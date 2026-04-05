package com.mal2cy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mal2cy.model.SyncResult;
import com.mal2cy.repo.AnimeEntryRepository;
import com.mal2cy.repo.SyncResultRepository;
import com.mal2cy.service.CrunchyrollMetadataService;
import com.mal2cy.service.CrunchyrollService;
import com.mal2cy.service.MalService;
import com.mal2cy.service.SyncService;

class SyncServiceTest {

    @Test
    void performSyncSkipsUnsafeCrunchyrollWritesAndUpdatesMal() throws Exception {
        CrunchyrollService crunchyrollService = mock(CrunchyrollService.class);
        MalService malService = mock(MalService.class);
        AnimeEntryRepository animeEntryRepository = mock(AnimeEntryRepository.class);
        SyncResultRepository syncResultRepository = mock(SyncResultRepository.class);
        CrunchyrollMetadataService crunchyrollMetadataService = mock(CrunchyrollMetadataService.class);

        when(crunchyrollService.getWatchlist()).thenReturn(List.of(
                Map.of("seriesId", "cr-1", "title", "Frieren")));
        when(crunchyrollService.getAccessToken()).thenReturn("secret-token");
        when(crunchyrollMetadataService.getSeriesMetadata("secret-token", "cr-1"))
                .thenReturn(new ObjectMapper().readTree("{\"id\":\"cr-1\"}"));
        when(malService.getAnimeList()).thenReturn(List.of(
                Map.of("malId", "mal-2", "title", "Dandadan", "status", "watching")));
        when(malService.findMalIdByTitle("Frieren")).thenReturn("mal-1");
        when(animeEntryRepository.findByMalId("mal-2")).thenReturn(null);

        SyncService syncService = new SyncService();
        ReflectionTestUtils.setField(syncService, "crunchyrollService", crunchyrollService);
        ReflectionTestUtils.setField(syncService, "malService", malService);
        ReflectionTestUtils.setField(syncService, "animeEntryRepository", animeEntryRepository);
        ReflectionTestUtils.setField(syncService, "syncResultRepository", syncResultRepository);
        ReflectionTestUtils.setField(syncService, "crunchyrollMetadataService", crunchyrollMetadataService);
        ReflectionTestUtils.setField(syncService, "conflictResolution", "last-write-wins");

        syncService.performSync();

        verify(malService).updateAnimeStatus("mal-1", "watching");
        verify(malService).updateAnimeStatus("mal-2", "watching");
        verify(crunchyrollService, never()).addToWatchlist("mal-2");
        verify(syncResultRepository, times(4)).save(any(SyncResult.class));
    }

    @Test
    void performSyncLogsFailureWhenUpstreamFetchFails() throws Exception {
        CrunchyrollService crunchyrollService = mock(CrunchyrollService.class);
        MalService malService = mock(MalService.class);
        AnimeEntryRepository animeEntryRepository = mock(AnimeEntryRepository.class);
        SyncResultRepository syncResultRepository = mock(SyncResultRepository.class);
        CrunchyrollMetadataService crunchyrollMetadataService = mock(CrunchyrollMetadataService.class);

        when(crunchyrollService.getWatchlist()).thenThrow(new RuntimeException("boom"));

        SyncService syncService = new SyncService();
        ReflectionTestUtils.setField(syncService, "crunchyrollService", crunchyrollService);
        ReflectionTestUtils.setField(syncService, "malService", malService);
        ReflectionTestUtils.setField(syncService, "animeEntryRepository", animeEntryRepository);
        ReflectionTestUtils.setField(syncService, "syncResultRepository", syncResultRepository);
        ReflectionTestUtils.setField(syncService, "crunchyrollMetadataService", crunchyrollMetadataService);
        ReflectionTestUtils.setField(syncService, "conflictResolution", "last-write-wins");

        syncService.performSync();

        verify(malService, never()).updateAnimeStatus(anyString(), anyString());
        verify(syncResultRepository).save(any(SyncResult.class));
    }
}
