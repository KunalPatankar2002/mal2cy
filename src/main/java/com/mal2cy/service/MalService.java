package com.mal2cy.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

@Service
public class MalService {

    @Autowired
    private OkHttpClient okHttpClient;

    @Value("${app.mal.base-url}")
    private String baseUrl;

    @Value("${app.mal.token-url}")
    private String tokenUrl;

    @Value("${app.mal.client-id}")
    private String clientId;

    @Value("${app.mal.client-secret}")
    private String clientSecret;

    @Value("${app.credentials.mal-access-token}")
    private String accessToken;

    @Value("${app.credentials.mal-refresh-token}")
    private String refreshToken;

    @Autowired
    private AuthTokenStore authTokenStore;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<Map<String, Object>> getAnimeList() throws IOException {
        String url = baseUrl + "/users/@me/animelist?fields=list_status&limit=1000";
        ensureTokensLoaded();
        Request.Builder builder = new Request.Builder()
                .url(url)
                .get();

        try (Response response = executeAuthorized(builder)) {
            if (!response.isSuccessful()) {
                throw new IOException("Get MAL list failed: " + response);
            }

            JsonNode json = objectMapper.readTree(response.body().string());
            List<Map<String, Object>> entries = new ArrayList<>();
            for (JsonNode item : json.get("data")) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("malId", item.get("node").get("id").asText());
                entry.put("title", item.get("node").get("title").asText());
                entry.put("status", item.get("list_status").get("status").asText());
                entries.add(entry);
            }
            return entries;
        }
    }

    public void updateAnimeStatus(String malId, String status) throws IOException {
        String url = baseUrl + "/anime/" + malId + "/my_list_status";
        String jsonBody = "{\"status\":\"" + status + "\"}";
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));

        ensureTokensLoaded();
        Request.Builder builder = new Request.Builder()
                .url(url)
                .patch(body)
                ;

        try (Response response = executeAuthorized(builder)) {
            if (!response.isSuccessful()) {
                throw new IOException("Update MAL status failed: " + response);
            }
        }
    }

    public String findMalIdByTitle(String title) throws IOException {
        // Use Jikan for fuzzy matching
        String jikanUrl = "https://api.jikan.moe/v4/anime?q=" + title.replace(" ", "%20") + "&limit=1";
        Request request = new Request.Builder()
                .url(jikanUrl)
                .get()
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return null;

            JsonNode json = objectMapper.readTree(response.body().string());
            if (json.get("data").size() > 0) {
                return json.get("data").get(0).get("mal_id").asText();
            }
        }
        return null;
    }

    private Response executeAuthorized(Request.Builder builder) throws IOException {
        Response response = okHttpClient.newCall(withAccessToken(builder).build()).execute();
        if (response.code() != 401) {
            return response;
        }

        response.close();
        refreshAccessToken();
        return okHttpClient.newCall(withAccessToken(builder).build()).execute();
    }

    private Request.Builder withAccessToken(Request.Builder builder) {
        return builder.header("Authorization", "Bearer " + accessToken);
    }

    private synchronized void ensureTokensLoaded() {
        Map<String, String> persistedTokens = authTokenStore.loadTokens();
        if (isBlank(accessToken)) {
            accessToken = persistedTokens.get("malAccessToken");
        }
        if (isBlank(refreshToken)) {
            refreshToken = persistedTokens.get("malRefreshToken");
        }
    }

    private synchronized void refreshAccessToken() throws IOException {
        ensureTokensLoaded();
        if (isBlank(refreshToken)) {
            throw new IOException("MAL access token expired and no refresh token is configured.");
        }
        if (isBlank(clientId) || isBlank(clientSecret)) {
            throw new IOException("MAL client configuration is missing. Set MAL_CLIENT_ID and MAL_CLIENT_SECRET.");
        }

        RequestBody body = new FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .build();

        Request request = new Request.Builder()
                .url(tokenUrl)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .post(body)
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Refresh MAL token failed: " + response);
            }

            JsonNode json = objectMapper.readTree(response.body().string());
            accessToken = json.get("access_token").asText();
            if (json.hasNonNull("refresh_token")) {
                refreshToken = json.get("refresh_token").asText();
            }
            authTokenStore.saveMalTokens(accessToken, refreshToken);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
