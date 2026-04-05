package com.mal2cy.service;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Service
public class MalOAuthBootstrapService {

    private static final Duration REQUEST_TTL = Duration.ofMinutes(10);

    private final OkHttpClient okHttpClient;
    private final AuthTokenStore authTokenStore;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, PendingAuthorization> pendingAuthorizations = new ConcurrentHashMap<>();

    @Value("${app.mal.auth-url}")
    private String authUrl;

    @Value("${app.mal.token-url}")
    private String tokenUrl;

    @Value("${app.mal.client-id}")
    private String clientId;

    @Value("${app.mal.client-secret}")
    private String clientSecret;

    @Value("${app.mal.redirect-uri:http://localhost:8080/auth/mal/callback}")
    private String redirectUri;

    public MalOAuthBootstrapService(OkHttpClient okHttpClient, AuthTokenStore authTokenStore, ObjectMapper objectMapper) {
        this.okHttpClient = okHttpClient;
        this.authTokenStore = authTokenStore;
        this.objectMapper = objectMapper;
    }

    public String buildAuthorizationUrl() {
        validateClientConfiguration();
        cleanupExpiredRequests();

        String state = UUID.randomUUID().toString();
        String codeVerifier = generateCodeVerifier();
        pendingAuthorizations.put(state, new PendingAuthorization(codeVerifier, Instant.now().plus(REQUEST_TTL)));

        return UriComponentsBuilder.fromUriString(authUrl)
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("code_challenge", codeVerifier)
                .queryParam("code_challenge_method", "plain")
                .queryParam("state", state)
                .build(true)
                .toUriString();
    }

    public void completeAuthorization(String code, String state) throws IOException {
        validateClientConfiguration();
        cleanupExpiredRequests();
        PendingAuthorization pendingAuthorization = pendingAuthorizations.remove(state);
        if (pendingAuthorization == null || pendingAuthorization.expiresAt().isBefore(Instant.now())) {
            throw new IOException("MAL authorization request is missing or expired. Start the flow again.");
        }

        RequestBody body = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("code", code)
                .add("code_verifier", pendingAuthorization.codeVerifier())
                .add("redirect_uri", redirectUri)
                .build();

        Request request = new Request.Builder()
                .url(tokenUrl)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .post(body)
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("MAL token exchange failed: " + response);
            }

            JsonNode json = objectMapper.readTree(response.body().string());
            String accessToken = json.get("access_token").asText();
            String refreshToken = json.get("refresh_token").asText();
            authTokenStore.saveMalTokens(accessToken, refreshToken);
        }
    }

    private void cleanupExpiredRequests() {
        Instant now = Instant.now();
        pendingAuthorizations.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private void validateClientConfiguration() {
        if (isBlank(clientId) || isBlank(clientSecret)) {
            throw new IllegalStateException(
                    "MAL client configuration is missing. Set MAL_CLIENT_ID and MAL_CLIENT_SECRET before using /auth/mal.");
        }
    }

    private String generateCodeVerifier() {
        byte[] bytes = new byte[64];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record PendingAuthorization(String codeVerifier, Instant expiresAt) {
    }
}
