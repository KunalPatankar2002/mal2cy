package com.mal2cy.controller;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mal2cy.model.AnimeEntry;
import com.mal2cy.model.SyncResult;
import com.mal2cy.repo.AnimeEntryRepository;
import com.mal2cy.repo.SyncResultRepository;
import com.mal2cy.service.MatchStore;
import com.mal2cy.service.SyncService;

@RestController
@RequestMapping("/sync")
public class SyncController {

    private final SyncService syncService;
    private final AnimeEntryRepository animeEntryRepository;
    private final SyncResultRepository syncResultRepository;
    private final MatchStore matchStore;

    public SyncController(SyncService syncService, AnimeEntryRepository animeEntryRepository,
            SyncResultRepository syncResultRepository, MatchStore matchStore) {
        this.syncService = syncService;
        this.animeEntryRepository = animeEntryRepository;
        this.syncResultRepository = syncResultRepository;
        this.matchStore = matchStore;
    }

    /** Runs a sync now instead of waiting for the nightly schedule. */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run() {
        syncService.performSync();
        return ResponseEntity.ok(describe(syncResultRepository.findFirstByOperationOrderByIdDesc("sync")));
    }

    /** Shows the last sync and which titles are matched between MAL and Crunchyroll. */
    @GetMapping("/report")
    public ResponseEntity<Map<String, Object>> report() {
        List<AnimeEntry> entries = animeEntryRepository.findAll().stream()
                .sorted(Comparator.comparing(e -> String.valueOf(e.getTitle()), String.CASE_INSENSITIVE_ORDER))
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lastSync", describe(syncResultRepository.findFirstByOperationOrderByIdDesc("sync")));
        body.put("matchedCount", entries.stream().filter(e -> e.getMalId() != null && e.getCrunchyrollId() != null).count());
        body.put("malWithoutCrunchyroll", entries.stream()
                .filter(e -> e.getMalId() != null && e.getCrunchyrollId() == null)
                .map(AnimeEntry::getTitle).toList());
        body.put("crunchyrollWithoutMal", entries.stream()
                .filter(e -> e.getCrunchyrollId() != null && e.getMalId() == null)
                .map(AnimeEntry::getTitle).toList());
        body.put("matched", entries.stream()
                .filter(e -> e.getMalId() != null && e.getCrunchyrollId() != null)
                .map(e -> e.getTitle() + " (MAL " + e.getMalId() + " / CR " + e.getCrunchyrollId() + ")")
                .toList());
        body.put("recentMissCount", matchStore.misses().size());
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> describe(SyncResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (result == null) {
            body.put("status", "never_run");
            return body;
        }
        body.put("status", result.getStatus());
        body.put("time", String.valueOf(result.getSyncTime()));
        if (result.getErrorMessage() != null) {
            body.put("error", result.getErrorMessage());
        }
        return body;
    }
}
