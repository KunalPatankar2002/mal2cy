package com.mal2cy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mal2cy.model.AnimeEntry;
import com.mal2cy.model.CrunchyrollWatchHistoryEntry;
import com.mal2cy.model.SyncResult;
import com.mal2cy.repo.AnimeEntryRepository;
import com.mal2cy.repo.SyncResultRepository;
import com.mal2cy.service.AuthTokenStore;
import com.mal2cy.service.CrunchyrollMetadataService;
import com.mal2cy.service.CrunchyrollService;
import com.mal2cy.service.MalService;
import com.mal2cy.service.SyncService;

import java.time.Instant;

class SyncServiceTest {

    @Test
    void performSyncSkipsUnsafeCrunchyrollWritesAndUpdatesMal() throws Exception {
        CrunchyrollService crunchyrollService = mock(CrunchyrollService.class);
        MalService malService = mock(MalService.class);
        AnimeEntryRepository animeEntryRepository = mock(AnimeEntryRepository.class);
        SyncResultRepository syncResultRepository = mock(SyncResultRepository.class);
        CrunchyrollMetadataService crunchyrollMetadataService = mock(CrunchyrollMetadataService.class);
        AuthTokenStore authTokenStore = mock(AuthTokenStore.class);

        when(crunchyrollService.getWatchlist()).thenReturn(List.of(
                Map.of("seriesId", "cr-1", "title", "Frieren")));
        when(crunchyrollService.getAccessToken()).thenReturn("secret-token");
        when(crunchyrollMetadataService.getSeriesMetadata("secret-token", "cr-1"))
                .thenReturn(new ObjectMapper().readTree("{\"id\":\"cr-1\"}"));
        when(malService.getAnimeList()).thenReturn(List.of(
                Map.of(
                        "malId", "mal-2",
                        "title", "Ikkoku Nikki",
                        "status", "watching",
                        "watchedEpisodes", 0,
                        "totalEpisodes", 13,
                        "titleCandidates", List.of("Journal with Witch", "Ikkoku Nikki"))));
        when(malService.findMalIdByTitle("Frieren")).thenReturn("mal-1");
        when(crunchyrollService.findSeriesIdByTitles(List.of("Journal with Witch", "Ikkoku Nikki"))).thenReturn("cr-2");
        when(animeEntryRepository.findByMalId("mal-2")).thenReturn(null);
        when(crunchyrollService.getWatchHistorySince(null)).thenReturn(List.of());
        when(authTokenStore.loadValue("crunchyrollWatchHistoryCursor")).thenReturn(null);

        SyncService syncService = new SyncService();
        ReflectionTestUtils.setField(syncService, "crunchyrollService", crunchyrollService);
        ReflectionTestUtils.setField(syncService, "malService", malService);
        ReflectionTestUtils.setField(syncService, "animeEntryRepository", animeEntryRepository);
        ReflectionTestUtils.setField(syncService, "syncResultRepository", syncResultRepository);
        ReflectionTestUtils.setField(syncService, "crunchyrollMetadataService", crunchyrollMetadataService);
        ReflectionTestUtils.setField(syncService, "authTokenStore", authTokenStore);
        ReflectionTestUtils.setField(syncService, "conflictResolution", "last-write-wins");
        ReflectionTestUtils.setField(syncService, "watchHistoryReplayHours", 48L);

        syncService.performSync();

        verify(crunchyrollService).addToWatchlist("cr-2");
        verify(malService).updateAnimeStatus("mal-1", "watching");
        verify(malService).updateAnimeStatus("mal-2", "watching");
        verify(syncResultRepository, times(4)).save(any(SyncResult.class));
    }

    @Test
    void performSyncAdvancesMalProgressFromWatchHistoryAndStoresCursor() throws Exception {
        CrunchyrollService crunchyrollService = mock(CrunchyrollService.class);
        MalService malService = mock(MalService.class);
        AnimeEntryRepository animeEntryRepository = mock(AnimeEntryRepository.class);
        SyncResultRepository syncResultRepository = mock(SyncResultRepository.class);
        CrunchyrollMetadataService crunchyrollMetadataService = mock(CrunchyrollMetadataService.class);
        AuthTokenStore authTokenStore = mock(AuthTokenStore.class);

        AnimeEntry existingMapping = new AnimeEntry();
        existingMapping.setMalId("mal-1");
        existingMapping.setCrunchyrollId("GT00365571");
        existingMapping.setTitle("Journal with Witch");

        when(crunchyrollService.getWatchlist()).thenReturn(List.of());
        when(malService.getAnimeList()).thenReturn(List.of(
                Map.of(
                        "malId", "mal-1",
                        "title", "Journal with Witch",
                        "status", "watching",
                        "watchedEpisodes", 2,
                        "totalEpisodes", 13,
                        "titleCandidates", List.of("Journal with Witch"))));
        when(authTokenStore.loadValue("crunchyrollWatchHistoryCursor")).thenReturn(null);
        when(crunchyrollService.getWatchHistorySince(null)).thenReturn(List.of(
                new CrunchyrollWatchHistoryEntry("ep-10", "GT00365571", "Journal with Witch", 10, Instant.parse("2026-04-07T04:00:37Z"), true),
                new CrunchyrollWatchHistoryEntry("ep-11", "GT00365571", "Journal with Witch", 11, Instant.parse("2026-04-07T08:44:37Z"), true)));
        when(animeEntryRepository.findByCrunchyrollId("GT00365571")).thenReturn(existingMapping);

        SyncService syncService = new SyncService();
        ReflectionTestUtils.setField(syncService, "crunchyrollService", crunchyrollService);
        ReflectionTestUtils.setField(syncService, "malService", malService);
        ReflectionTestUtils.setField(syncService, "animeEntryRepository", animeEntryRepository);
        ReflectionTestUtils.setField(syncService, "syncResultRepository", syncResultRepository);
        ReflectionTestUtils.setField(syncService, "crunchyrollMetadataService", crunchyrollMetadataService);
        ReflectionTestUtils.setField(syncService, "authTokenStore", authTokenStore);
        ReflectionTestUtils.setField(syncService, "conflictResolution", "last-write-wins");
        ReflectionTestUtils.setField(syncService, "watchHistoryReplayHours", 48L);

        syncService.performSync();

        verify(malService).updateAnimeProgress("mal-1", 11, "watching");
        verify(authTokenStore).saveValue("crunchyrollWatchHistoryCursor", "2026-04-07T08:44:37Z");
    }

    @Test
    void performSyncMarksCompletedWhenHistoryReachesTotalEpisodeCount() throws Exception {
        CrunchyrollService crunchyrollService = mock(CrunchyrollService.class);
        MalService malService = mock(MalService.class);
        AnimeEntryRepository animeEntryRepository = mock(AnimeEntryRepository.class);
        SyncResultRepository syncResultRepository = mock(SyncResultRepository.class);
        CrunchyrollMetadataService crunchyrollMetadataService = mock(CrunchyrollMetadataService.class);
        AuthTokenStore authTokenStore = mock(AuthTokenStore.class);

        AnimeEntry existingMapping = new AnimeEntry();
        existingMapping.setMalId("mal-1");
        existingMapping.setCrunchyrollId("GT00365571");
        existingMapping.setTitle("Journal with Witch");

        when(crunchyrollService.getWatchlist()).thenReturn(List.of());
        when(malService.getAnimeList()).thenReturn(List.of(
                Map.of(
                        "malId", "mal-1",
                        "title", "Journal with Witch",
                        "status", "watching",
                        "watchedEpisodes", 12,
                        "totalEpisodes", 13,
                        "titleCandidates", List.of("Journal with Witch"))));
        when(authTokenStore.loadValue("crunchyrollWatchHistoryCursor")).thenReturn(null);
        when(crunchyrollService.getWatchHistorySince(null)).thenReturn(List.of(
                new CrunchyrollWatchHistoryEntry("ep-13", "GT00365571", "Journal with Witch", 13, Instant.parse("2026-04-07T08:44:37Z"), true)));
        when(animeEntryRepository.findByCrunchyrollId("GT00365571")).thenReturn(existingMapping);

        SyncService syncService = new SyncService();
        ReflectionTestUtils.setField(syncService, "crunchyrollService", crunchyrollService);
        ReflectionTestUtils.setField(syncService, "malService", malService);
        ReflectionTestUtils.setField(syncService, "animeEntryRepository", animeEntryRepository);
        ReflectionTestUtils.setField(syncService, "syncResultRepository", syncResultRepository);
        ReflectionTestUtils.setField(syncService, "crunchyrollMetadataService", crunchyrollMetadataService);
        ReflectionTestUtils.setField(syncService, "authTokenStore", authTokenStore);
        ReflectionTestUtils.setField(syncService, "conflictResolution", "last-write-wins");
        ReflectionTestUtils.setField(syncService, "watchHistoryReplayHours", 48L);

        syncService.performSync();

        verify(malService).updateAnimeProgress("mal-1", 13, "completed");
    }

    @Test
    void performSyncSkipsRegressingHistoryProgress() throws Exception {
        CrunchyrollService crunchyrollService = mock(CrunchyrollService.class);
        MalService malService = mock(MalService.class);
        AnimeEntryRepository animeEntryRepository = mock(AnimeEntryRepository.class);
        SyncResultRepository syncResultRepository = mock(SyncResultRepository.class);
        CrunchyrollMetadataService crunchyrollMetadataService = mock(CrunchyrollMetadataService.class);
        AuthTokenStore authTokenStore = mock(AuthTokenStore.class);

        AnimeEntry existingMapping = new AnimeEntry();
        existingMapping.setMalId("mal-1");
        existingMapping.setCrunchyrollId("GT00365571");
        existingMapping.setTitle("Journal with Witch");

        when(crunchyrollService.getWatchlist()).thenReturn(List.of());
        when(malService.getAnimeList()).thenReturn(List.of(
                Map.of(
                        "malId", "mal-1",
                        "title", "Journal with Witch",
                        "status", "watching",
                        "watchedEpisodes", 11,
                        "totalEpisodes", 13,
                        "titleCandidates", List.of("Journal with Witch"))));
        when(authTokenStore.loadValue("crunchyrollWatchHistoryCursor")).thenReturn(null);
        when(crunchyrollService.getWatchHistorySince(null)).thenReturn(List.of(
                new CrunchyrollWatchHistoryEntry("ep-10", "GT00365571", "Journal with Witch", 10, Instant.parse("2026-04-07T04:00:37Z"), true)));
        when(animeEntryRepository.findByCrunchyrollId("GT00365571")).thenReturn(existingMapping);

        SyncService syncService = new SyncService();
        ReflectionTestUtils.setField(syncService, "crunchyrollService", crunchyrollService);
        ReflectionTestUtils.setField(syncService, "malService", malService);
        ReflectionTestUtils.setField(syncService, "animeEntryRepository", animeEntryRepository);
        ReflectionTestUtils.setField(syncService, "syncResultRepository", syncResultRepository);
        ReflectionTestUtils.setField(syncService, "crunchyrollMetadataService", crunchyrollMetadataService);
        ReflectionTestUtils.setField(syncService, "authTokenStore", authTokenStore);
        ReflectionTestUtils.setField(syncService, "conflictResolution", "last-write-wins");
        ReflectionTestUtils.setField(syncService, "watchHistoryReplayHours", 48L);

        syncService.performSync();

        verify(malService, never()).updateAnimeProgress(anyString(), any(), anyString());
        verify(authTokenStore).saveValue("crunchyrollWatchHistoryCursor", "2026-04-07T04:00:37Z");
    }

    @Test
    void performSyncLogsFailureWhenUpstreamFetchFails() throws Exception {
        CrunchyrollService crunchyrollService = mock(CrunchyrollService.class);
        MalService malService = mock(MalService.class);
        AnimeEntryRepository animeEntryRepository = mock(AnimeEntryRepository.class);
        SyncResultRepository syncResultRepository = mock(SyncResultRepository.class);
        CrunchyrollMetadataService crunchyrollMetadataService = mock(CrunchyrollMetadataService.class);
        AuthTokenStore authTokenStore = mock(AuthTokenStore.class);

        when(crunchyrollService.getWatchlist()).thenThrow(new RuntimeException("boom"));

        SyncService syncService = new SyncService();
        ReflectionTestUtils.setField(syncService, "crunchyrollService", crunchyrollService);
        ReflectionTestUtils.setField(syncService, "malService", malService);
        ReflectionTestUtils.setField(syncService, "animeEntryRepository", animeEntryRepository);
        ReflectionTestUtils.setField(syncService, "syncResultRepository", syncResultRepository);
        ReflectionTestUtils.setField(syncService, "crunchyrollMetadataService", crunchyrollMetadataService);
        ReflectionTestUtils.setField(syncService, "authTokenStore", authTokenStore);
        ReflectionTestUtils.setField(syncService, "conflictResolution", "last-write-wins");
        ReflectionTestUtils.setField(syncService, "watchHistoryReplayHours", 48L);

        syncService.performSync();

        verify(malService, never()).updateAnimeStatus(anyString(), anyString());
        verify(syncResultRepository).save(any(SyncResult.class));
    }
}
