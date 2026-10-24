package com.mal2cy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import com.mal2cy.model.AnimeEntry;
import com.mal2cy.model.CrunchyrollWatchHistoryEntry;
import com.mal2cy.repo.AnimeEntryRepository;
import com.mal2cy.repo.SyncResultRepository;
import com.mal2cy.service.AuthTokenStore;
import com.mal2cy.service.CrunchyrollMetadataService;
import com.mal2cy.service.CrunchyrollService;
import com.mal2cy.service.MalService;
import com.mal2cy.service.MatchStore;
import com.mal2cy.service.NotificationService;
import com.mal2cy.service.SyncService;
import com.mal2cy.service.TitleMatcher;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

class MatchingTest {

    @TempDir
    Path tempDir;

    private final CrunchyrollService crunchyrollService = mock(CrunchyrollService.class);
    private final MalService malService = mock(MalService.class);
    private final AnimeEntryRepository animeEntryRepository = mock(AnimeEntryRepository.class);
    private final SyncResultRepository syncResultRepository = mock(SyncResultRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);

    @Test
    void stripsSeasonAndSequelSuffixes() {
        assertEquals("Dandadan", TitleMatcher.stripSeasonSuffix("Dandadan 2nd Season"));
        assertEquals("Boku no Hero Academia", TitleMatcher.stripSeasonSuffix("Boku no Hero Academia 7th Season"));
        assertEquals("To Your Eternity", TitleMatcher.stripSeasonSuffix("To Your Eternity Season 3"));
        assertEquals("Shingeki no Kyojin", TitleMatcher.stripSeasonSuffix("Shingeki no Kyojin: The Final Season - Kanketsu-hen"));
        assertEquals("Spy x Family", TitleMatcher.stripSeasonSuffix("Spy x Family Part 2"));
        assertEquals("Mob Psycho 100", TitleMatcher.stripSeasonSuffix("Mob Psycho 100 III"));
        assertEquals("Ijiranaide, Nagatoro-san", TitleMatcher.stripSeasonSuffix("Ijiranaide, Nagatoro-san 2nd Attack"));
        assertEquals("Kimetsu no Yaiba", TitleMatcher.stripSeasonSuffix("Kimetsu no Yaiba: Katanakaji no Sato-hen"));
        assertEquals("Mashle", TitleMatcher.stripSeasonSuffix("Mashle: Shinkakusha Kouho Senbatsu Shiken-hen"));
        assertEquals("Sousou no Frieren", TitleMatcher.stripSeasonSuffix("Sousou no Frieren: Ougonkyou-hen"));
        assertNull(TitleMatcher.stripSeasonSuffix("One Piece"));
        assertNull(TitleMatcher.stripSeasonSuffix("Hawaii Five"));
        assertNull(TitleMatcher.stripSeasonSuffix("Mob Psycho 100"));
    }

    @Test
    void watchlistTitleMatchesMalListWithoutCallingJikan() throws Exception {
        when(crunchyrollService.getWatchlist()).thenReturn(List.of(
                new HashMap<>(Map.of("seriesId", "GRMG8ZQZR", "title", "ONE PIECE"))));
        when(malService.getAnimeList()).thenReturn(List.of(Map.of(
                "malId", "21", "title", "One Piece", "status", "watching",
                "titleCandidates", List.of("One Piece"))));

        newSyncService(newMatchStore()).performSync();

        verify(malService, never()).findMalIdByTitle(anyString());
        verify(animeEntryRepository).save(argThat((AnimeEntry e) ->
                "21".equals(e.getMalId()) && "GRMG8ZQZR".equals(e.getCrunchyrollId())));
    }

    @Test
    void sequelMapsToParentCrunchyrollSeries() throws Exception {
        when(crunchyrollService.getWatchlist()).thenReturn(List.of());
        when(malService.getAnimeList()).thenReturn(List.of(Map.of(
                "malId", "60543", "title", "Dandadan 2nd Season", "status", "completed",
                "titleCandidates", List.of("Dan Da Dan Season 2", "Dandadan 2nd Season"))));
        when(crunchyrollService.findSeriesIdByTitles(List.of("Dan Da Dan Season 2", "Dandadan 2nd Season"))).thenReturn(null);
        when(crunchyrollService.findSeriesIdByTitles(List.of("Dan Da Dan", "Dandadan"))).thenReturn("GG5H5XQ0D");

        newSyncService(newMatchStore()).performSync();

        verify(animeEntryRepository).save(argThat((AnimeEntry e) ->
                "60543".equals(e.getMalId()) && "GG5H5XQ0D".equals(e.getCrunchyrollId())));
    }

    @Test
    void recentMissesAreNotSearchedAgainAndOverridesWin() throws Exception {
        MatchStore matchStore = newMatchStore();
        Files.writeString(tempDir.resolve("overrides.json"),
                "{\"malToCrunchyroll\":{\"2\":\"GOVERRIDE\"},\"crunchyrollToMal\":{}}");
        when(crunchyrollService.getWatchlist()).thenReturn(List.of());
        when(malService.getAnimeList()).thenReturn(List.of(
                Map.of("malId", "1", "title", "Netflix Only", "status", "completed", "titleCandidates", List.of("Netflix Only")),
                Map.of("malId", "2", "title", "Odd Title", "status", "completed", "titleCandidates", List.of("Odd Title"))));

        SyncService syncService = newSyncService(matchStore);
        syncService.performSync();
        syncService.performSync();

        verify(crunchyrollService, times(1)).findSeriesIdByTitles(List.of("Netflix Only"));
        verify(crunchyrollService, never()).findSeriesIdByTitles(List.of("Odd Title"));
        assertTrue(matchStore.recentlyMissed("mal:1"));
        assertFalse(matchStore.recentlyMissed("mal:2"));
    }

    @Test
    void failedSyncSendsNotification() throws Exception {
        when(crunchyrollService.getWatchlist()).thenThrow(new java.io.IOException("Auth failed: 400"));

        newSyncService(newMatchStore()).performSync();

        verify(notificationService).notifyFailure(eq("mal2cy sync failed"), eq("Auth failed: 400"));
    }

    @Test
    void historyForSeriesSharedByTwoSeasonsGoesToTheSeasonBeingWatched() throws Exception {
        AnimeEntry season1 = entry(1L, "52991", "GG5H5XQX4");
        AnimeEntry season2 = entry(2L, "59978", "GG5H5XQX4");
        when(animeEntryRepository.findAllByCrunchyrollIdOrderByIdAsc("GG5H5XQX4")).thenReturn(List.of(season1, season2));
        when(crunchyrollService.getWatchlist()).thenReturn(List.of());
        when(malService.getAnimeList()).thenReturn(List.of(
                Map.of("malId", "52991", "title", "Sousou no Frieren", "status", "completed",
                        "watchedEpisodes", 28, "totalEpisodes", 28, "titleCandidates", List.of("Sousou no Frieren")),
                Map.of("malId", "59978", "title", "Sousou no Frieren 2nd Season", "status", "watching",
                        "watchedEpisodes", 2, "totalEpisodes", 10, "titleCandidates", List.of("Sousou no Frieren 2nd Season"))));
        when(crunchyrollService.getWatchHistorySince(any())).thenReturn(List.of(
                new CrunchyrollWatchHistoryEntry("ep", "GG5H5XQX4", "Frieren: Beyond Journey's End", 3,
                        Instant.parse("2026-10-20T00:00:00Z"), true)));

        newSyncService(newMatchStore()).performSync();

        verify(malService).updateAnimeProgress("59978", 3, "watching");
        verify(malService, never()).updateAnimeProgress(eq("52991"), anyInt(), anyString());
    }

    @Test
    void historyWithAbsoluteEpisodeNumberBeyondMalCountIsSkipped() throws Exception {
        AnimeEntry mapping = entry(1L, "59978", "GG5H5XQX4");
        when(animeEntryRepository.findAllByCrunchyrollIdOrderByIdAsc("GG5H5XQX4")).thenReturn(List.of(mapping));
        when(crunchyrollService.getWatchlist()).thenReturn(List.of());
        when(malService.getAnimeList()).thenReturn(List.of(
                Map.of("malId", "59978", "title", "Sousou no Frieren 2nd Season", "status", "watching",
                        "watchedEpisodes", 2, "totalEpisodes", 10, "titleCandidates", List.of("Sousou no Frieren 2nd Season"))));
        when(crunchyrollService.getWatchHistorySince(any())).thenReturn(List.of(
                new CrunchyrollWatchHistoryEntry("ep", "GG5H5XQX4", "Frieren", 31, Instant.parse("2026-10-20T00:00:00Z"), true)));

        newSyncService(newMatchStore()).performSync();

        verify(malService, never()).updateAnimeProgress(anyString(), anyInt(), anyString());
    }

    @Test
    void jikanLookupRetriesOn429AndOnlyAcceptsAMatchingTitle() throws Exception {
        OkHttpClient client = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        when(client.newCall(any(Request.class))).thenReturn(call);
        when(call.execute())
                .thenReturn(response(429, "{}"))
                .thenReturn(response(200, "{\"data\":["
                        + "{\"mal_id\":1,\"title\":\"One Piece Film: Red\",\"title_english\":\"One Piece Film Red\"},"
                        + "{\"mal_id\":21,\"title\":\"One Piece\",\"title_english\":\"One Piece\"}]}"))
                .thenReturn(response(200, "{\"data\":[{\"mal_id\":5,\"title\":\"Something Else\"}]}"));

        MalService service = new MalService();
        ReflectionTestUtils.setField(service, "okHttpClient", client);
        ReflectionTestUtils.setField(service, "jikanMinIntervalMillis", 1L);

        assertEquals("21", service.findMalIdByTitle("ONE PIECE"));
        assertNull(service.findMalIdByTitle("Unrelated Show"));
        verify(client, times(2)).newCall(argThat(request -> "ONE PIECE".equals(request.url().queryParameter("q"))));
    }

    private MatchStore newMatchStore() {
        return new MatchStore(tempDir.resolve("misses.json").toString(), tempDir.resolve("overrides.json").toString(), 7);
    }

    private SyncService newSyncService(MatchStore matchStore) {
        SyncService syncService = new SyncService();
        ReflectionTestUtils.setField(syncService, "crunchyrollService", crunchyrollService);
        ReflectionTestUtils.setField(syncService, "malService", malService);
        ReflectionTestUtils.setField(syncService, "animeEntryRepository", animeEntryRepository);
        ReflectionTestUtils.setField(syncService, "syncResultRepository", syncResultRepository);
        ReflectionTestUtils.setField(syncService, "crunchyrollMetadataService", mock(CrunchyrollMetadataService.class));
        ReflectionTestUtils.setField(syncService, "authTokenStore", mock(AuthTokenStore.class));
        ReflectionTestUtils.setField(syncService, "matchStore", matchStore);
        ReflectionTestUtils.setField(syncService, "notificationService", notificationService);
        ReflectionTestUtils.setField(syncService, "conflictResolution", "last-write-wins");
        ReflectionTestUtils.setField(syncService, "watchHistoryReplayHours", 48L);
        return syncService;
    }

    private static AnimeEntry entry(Long id, String malId, String crunchyrollId) {
        AnimeEntry entry = new AnimeEntry();
        entry.setId(id);
        entry.setMalId(malId);
        entry.setCrunchyrollId(crunchyrollId);
        entry.setSource("mal");
        entry.setStatus("watching");
        entry.setLastUpdated(java.time.LocalDateTime.now().minusDays(1));
        return entry;
    }

    private static Response response(int code, String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://api.jikan.test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("status")
                .body(ResponseBody.create(body, okhttp3.MediaType.parse("application/json")))
                .build();
    }
}
