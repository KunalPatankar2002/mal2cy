package com.mal2cy.service;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Logs in to Crunchyroll the way its Android TV app does: a one-time activation code
 * entered at crunchyroll.com/activate yields a refresh token, which is renewed (and
 * rotated) on every login so the session never needs a hand-copied cookie.
 */
@Service
public class CrunchyrollTvAuthService {

    private static final Logger log = LoggerFactory.getLogger(CrunchyrollTvAuthService.class);

    static final String REFRESH_TOKEN_KEY = "crunchyrollTvRefreshToken";
    static final String DEVICE_ID_KEY = "crunchyrollTvDeviceId";
    public static final String ACTIVATION_URL = "https://www.crunchyroll.com/activate";

    private static final long POLL_INTERVAL_SECONDS = 5;

    @Autowired
    private OkHttpClient okHttpClient;

    @Autowired
    private AuthTokenStore authTokenStore;

    @Value("${app.crunchyroll.base-url}")
    private String baseUrl;

    @Value("${app.crunchyroll.auth-url}")
    private String authUrl;

    @Value("${app.crunchyroll.device-code-url:/auth/v1/device/code}")
    private String deviceCodeUrl;

    @Value("${app.crunchyroll.device-token-url:/auth/v1/device/token}")
    private String deviceTokenUrl;

    @Value("${app.crunchyroll.account-url:/accounts/v1/me}")
    private String accountUrl;

    @Value("${app.crunchyroll.tv-client-auth}")
    private String tvClientAuth;

    @Value("${app.crunchyroll.tv-user-agent}")
    private String tvUserAgent;

    @Value("${app.crunchyroll.tv-device-name:mal2cy}")
    private String tvDeviceName;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "crunchyroll-activation");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Activation activation;

    public record TokenResult(String accessToken, String accountId) {
    }

    public record Activation(String userCode, Instant expiresAt, String status, String message) {
    }

    public boolean isActivated() {
        return !isBlank(authTokenStore.loadValue(REFRESH_TOKEN_KEY));
    }

    /**
     * Starts a new activation (or returns the one still waiting for the code to be
     * entered) and polls Crunchyroll in the background until it completes or expires.
     */
    public synchronized Activation startActivation() throws IOException {
        Activation current = activation;
        if (current != null && "pending".equals(current.status()) && Instant.now().isBefore(current.expiresAt())) {
            return current;
        }

        String deviceId = UUID.randomUUID().toString();
        JsonNode code;
        try (Response response = okHttpClient.newCall(tvRequest(deviceCodeUrl)
                .post(new FormBody.Builder().build())
                .build()).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Crunchyroll activation code request failed: HTTP " + response.code() + " " + body);
            }
            code = objectMapper.readTree(body);
        }

        String deviceCode = code.path("device_code").asText(null);
        String userCode = code.path("user_code").asText(null);
        if (isBlank(deviceCode) || isBlank(userCode)) {
            throw new IOException("Crunchyroll activation code response was missing device_code or user_code");
        }

        Instant expiresAt = Instant.now().plusSeconds(code.path("expires_in").asLong(300));
        activation = new Activation(userCode, expiresAt, "pending", null);
        log.info("Crunchyroll activation started; enter code {} at {} before {}", userCode, ACTIVATION_URL, expiresAt);
        poller.schedule(() -> pollActivation(deviceCode, deviceId, expiresAt), POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        return activation;
    }

    public Activation getActivation() {
        return activation;
    }

    private void pollActivation(String deviceCode, String deviceId, Instant expiresAt) {
        try {
            TokenResult result = pollDeviceToken(deviceCode, deviceId);
            if (result != null) {
                activation = new Activation(activation.userCode(), expiresAt, "activated", null);
                log.info("Crunchyroll activation completed for account_id={}", result.accountId());
                return;
            }
            if (Instant.now().isAfter(expiresAt)) {
                activation = new Activation(activation.userCode(), expiresAt, "expired", "The code expired before it was entered.");
                log.warn("Crunchyroll activation code expired before it was entered");
                return;
            }
            poller.schedule(() -> pollActivation(deviceCode, deviceId, expiresAt), POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            activation = new Activation(activation.userCode(), expiresAt, "failed", e.getMessage());
            log.error("Crunchyroll activation failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Polls once for the tokens behind an activation code. Returns null while the code
     * has not been entered yet; saves the refresh token once it has.
     */
    public TokenResult pollDeviceToken(String deviceCode, String deviceId) throws IOException {
        String json = objectMapper.writeValueAsString(java.util.Map.of("device_code", deviceCode));
        try (Response response = okHttpClient.newCall(tvRequest(deviceTokenUrl)
                .header("Accept", "application/json")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build()).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (response.code() == 204 || body.contains("authorization_pending")) {
                return null;
            }
            if (!response.isSuccessful()) {
                throw new IOException("Crunchyroll activation failed: HTTP " + response.code() + " " + body);
            }
            return saveTokens(objectMapper.readTree(body), deviceId);
        }
    }

    /**
     * Exchanges the saved refresh token for a new access token. Crunchyroll rotates the
     * refresh token, so the new one is saved for the next login.
     */
    public TokenResult refresh() throws IOException {
        String refreshToken = authTokenStore.loadValue(REFRESH_TOKEN_KEY);
        String deviceId = authTokenStore.loadValue(DEVICE_ID_KEY);
        if (isBlank(refreshToken)) {
            throw new IOException("Crunchyroll TV login is not activated");
        }
        if (isBlank(deviceId)) {
            deviceId = UUID.randomUUID().toString();
        }

        try (Response response = okHttpClient.newCall(tvRequest(authUrl)
                .post(new FormBody.Builder()
                        .add("refresh_token", refreshToken)
                        .add("grant_type", "refresh_token")
                        .add("scope", "offline_access")
                        .add("device_id", deviceId)
                        .add("device_name", tvDeviceName)
                        .add("device_type", "ANDROIDTV")
                        .build())
                .build()).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Crunchyroll TV token refresh failed: HTTP " + response.code() + " " + body);
            }
            return saveTokens(objectMapper.readTree(body), deviceId);
        }
    }

    private TokenResult saveTokens(JsonNode json, String deviceId) throws IOException {
        String accessToken = json.path("access_token").asText(null);
        if (isBlank(accessToken)) {
            throw new IOException("Crunchyroll token response was missing access_token");
        }

        String refreshToken = json.path("refresh_token").asText(null);
        if (!isBlank(refreshToken)) {
            authTokenStore.saveValue(REFRESH_TOKEN_KEY, refreshToken);
            authTokenStore.saveValue(DEVICE_ID_KEY, deviceId);
        }

        String accountId = json.path("account_id").asText(null);
        if (isBlank(accountId)) {
            accountId = fetchAccountId(accessToken);
        }
        return new TokenResult(accessToken, accountId);
    }

    private String fetchAccountId(String accessToken) throws IOException {
        try (Response response = okHttpClient.newCall(new Request.Builder()
                .url(baseUrl + accountUrl)
                .header("Authorization", "Bearer " + accessToken)
                .header("User-Agent", tvUserAgent)
                .get()
                .build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Crunchyroll account lookup failed: HTTP " + response.code());
            }
            return objectMapper.readTree(response.body().string()).path("account_id").asText(null);
        }
    }

    private Request.Builder tvRequest(String path) {
        return new Request.Builder()
                .url(baseUrl + path)
                .header("Authorization", "Basic " + tvClientAuth)
                .header("User-Agent", tvUserAgent);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
