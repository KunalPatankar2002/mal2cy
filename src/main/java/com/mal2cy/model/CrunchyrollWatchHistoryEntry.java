package com.mal2cy.model;

import java.time.Instant;

/**
 * Represents a fully parsed Crunchyroll watch-history item that is eligible for
 * MAL progress sync decisions.
 */
public record CrunchyrollWatchHistoryEntry(
        String episodeId,
        String seriesId,
        String seriesTitle,
        int episodeNumber,
        Instant datePlayed,
        boolean fullyWatched) {
}
