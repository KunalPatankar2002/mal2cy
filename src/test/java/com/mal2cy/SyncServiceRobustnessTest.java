package com.mal2cy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.mal2cy.model.AnimeEntry;
import com.mal2cy.model.SyncResult;
import com.mal2cy.repo.AnimeEntryRepository;
import com.mal2cy.repo.SyncResultRepository;
import com.mal2cy.service.AuthTokenStore;
import com.mal2cy.service.CrunchyrollMetadataService;
import com.mal2cy.service.CrunchyrollService;
import com.mal2cy.service.MalService;
import com.mal2cy.service.SyncService;

class SyncServiceRobustnessTest {

    private final CrunchyrollService crunchyrollService = mock(CrunchyrollService.class);
    private final MalService malService = mock(MalService.class);
    private final AnimeEntryRepository animeEntryRepository = mock(AnimeEntryRepository.class);
    private final SyncResultRepository syncResultRepository = mock(SyncResultRepository.class);
    private final AuthTokenStore authTokenStore = mock(AuthTokenStore.class);

    @Test
    void performSyncMergesDuplicateRowsBeforeSyncing() throws Exception {
        AnimeEntry original = entry(1L, "Sousou no Frieren", "52991", "GG5H5XQX4", "mal");
        AnimeEntry duplicate = entry(2L, "Frieren: Beyond Journey's End", "52991", "GG5H5XQX4", "crunchyroll");
        AnimeEntry unrelated = entry(3L, "One Piece", "21", "GRMG8ZQZR", "mal");
        when(animeEntryRepository.findAll()).thenReturn(List.of(duplicate, unrelated, original));
        when(crunchyrollService.getWatchlist()).thenReturn(List.of());
        when(malService.getAnimeList()).thenReturn(List.of());

        newSyncService().performSync();

        verify(animeEntryRepository).delete(duplicate);
        verify(animeEntryRepository, never()).delete(original);
        verify(animeEntryRepository, never()).delete(unrelated);
        verify(syncResultRepository).save(argThat((SyncResult result) ->
                "sync".equals(result.getOperation()) && "success".equals(result.getStatus())));
    }

    @Test
    void mergeKeepsRowsWithConflictingIds() throws Exception {
        AnimeEntry season1 = entry(1L, "Season 1", "100", "GX1", "mal");
        AnimeEntry season2 = entry(2L, "Season 2", "200", "GX1", "mal");
        when(animeEntryRepository.findAll()).thenReturn(List.of(season1, season2));
        when(crunchyrollService.getWatchlist()).thenReturn(List.of());
        when(malService.getAnimeList()).thenReturn(List.of());

        newSyncService().performSync();

        verify(animeEntryRepository, never()).delete(any(AnimeEntry.class));
    }

    @Test
    void crunchyrollWatchlistMatchMergesIntoExistingMalEntryWithoutOverwritingStatus() throws Exception {
        when(crunchyrollService.getWatchlist()).thenReturn(List.of(
                new java.util.HashMap<>(Map.of("seriesId", "GG5H5XQX4", "title", "Frieren: Beyond Journey's End"))));
        when(malService.getAnimeList()).thenReturn(List.of(Map.of(
                "malId", "52991",
                "title", "Sousou no Frieren",
                "status", "completed",
                "watchedEpisodes", 28,
                "totalEpisodes", 28,
                "titleCandidates", List.of("Frieren: Beyond Journey's End", "Sousou no Frieren"))));
        when(malService.findMalIdByTitle("Frieren: Beyond Journey's End")).thenReturn("52991");

        newSyncService().performSync();

        // Both sides describe one show, so only one row is saved and MAL's "completed" is never touched.
        verify(animeEntryRepository).save(argThat((AnimeEntry saved) ->
                "52991".equals(saved.getMalId()) && "GG5H5XQX4".equals(saved.getCrunchyrollId())));
        verify(animeEntryRepository, org.mockito.Mockito.times(1)).save(any(AnimeEntry.class));
        verify(malService, never()).updateAnimeStatus(anyString(), anyString());
        verify(crunchyrollService, never()).findSeriesIdByTitles(any());
    }

    @Test
    void oneFailedSearchDoesNotAbortTheSync() throws Exception {
        when(crunchyrollService.getWatchlist()).thenReturn(List.of());
        when(malService.getAnimeList()).thenReturn(List.of(
                Map.of("malId", "1", "title", "Broken", "status", "watching", "titleCandidates", List.of("Broken")),
                Map.of("malId", "2", "title", "Works", "status", "plan_to_watch", "titleCandidates", List.of("Works"))));
        when(crunchyrollService.findSeriesIdByTitles(List.of("Broken")))
                .thenThrow(new IOException("Crunchyroll search failed: 503"));
        when(crunchyrollService.findSeriesIdByTitles(List.of("Works"))).thenReturn("GWORKS");
        when(crunchyrollService.getWatchHistorySince(null)).thenReturn(List.of());

        newSyncService().performSync();

        verify(crunchyrollService).addToWatchlist("GWORKS");
        verify(syncResultRepository).save(argThat((SyncResult result) ->
                "sync".equals(result.getOperation()) && "success".equals(result.getStatus())));
        verify(syncResultRepository).save(argThat((SyncResult result) ->
                "match".equals(result.getOperation()) && "failed".equals(result.getStatus())
                        && "Broken".equals(result.getAnimeTitle())));
    }

    @Test
    void failedMalLookupForWatchlistTitleIsSkipped() throws Exception {
        when(crunchyrollService.getWatchlist()).thenReturn(List.of(
                new java.util.HashMap<>(Map.of("seriesId", "GX", "title", "Timeout Show"))));
        when(malService.getAnimeList()).thenReturn(List.of());
        when(malService.findMalIdByTitle("Timeout Show")).thenThrow(new IOException("timeout"));
        when(crunchyrollService.getWatchHistorySince(null)).thenReturn(List.of());

        newSyncService().performSync();

        verify(animeEntryRepository, never()).save(any(AnimeEntry.class));
        verify(syncResultRepository).save(argThat((SyncResult result) ->
                "sync".equals(result.getOperation()) && "success".equals(result.getStatus())));
    }

    @Test
    void crunchyrollOnlyMatchIsStillAddedToMal() throws Exception {
        when(crunchyrollService.getWatchlist()).thenReturn(List.of(
                new java.util.HashMap<>(Map.of("seriesId", "GNEW", "title", "New Show"))));
        when(malService.getAnimeList()).thenReturn(List.of());
        when(malService.findMalIdByTitle("New Show")).thenReturn("999");
        when(crunchyrollService.getWatchHistorySince(null)).thenReturn(List.of());

        newSyncService().performSync();

        verify(malService).updateAnimeStatus(eq("999"), eq("watching"));
        verify(crunchyrollService, never()).addToWatchlist(anyString());
    }

    private SyncService newSyncService() {
        SyncService syncService = new SyncService();
        ReflectionTestUtils.setField(syncService, "crunchyrollService", crunchyrollService);
        ReflectionTestUtils.setField(syncService, "malService", malService);
        ReflectionTestUtils.setField(syncService, "animeEntryRepository", animeEntryRepository);
        ReflectionTestUtils.setField(syncService, "syncResultRepository", syncResultRepository);
        ReflectionTestUtils.setField(syncService, "crunchyrollMetadataService", mock(CrunchyrollMetadataService.class));
        ReflectionTestUtils.setField(syncService, "authTokenStore", authTokenStore);
        ReflectionTestUtils.setField(syncService, "matchStore", mock(com.mal2cy.service.MatchStore.class));
        ReflectionTestUtils.setField(syncService, "notificationService", mock(com.mal2cy.service.NotificationService.class));
        ReflectionTestUtils.setField(syncService, "conflictResolution", "last-write-wins");
        ReflectionTestUtils.setField(syncService, "watchHistoryReplayHours", 48L);
        return syncService;
    }

    private static AnimeEntry entry(Long id, String title, String malId, String crunchyrollId, String source) {
        AnimeEntry entry = new AnimeEntry();
        entry.setId(id);
        entry.setTitle(title);
        entry.setMalId(malId);
        entry.setCrunchyrollId(crunchyrollId);
        entry.setSource(source);
        entry.setStatus("watching");
        entry.setLastUpdated(java.time.LocalDateTime.now().minusDays(1));
        return entry;
    }
}
