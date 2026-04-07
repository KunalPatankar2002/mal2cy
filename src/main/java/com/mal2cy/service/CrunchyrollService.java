package com.mal2cy.service;

import java.io.IOException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mal2cy.model.CrunchyrollWatchHistoryEntry;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Service
public class CrunchyrollService {

    private static final Logger log = LoggerFactory.getLogger(CrunchyrollService.class);

    @Autowired
    private OkHttpClient okHttpClient;

    @Value("${app.crunchyroll.base-url}")
    private String baseUrl;

    @Value("${app.crunchyroll.auth-url}")
    private String authUrl;

    @Value("${app.crunchyroll.watchlist-url}")
    private String watchlistUrl;

    @Value("${app.crunchyroll.search-url:/content/v2/discover/search}")
    private String searchUrl;

    @Value("${app.crunchyroll.watch-history-url:/content/v2/{accountId}/watch-history}")
    private String watchHistoryUrl;

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

    @Value("${app.crunchyroll.search-score-threshold:120}")
    private double searchScoreThreshold;

    @Value("${app.crunchyroll.watch-history-page-size:100}")
    private int watchHistoryPageSize;

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

    /**
     * Reads Crunchyroll watch history newer than the provided cutoff. Only fully
     * watched entries with an integer episode number are returned.
     */
    public List<CrunchyrollWatchHistoryEntry> getWatchHistorySince(Instant cutoff) throws IOException {
        ensureAuthenticated();
        String resolvedWatchHistoryUrl = watchHistoryUrl.replace("{accountId}", accountId);
        HttpUrl url = HttpUrl.parse(baseUrl + resolvedWatchHistoryUrl).newBuilder()
                .addQueryParameter("page_size", String.valueOf(watchHistoryPageSize))
                .addQueryParameter("locale", "en-US")
                .build();
        log.info("Fetching Crunchyroll watch history since cutoff={}", cutoff);

        try (Response response = executeAuthorized(new Request.Builder()
                .url(url)
                .get())) {
            if (!response.isSuccessful()) {
                throw new IOException("Get watch history failed: " + response);
            }

            JsonNode json = objectMapper.readTree(response.body().string());
            JsonNode items = json.path("data");
            List<CrunchyrollWatchHistoryEntry> entries = new ArrayList<>();
            boolean hitOlderEntry = false;

            for (JsonNode item : items) {
                Instant datePlayed = parseInstant(item.path("date_played").asText(null));
                if (datePlayed == null) {
                    log.debug("Rejected watch history item id={} because date_played was missing or invalid",
                            item.path("id").asText(null));
                    continue;
                }

                if (cutoff != null && datePlayed.isBefore(cutoff)) {
                    log.info("Stopping watch history read at id={} because date_played={} is older than cutoff={}",
                            item.path("id").asText(null), datePlayed, cutoff);
                    hitOlderEntry = true;
                    break;
                }

                if (!item.path("fully_watched").asBoolean(false)) {
                    log.debug("Rejected watch history item id={} because fully_watched=false",
                            item.path("id").asText(null));
                    continue;
                }

                JsonNode episodeMetadata = item.path("panel").path("episode_metadata");
                if (!episodeMetadata.path("episode_number").canConvertToInt()) {
                    log.debug("Rejected watch history item id={} because episode_number was not an integer",
                            item.path("id").asText(null));
                    continue;
                }

                String seriesId = episodeMetadata.path("series_id").asText(null);
                String seriesTitle = episodeMetadata.path("series_title").asText(null);
                if (isBlank(seriesId) || isBlank(seriesTitle)) {
                    log.debug("Rejected watch history item id={} because series metadata was incomplete",
                            item.path("id").asText(null));
                    continue;
                }

                CrunchyrollWatchHistoryEntry entry = new CrunchyrollWatchHistoryEntry(
                        item.path("id").asText(null),
                        seriesId,
                        seriesTitle,
                        episodeMetadata.path("episode_number").asInt(),
                        datePlayed,
                        true);
                log.info("Accepted watch history item series_id={} series_title='{}' episode={} date_played={}",
                        entry.seriesId(), entry.seriesTitle(), entry.episodeNumber(), entry.datePlayed());
                entries.add(entry);
            }

            if (!hitOlderEntry && cutoff != null && items.size() >= watchHistoryPageSize) {
                log.warn("Crunchyroll watch history may be truncated: first page returned {} item(s) and none were older than cutoff={}",
                        items.size(), cutoff);
            }

            return entries;
        }
    }

    public String findSeriesIdByTitles(List<String> titles) throws IOException {
        log.info("Crunchyroll search started for {} title candidate(s): {}", titles.size(), titles);
        for (String title : titles) {
            String contentId = findSeriesIdByTitle(title);
            if (contentId != null) {
                log.info("Crunchyroll search resolved content_id={} using title candidate='{}'", contentId, title);
                return contentId;
            }
        }
        log.info("Crunchyroll search resolved no match for title candidates={}", titles);
        return null;
    }

    public String findSeriesIdByTitle(String title) throws IOException {
        if (isBlank(title)) {
            return null;
        }

        ensureAuthenticated();
        HttpUrl url = HttpUrl.parse(baseUrl + searchUrl).newBuilder()
                .addQueryParameter("q", title)
                .addQueryParameter("n", "10")
                .addQueryParameter("type", "series")
                .addQueryParameter("preferred_audio_language", "ja-JP")
                .addQueryParameter("locale", "en-US")
                .build();
        log.info("Searching Crunchyroll series for title candidate='{}'", title);

        try (Response response = executeAuthorized(new Request.Builder()
                .url(url)
                .get())) {
            if (!response.isSuccessful()) {
                throw new IOException("Crunchyroll search failed: " + response);
            }

            JsonNode json = objectMapper.readTree(response.body().string());
            for (JsonNode section : json.path("data")) {
                if (!"series".equals(section.path("type").asText())) {
                    continue;
                }
                return findMatchingSeriesId(section.path("items"), title);
            }
        }

        log.info("Crunchyroll returned no series section for title candidate='{}'", title);
        return null;
    }

    public void addToWatchlist(String contentId) throws IOException {
        ensureAuthenticated();
        String url = baseUrl + addWatchlistUrl.replace("{accountId}", accountId) + "?preferred_audio_language=ja-JP&locale=en-US";
        String jsonBody = "{\"content_id\":\"" + contentId + "\"}";
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        log.info("Adding Crunchyroll watchlist entry content_id={}", contentId);

        try (Response response = executeAuthorized(new Request.Builder()
                .url(url)
                .post(body)
                )) {
            if (!response.isSuccessful()) {
                throw new IOException("Add to watchlist failed: " + response);
            }
            log.info("Added Crunchyroll watchlist entry content_id={}", contentId);
        }
    }

    public void removeFromWatchlist(String contentId) throws IOException {
        ensureAuthenticated();
        String url = baseUrl + removeWatchlistUrl.replace("{accountId}", accountId).replace("{contentId}", contentId) + "?preferred_audio_language=ja-JP&locale=en-US";
        log.info("Removing Crunchyroll watchlist entry content_id={}", contentId);
        try (Response response = executeAuthorized(new Request.Builder()
                .url(url)
                .delete()
                )) {
            if (!response.isSuccessful()) {
                throw new IOException("Remove from watchlist failed: " + response);
            }
            log.info("Removed Crunchyroll watchlist entry content_id={}", contentId);
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

    private String findMatchingSeriesId(JsonNode items, String title) {
        String normalizedSearchTitle = normalizeTitle(title);
        log.debug("Evaluating {} Crunchyroll series candidate(s) for title candidate='{}'", items.size(), title);

        for (JsonNode item : items) {
            String candidateId = item.path("id").asText(null);
            String itemTitle = item.path("title").asText("");
            String slugTitle = item.path("slug_title").asText("");
            double score = item.path("search_metadata").path("score").asDouble(0.0d);
            boolean meetsThreshold = scoreMeetsThreshold(item);
            boolean titleMatch = normalizedSearchTitle.equals(normalizeTitle(itemTitle));
            boolean slugMatch = normalizedSearchTitle.equals(normalizeTitle(slugTitle));

            if (!meetsThreshold) {
                log.debug(
                        "Rejected Crunchyroll candidate id={} title='{}' slug='{}' score={} for title candidate='{}' because score is below threshold={}",
                        candidateId,
                        itemTitle,
                        slugTitle,
                        score,
                        title,
                        searchScoreThreshold);
                continue;
            }

            if (titleMatch || slugMatch) {
                log.info(
                        "Accepted Crunchyroll candidate id={} title='{}' slug='{}' score={} for title candidate='{}'",
                        candidateId,
                        itemTitle,
                        slugTitle,
                        score,
                        title);
                return candidateId;
            }

            log.debug(
                    "Rejected Crunchyroll candidate id={} title='{}' slug='{}' score={} for title candidate='{}' because normalized title did not match",
                    candidateId,
                    itemTitle,
                    slugTitle,
                    score,
                    title);
        }

        log.info("Rejected all Crunchyroll series candidates for title candidate='{}'", title);
        return null;
    }

    private boolean scoreMeetsThreshold(JsonNode item) {
        return item.path("search_metadata").path("score").asDouble(0.0d) >= searchScoreThreshold;
    }

    private String normalizeTitle(String title) {
        if (isBlank(title)) {
            return "";
        }

        String normalized = Normalizer.normalize(title, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Instant parseInstant(String value) {
        if (isBlank(value)) {
            return null;
        }

        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
