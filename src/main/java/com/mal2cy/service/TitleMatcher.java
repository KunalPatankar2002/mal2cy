package com.mal2cy.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Title helpers shared by the MAL and Crunchyroll matchers.
 */
public final class TitleMatcher {

    // Trailing season / part / sequel markers MAL puts on sequel entries, e.g.
    // "2nd Season", "Season 3", "Part 2", "II", "The Final Season - Kanketsu-hen".
    private static final Pattern SEASON_SUFFIX = Pattern.compile(
            "(?i)[\\s:\\-–]+(?:"
                    + "\\d+(?:st|nd|rd|th)\\s+season"
                    + "|season\\s+\\d+"
                    + "|(?:the\\s+)?final\\s+season"
                    + "|part\\s+\\d+"
                    + "|(?:ii|iii|iv)"
                    + "|\\d+(?:st|nd|rd|th)\\s+attack"
                    + ")(?:\\b.*)?$");

    // Japanese story-arc subtitles MAL uses for later seasons, e.g.
    // "Kimetsu no Yaiba: Katanakaji no Sato-hen". Crunchyroll keeps arcs under the main series.
    private static final Pattern ARC_SUFFIX = Pattern.compile("(?i)\\s*:\\s*[^:]+-hen\\s*$");

    private TitleMatcher() {
    }

    public static String normalize(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }

        String normalized = Normalizer.normalize(title, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .trim();
        return normalized.replaceAll("\\s+", " ");
    }

    /**
     * Returns the title without a trailing season/sequel marker, or null when the
     * title has no such marker (or nothing would be left).
     */
    public static String stripSeasonSuffix(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String stripped = SEASON_SUFFIX.matcher(title.trim()).replaceFirst("").trim();
        stripped = ARC_SUFFIX.matcher(stripped).replaceFirst("").trim();
        if (stripped.isEmpty() || stripped.equalsIgnoreCase(title.trim())) {
            return null;
        }
        return stripped;
    }
}
