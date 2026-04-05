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
public class CrunchyrollService {

    @Autowired
    private OkHttpClient okHttpClient;

    @Value("${app.crunchyroll.base-url}")
    private String baseUrl;

    @Value("${app.crunchyroll.auth-url}")
    private String authUrl;

    @Value("${app.crunchyroll.watchlist-url}")
    private String watchlistUrl;

    @Value("${app.crunchyroll.add-watchlist-url}")
    private String addWatchlistUrl;

    @Value("${app.crunchyroll.remove-watchlist-url}")
    private String removeWatchlistUrl;

    @Value("${app.crunchyroll.client-id}")
    private String clientId;

    @Value("${app.credentials.crunchyroll-device-id}")
    private String deviceId;

    @Value("${app.credentials.crunchyroll-etp-rt}")
    private String etpRt;

    @Value("${app.crunchyroll.device-type:Chrome on Chrome OS}")
    private String deviceType;

    private String accessToken;
    private String accountId;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void authenticate() throws IOException {
        RequestBody body = new FormBody.Builder()
                .add("device_id", deviceId)
                .add("device_type", deviceType)
                .add("grant_type", "etp_rt_cookie")
                .build();

        Request request = new Request.Builder()
                .url(baseUrl + authUrl)
                .addHeader("Authorization", "Basic " + clientId)
                .addHeader("Cookie", "etp_rt=" + etpRt)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .post(body)
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Auth failed: " + response);

            JsonNode json = objectMapper.readTree(response.body().string());
            accessToken = json.get("access_token").asText();
            accountId = json.get("account_id").asText();
        }
    }

    public List<Map<String, Object>> getWatchlist() throws IOException {
        ensureAuthenticated();
        String url = baseUrl + watchlistUrl.replace("{accountId}", accountId) + "?order=desc&n=100&locale=en-US";
        try (Response response = executeAuthorized(new Request.Builder()
                .url(url)
                .get())) {
            if (!response.isSuccessful()) {
                throw new IOException("Get watchlist failed: " + response);
            }

            JsonNode json = objectMapper.readTree(response.body().string());
            List<Map<String, Object>> entries = new ArrayList<>();
            for (JsonNode item : json.get("data")) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("seriesId", item.get("panel").get("episode_metadata").get("series_id").asText());
                entry.put("title", item.get("panel").get("episode_metadata").get("series_title").asText());
                entries.add(entry);
            }
            return entries;
        }
    }

    public void addToWatchlist(String contentId) throws IOException {
        ensureAuthenticated();
        String url = baseUrl + addWatchlistUrl.replace("{accountId}", accountId) + "?preferred_audio_language=ja-JP&locale=en-US";
        String jsonBody = "{\"content_id\":\"" + contentId + "\"}";
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));

        try (Response response = executeAuthorized(new Request.Builder()
                .url(url)
                .post(body)
                )) {
            if (!response.isSuccessful()) {
                throw new IOException("Add to watchlist failed: " + response);
            }
        }
    }

    public void removeFromWatchlist(String contentId) throws IOException {
        ensureAuthenticated();
        String url = baseUrl + removeWatchlistUrl.replace("{accountId}", accountId).replace("{contentId}", contentId) + "?preferred_audio_language=ja-JP&locale=en-US";
        try (Response response = executeAuthorized(new Request.Builder()
                .url(url)
                .delete()
                )) {
            if (!response.isSuccessful()) {
                throw new IOException("Remove from watchlist failed: " + response);
            }
        }
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getAccountId() {
        return accountId;
    }

    private Response executeAuthorized(Request.Builder builder) throws IOException {
        ensureAuthenticated();
        Response response = okHttpClient.newCall(withAccessToken(builder).build()).execute();
        if (response.code() != 401) {
            return response;
        }

        response.close();
        accessToken = null;
        authenticate();
        return okHttpClient.newCall(withAccessToken(builder).build()).execute();
    }

    private void ensureAuthenticated() throws IOException {
        if (accessToken == null || accountId == null) {
            authenticate();
        }
    }

    private Request.Builder withAccessToken(Request.Builder builder) {
        return builder.header("Authorization", "Bearer " + accessToken);
    }
}
