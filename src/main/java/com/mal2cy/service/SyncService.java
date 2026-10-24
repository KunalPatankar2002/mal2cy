package com.mal2cy.service;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mal2cy.model.AnimeEntry;
import com.mal2cy.model.CrunchyrollWatchHistoryEntry;
import com.mal2cy.model.SyncResult;
import com.mal2cy.repo.AnimeEntryRepository;
import com.mal2cy.repo.SyncResultRepository;

@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

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

    @Autowired
    private AuthTokenStore authTokenStore;

    @Value("${app.sync.conflict-resolution}")
    private String conflictResolution;

    @Value("${app.sync.watch-history-replay-hours:48}")
    private long watchHistoryReplayHours;

    private static final String WATCH_HISTORY_CURSOR_KEY = "crunchyrollWatchHistoryCursor";

    public void performSync() {
        try {
            mergeDuplicateEntries();

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
                    log.debug("Crunchyroll metadata fetch failed for content_id={}: {}", crId, e.getMessage());
                }
            }

            Set<String> crunchyrollWatchlistIds = crunchyrollList.stream()
                    .map(item -> (String) item.get("seriesId"))
                    .filter(id -> id != null && !id.isBlank())
                    .collect(Collectors.toSet());

            Set<String> malListIds = malList.stream()
                    .map(item -> (String) item.get("malId"))
                    .filter(id -> id != null && !id.isBlank())
                    .collect(Collectors.toSet());

            // Convert to unified format, keyed by MAL id when known so both sides merge into one entry
            Map<String, AnimeEntry> unifiedMap = new HashMap<>();

            // Process Crunchyroll
            for (Map<String, Object> item : crunchyrollList) {
                String crId = (String) item.get("seriesId");
                String title = (String) item.get("title");

                AnimeEntry entry = animeEntryRepository.findByCrunchyrollId(crId);
                String malId = entry != null ? entry.getMalId() : null;
                if (malId == null) {
                    try {
                        malId = malService.findMalIdByTitle(title);
                    } catch (Exception e) {
                        log.warn("MAL lookup failed for Crunchyroll watchlist title='{}' content_id={}: {}",
                                title, crId, e.getMessage());
                        logSyncResult("match", "mal", title, "failed", e.getMessage());
                        continue;
                    }
                }

                if (malId == null) {
                    log.info("No MAL match found for Crunchyroll watchlist title='{}' content_id={}", title, crId);
                    logSyncResult(
                            "match",
                            "mal",
                            title,
                            "missing",
                            "Present in Crunchyroll watchlist but no MAL match was found.");
                }

                if (entry == null && malId != null) {
                    entry = animeEntryRepository.findByMalId(malId);
                }
                if (entry == null) {
                    entry = new AnimeEntry();
                    entry.setTitle(title);
                    entry.setStatus("watching");
                    entry.setSource("crunchyroll");
                    entry.setLastUpdated(LocalDateTime.now());
                }
                entry.setCrunchyrollId(crId);
                if (entry.getMalId() == null) {
                    entry.setMalId(malId);
                }
                unifiedMap.put(unifiedKey(entry), entry);
            }

            // Process MAL
            for (Map<String, Object> item : malList) {
                String malId = (String) item.get("malId");
                String title = (String) item.get("title");
                String status = (String) item.get("status");
                List<String> titleCandidates = extractTitleCandidates(item, title);
                log.info("Processing MAL entry malId={} title='{}' with title candidates={}", malId, title, titleCandidates);

                AnimeEntry entry = unifiedMap.get(malId);
                if (entry == null) {
                    entry = animeEntryRepository.findByMalId(malId);
                }
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

                if (entry.getCrunchyrollId() == null) {
                    try {
                        entry.setCrunchyrollId(crunchyrollService.findSeriesIdByTitles(titleCandidates));
                    } catch (Exception e) {
                        log.warn("Crunchyroll search failed for MAL entry malId={} title='{}': {}",
                                malId, title, e.getMessage());
                        logSyncResult("match", "crunchyroll", title, "failed", e.getMessage());
                    }
                    if (entry.getCrunchyrollId() != null) {
                        log.info(
                                "Resolved Crunchyroll content_id={} for MAL entry malId={} title='{}'",
                                entry.getCrunchyrollId(),
                                malId,
                                title);
                    } else {
                        log.info("No Crunchyroll content_id resolved for MAL entry malId={} title='{}'", malId, title);
                        logSyncResult(
                                "match",
                                "crunchyroll",
                                title,
                                "missing",
                                "Present in MAL but no Crunchyroll match was found.");
                    }
                }

                unifiedMap.put(malId, entry);
            }

            // Sync to both platforms
            for (AnimeEntry entry : unifiedMap.values()) {
                animeEntryRepository.save(entry);
                syncToCrunchyroll(entry, crunchyrollWatchlistIds);
                syncToMal(entry, malListIds);
            }

            syncWatchHistoryToMal(malList);

            // Log success
            logSyncResult("sync", "both", "All", "success", null);
            log.info("Sync finished successfully");

        } catch (Exception e) {
            log.error("Sync failed: {}", e.getMessage(), e);
            logSyncResult("sync", "both", "All", "failed", e.getMessage());
        }
    }

    private String unifiedKey(AnimeEntry entry) {
        return entry.getMalId() != null ? entry.getMalId() : "cr:" + entry.getCrunchyrollId();
    }

    /**
     * Collapses rows that describe the same show (same MAL id or same Crunchyroll id,
     * with no conflicting other id) into the oldest row. Earlier versions could insert
     * the same show twice, which made every later sync fail on a non-unique lookup.
     */
    void mergeDuplicateEntries() {
        List<AnimeEntry> entries = new ArrayList<>(animeEntryRepository.findAll());
        entries.sort(Comparator.comparing(AnimeEntry::getId, Comparator.nullsLast(Comparator.naturalOrder())));

        Map<String, AnimeEntry> keepersByMalId = new HashMap<>();
        Map<String, AnimeEntry> keepersByCrunchyrollId = new HashMap<>();
        for (AnimeEntry entry : entries) {
            AnimeEntry keeper = null;
            if (entry.getMalId() != null) {
                keeper = keepersByMalId.get(entry.getMalId());
            }
            if (keeper == null && entry.getCrunchyrollId() != null) {
                keeper = keepersByCrunchyrollId.get(entry.getCrunchyrollId());
            }

            if (keeper == null || !sameShow(keeper, entry)) {
                registerKeeper(entry, keepersByMalId, keepersByCrunchyrollId);
                continue;
            }

            if (keeper.getMalId() == null) {
                keeper.setMalId(entry.getMalId());
            }
            if (keeper.getCrunchyrollId() == null) {
                keeper.setCrunchyrollId(entry.getCrunchyrollId());
            }
            log.info("Merging duplicate anime entry id={} title='{}' into id={} (malId={} content_id={})",
                    entry.getId(), entry.getTitle(), keeper.getId(), keeper.getMalId(), keeper.getCrunchyrollId());
            animeEntryRepository.delete(entry);
            animeEntryRepository.save(keeper);
            registerKeeper(keeper, keepersByMalId, keepersByCrunchyrollId);
        }
    }

    private boolean sameShow(AnimeEntry keeper, AnimeEntry entry) {
        boolean malCompatible = keeper.getMalId() == null || entry.getMalId() == null
                || keeper.getMalId().equals(entry.getMalId());
        boolean crCompatible = keeper.getCrunchyrollId() == null || entry.getCrunchyrollId() == null
                || keeper.getCrunchyrollId().equals(entry.getCrunchyrollId());
        return malCompatible && crCompatible;
    }

    private void registerKeeper(AnimeEntry keeper, Map<String, AnimeEntry> byMalId, Map<String, AnimeEntry> byCrunchyrollId) {
        if (keeper.getMalId() != null) {
            byMalId.putIfAbsent(keeper.getMalId(), keeper);
        }
        if (keeper.getCrunchyrollId() != null) {
            byCrunchyrollId.putIfAbsent(keeper.getCrunchyrollId(), keeper);
        }
    }

    private boolean resolveConflict(AnimeEntry existing, String newStatus, LocalDateTime newTime) {
        if ("last-write-wins".equals(conflictResolution)) {
            return newTime.isAfter(existing.getLastUpdated());
        }
        // Add more strategies as needed
        return false;
    }

    private void syncToCrunchyroll(AnimeEntry entry, Set<String> crunchyrollWatchlistIds) {
        try {
            if (!"mal".equals(entry.getSource())) {
                return;
            }

            if (entry.getCrunchyrollId() == null) {
                logSyncResult(
                        "add",
                        "crunchyroll",
                        entry.getTitle(),
                        "skipped",
                        "Skipped Crunchyroll add because no Crunchyroll content ID is available for this entry.");
                log.info("Skipping Crunchyroll add for title='{}' because no content_id was resolved", entry.getTitle());
                return;
            }

            if (crunchyrollWatchlistIds.contains(entry.getCrunchyrollId())) {
                logSyncResult(
                        "add",
                        "crunchyroll",
                        entry.getTitle(),
                        "skipped",
                        "Skipped Crunchyroll add because the title is already in the Crunchyroll watchlist.");
                log.info(
                        "Skipping Crunchyroll add for title='{}' because content_id={} is already in the watchlist",
                        entry.getTitle(),
                        entry.getCrunchyrollId());
                return;
            }

            log.info("Adding title='{}' to Crunchyroll watchlist with content_id={}", entry.getTitle(), entry.getCrunchyrollId());
            crunchyrollService.addToWatchlist(entry.getCrunchyrollId());
            crunchyrollWatchlistIds.add(entry.getCrunchyrollId());
            logSyncResult("add", "crunchyroll", entry.getTitle(), "success", null);
            log.info("Added title='{}' to Crunchyroll watchlist with content_id={}", entry.getTitle(), entry.getCrunchyrollId());
        } catch (Exception e) {
            log.warn(
                    "Crunchyroll add failed for title='{}' content_id={}: {}",
                    entry.getTitle(),
                    entry.getCrunchyrollId(),
                    e.getMessage());
            logSyncResult("add", "crunchyroll", entry.getTitle(), "failed", e.getMessage());
        }
    }

    private void syncToMal(AnimeEntry entry, Set<String> malListIds) {
        try {
            // Entries already on the MAL list carry MAL's own status; pushing it back is at best a
            // no-op and at worst overwrites e.g. "completed" with Crunchyroll's default "watching".
            if (entry.getMalId() != null && !malListIds.contains(entry.getMalId())) {
                malService.updateAnimeStatus(entry.getMalId(), entry.getStatus());
                logSyncResult("update", "mal", entry.getTitle(), "success", null);
            }
        } catch (Exception e) {
            log.warn("MAL status update failed for malId={} title='{}': {}", entry.getMalId(), entry.getTitle(), e.getMessage());
            logSyncResult("update", "mal", entry.getTitle(), "failed", e.getMessage());
        }
    }

    /**
     * Advances MAL episode progress from Crunchyroll watch history using only fully
     * watched entries newer than the replay cutoff.
     */
    private void syncWatchHistoryToMal(List<Map<String, Object>> malList) throws Exception {
        Instant cutoff = getWatchHistoryReplayCutoff();
        List<CrunchyrollWatchHistoryEntry> watchHistoryEntries = crunchyrollService.getWatchHistorySince(cutoff);
        if (watchHistoryEntries.isEmpty()) {
            log.info("No Crunchyroll watch history entries were eligible for MAL progress sync");
            return;
        }

        Map<String, MalProgressState> malProgressById = buildMalProgressStateMap(malList);
        Map<String, WatchHistoryCandidate> candidatesBySeriesId = new HashMap<>();
        Instant maxProcessedDate = null;

        for (CrunchyrollWatchHistoryEntry entry : watchHistoryEntries) {
            if (maxProcessedDate == null || entry.datePlayed().isAfter(maxProcessedDate)) {
                maxProcessedDate = entry.datePlayed();
            }
            candidatesBySeriesId.merge(
                    entry.seriesId(),
                    new WatchHistoryCandidate(entry.seriesId(), entry.seriesTitle(), entry.episodeNumber(), entry.datePlayed()),
                    this::pickBetterHistoryCandidate);
        }

        for (WatchHistoryCandidate candidate : candidatesBySeriesId.values()) {
            logSyncResult(
                    "history",
                    "crunchyroll",
                    candidate.seriesTitle() + " E" + candidate.episodeNumber(),
                    "accepted",
                    null);
            try {
                processWatchHistoryCandidate(candidate, malProgressById);
            } catch (Exception e) {
                log.warn("MAL progress sync failed for Crunchyroll series_id={} title='{}': {}",
                        candidate.seriesId(), candidate.seriesTitle(), e.getMessage());
                logSyncResult("progress_update", "mal", candidate.seriesTitle(), "failed", e.getMessage());
            }
        }

        if (maxProcessedDate != null) {
            authTokenStore.saveValue(WATCH_HISTORY_CURSOR_KEY, maxProcessedDate.toString());
            log.info("Advanced Crunchyroll watch history cursor to {}", maxProcessedDate);
        }
    }

    private void processWatchHistoryCandidate(WatchHistoryCandidate candidate, Map<String, MalProgressState> malProgressById)
            throws Exception {
        AnimeEntry mappingEntry = animeEntryRepository.findByCrunchyrollId(candidate.seriesId());
        String malId = mappingEntry != null ? mappingEntry.getMalId() : null;

        if (malId == null) {
            malId = malService.findMalIdByTitle(candidate.seriesTitle());
            if (malId != null) {
                log.info("Resolved MAL id={} for Crunchyroll watch history series_id={} title='{}'",
                        malId, candidate.seriesId(), candidate.seriesTitle());
            }
        }

        if (malId == null) {
            log.info("No MAL mapping found for Crunchyroll watch history series_id={} title='{}'",
                    candidate.seriesId(), candidate.seriesTitle());
            logSyncResult(
                    "match",
                    "mal",
                    candidate.seriesTitle(),
                    "missing",
                    "Present in Crunchyroll watch history but no MAL match was found.");
            return;
        }

        MalProgressState currentState = malProgressById.getOrDefault(
                malId,
                new MalProgressState(malId, candidate.seriesTitle(), 0, 0, "watching"));

        if (candidate.episodeNumber() <= currentState.watchedEpisodes()) {
            log.info(
                    "Skipping MAL progress update for malId={} title='{}' because candidate episode {} does not advance current watched count {}",
                    malId, candidate.seriesTitle(), candidate.episodeNumber(), currentState.watchedEpisodes());
            logSyncResult(
                    "progress_update",
                    "mal",
                    candidate.seriesTitle(),
                    "skipped",
                    "Skipped MAL progress update because Crunchyroll history did not advance watched episode count.");
            return;
        }

        String targetStatus = determineStatus(candidate.episodeNumber(), currentState.totalEpisodes());
        log.info(
                "Updating MAL progress for malId={} title='{}' to watchedEpisodes={} status={}",
                malId, candidate.seriesTitle(), candidate.episodeNumber(), targetStatus);
        malService.updateAnimeProgress(malId, candidate.episodeNumber(), targetStatus);
        logSyncResult("progress_update", "mal", candidate.seriesTitle(), "success", null);

        malProgressById.put(
                malId,
                new MalProgressState(malId, candidate.seriesTitle(), candidate.episodeNumber(), currentState.totalEpisodes(), targetStatus));
        persistCrunchyrollMalMapping(mappingEntry, malId, candidate, targetStatus);
    }

    private void persistCrunchyrollMalMapping(AnimeEntry mappingEntry, String malId, WatchHistoryCandidate candidate, String status) {
        AnimeEntry entryToSave = mappingEntry;
        if (entryToSave == null) {
            entryToSave = animeEntryRepository.findByMalId(malId);
        }
        if (entryToSave == null) {
            entryToSave = new AnimeEntry();
            entryToSave.setSource("crunchyroll");
        }

        entryToSave.setTitle(candidate.seriesTitle());
        entryToSave.setMalId(malId);
        entryToSave.setCrunchyrollId(candidate.seriesId());
        entryToSave.setStatus(status);
        entryToSave.setLastUpdated(LocalDateTime.now());
        animeEntryRepository.save(entryToSave);
    }

    private Map<String, MalProgressState> buildMalProgressStateMap(List<Map<String, Object>> malList) {
        Map<String, MalProgressState> progressMap = new HashMap<>();
        for (Map<String, Object> item : malList) {
            String malId = (String) item.get("malId");
            if (malId == null || malId.isBlank()) {
                continue;
            }

            progressMap.put(
                    malId,
                    new MalProgressState(
                            malId,
                            (String) item.get("title"),
                            readIntValue(item.get("watchedEpisodes")),
                            readIntValue(item.get("totalEpisodes")),
                            (String) item.get("status")));
        }
        return progressMap;
    }

    private WatchHistoryCandidate pickBetterHistoryCandidate(WatchHistoryCandidate left, WatchHistoryCandidate right) {
        if (right.episodeNumber() > left.episodeNumber()) {
            return right;
        }
        if (right.episodeNumber() < left.episodeNumber()) {
            return left;
        }
        return right.datePlayed().isAfter(left.datePlayed()) ? right : left;
    }

    private String determineStatus(int watchedEpisodes, int totalEpisodes) {
        if (totalEpisodes > 0 && watchedEpisodes >= totalEpisodes) {
            return "completed";
        }
        return "watching";
    }

    private Instant getWatchHistoryReplayCutoff() {
        String cursorValue = authTokenStore.loadValue(WATCH_HISTORY_CURSOR_KEY);
        if (cursorValue == null || cursorValue.isBlank()) {
            log.info("No existing Crunchyroll watch history cursor found; reading the first available history page");
            return null;
        }

        try {
            Instant cursor = Instant.parse(cursorValue);
            Instant replayCutoff = cursor.minus(watchHistoryReplayHours, ChronoUnit.HOURS);
            log.info("Using Crunchyroll watch history replay cutoff={} from cursor={} and replay window={}h",
                    replayCutoff, cursor, watchHistoryReplayHours);
            return replayCutoff;
        } catch (Exception e) {
            log.warn("Failed to parse Crunchyroll watch history cursor '{}': {}", cursorValue, e.getMessage());
            return null;
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

    @SuppressWarnings("unchecked")
    private List<String> extractTitleCandidates(Map<String, Object> item, String fallbackTitle) {
        Object titleCandidates = item.get("titleCandidates");
        if (titleCandidates instanceof List<?>) {
            List<String> candidates = new ArrayList<>();
            for (Object candidate : (List<Object>) titleCandidates) {
                if (candidate instanceof String value && !value.isBlank()) {
                    candidates.add(value);
                }
            }
            if (!candidates.isEmpty()) {
                return candidates;
            }
        }

        return List.of(fallbackTitle);
    }

    private int readIntValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private record WatchHistoryCandidate(String seriesId, String seriesTitle, int episodeNumber, Instant datePlayed) {
    }

    private record MalProgressState(String malId, String title, int watchedEpisodes, int totalEpisodes, String status) {
    }
}
