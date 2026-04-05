package com.mal2cy.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.mal2cy.model.AnimeEntry;
import com.mal2cy.model.SyncResult;
import com.mal2cy.repo.AnimeEntryRepository;
import com.mal2cy.repo.SyncResultRepository;

@Service
public class SyncService {

    @Autowired
    private CrunchyrollService crunchyrollService;

    @Autowired
    private MalService malService;

    @Autowired
    private AnimeEntryRepository animeEntryRepository;

    @Autowired
    private SyncResultRepository syncResultRepository;

    @Autowired
    private CrunchyrollMetadataService crunchyrollMetadataService;

    @Value("${app.sync.conflict-resolution}")
    private String conflictResolution;

    public void performSync() {
        try {
            // Fetch data from both platforms
            List<Map<String, Object>> crunchyrollList = crunchyrollService.getWatchlist();
            List<Map<String, Object>> malList = malService.getAnimeList();

            // Enrich Crunchyroll entries with metadata
            for (Map<String, Object> item : crunchyrollList) {
                String crId = (String) item.get("seriesId");
                String accessToken = crunchyrollService.getAccessToken();
                try {
                    var metadata = crunchyrollMetadataService.getSeriesMetadata(accessToken, crId);
                    item.put("metadata", metadata);
                } catch (Exception e) {
                    // Log or handle metadata fetch failure
                }
            }

            // Convert to unified format
            Map<String, AnimeEntry> unifiedMap = new HashMap<>();

            // Process Crunchyroll
            for (Map<String, Object> item : crunchyrollList) {
                String crId = (String) item.get("seriesId");
                String title = (String) item.get("title");
                String malId = malService.findMalIdByTitle(title);

                AnimeEntry entry = new AnimeEntry();
                entry.setTitle(title);
                entry.setCrunchyrollId(crId);
                entry.setMalId(malId);
                entry.setStatus("watching");
                entry.setSource("crunchyroll");
                entry.setLastUpdated(LocalDateTime.now());
                unifiedMap.put(crId, entry);
            }

            // Process MAL
            for (Map<String, Object> item : malList) {
                String malId = (String) item.get("malId");
                String title = (String) item.get("title");
                String status = (String) item.get("status");

                AnimeEntry entry = animeEntryRepository.findByMalId(malId);
                if (entry == null) {
                    entry = new AnimeEntry();
                    entry.setTitle(title);
                    entry.setMalId(malId);
                    entry.setStatus(status);
                    entry.setSource("mal");
                    entry.setLastUpdated(LocalDateTime.now());
                } else {
                    // Conflict resolution
                    if (resolveConflict(entry, status, LocalDateTime.now())) {
                        entry.setStatus(status);
                        entry.setLastUpdated(LocalDateTime.now());
                    }
                }
                unifiedMap.put(malId, entry);
            }

            // Sync to both platforms
            for (AnimeEntry entry : unifiedMap.values()) {
                syncToCrunchyroll(entry);
                syncToMal(entry);
            }

            // Log success
            logSyncResult("sync", "both", "All", "success", null);

        } catch (Exception e) {
            logSyncResult("sync", "both", "All", "failed", e.getMessage());
        }
    }

    private boolean resolveConflict(AnimeEntry existing, String newStatus, LocalDateTime newTime) {
        if ("last-write-wins".equals(conflictResolution)) {
            return newTime.isAfter(existing.getLastUpdated());
        }
        // Add more strategies as needed
        return false;
    }

    private void syncToCrunchyroll(AnimeEntry entry) {
        try {
            if (entry.getCrunchyrollId() == null) {
                logSyncResult(
                        "add",
                        "crunchyroll",
                        entry.getTitle(),
                        "skipped",
                        "Skipped Crunchyroll add because no Crunchyroll content ID is available for this entry.");
                return;
            }
        } catch (Exception e) {
            logSyncResult("add", "crunchyroll", entry.getTitle(), "failed", e.getMessage());
        }
    }

    private void syncToMal(AnimeEntry entry) {
        try {
            if (entry.getMalId() != null) {
                malService.updateAnimeStatus(entry.getMalId(), entry.getStatus());
                logSyncResult("update", "mal", entry.getTitle(), "success", null);
            }
        } catch (Exception e) {
            logSyncResult("update", "mal", entry.getTitle(), "failed", e.getMessage());
        }
    }

    private void logSyncResult(String operation, String platform, String title, String status, String error) {
        SyncResult result = new SyncResult();
        result.setSyncTime(LocalDateTime.now());
        result.setOperation(operation);
        result.setPlatform(platform);
        result.setAnimeTitle(title);
        result.setStatus(status);
        result.setErrorMessage(error);
        syncResultRepository.save(result);
    }
}
