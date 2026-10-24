package com.mal2cy.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Remembers titles that had no match recently, so they are not searched again every
 * night, and reads hand-written id overrides from {@code data/title-overrides.json}:
 *
 * <pre>
 * {
 *   "malToCrunchyroll": { "41025": "GG5H5XMWV" },
 *   "crunchyrollToMal": { "GT00371893": "38668" }
 * }
 * </pre>
 */
@Component
public class MatchStore {

    private static final Logger log = LoggerFactory.getLogger(MatchStore.class);
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Map<String, String>>> OVERRIDES = new TypeReference<>() {
    };

    private final Path missesPath;
    private final Path overridesPath;
    private final long retryDays;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MatchStore(
            @Value("${app.match.misses-path:./data/match-misses.json}") String missesPath,
            @Value("${app.match.overrides-path:./data/title-overrides.json}") String overridesPath,
            @Value("${app.match.retry-days:30}") long retryDays) {
        this.missesPath = Path.of(missesPath);
        this.overridesPath = Path.of(overridesPath);
        this.retryDays = retryDays;
    }

    /** True when this key had no match within the retry window. */
    public synchronized boolean recentlyMissed(String key) {
        String missedAt = loadMisses().get(key);
        if (missedAt == null) {
            return false;
        }
        try {
            return Instant.parse(missedAt).isAfter(Instant.now().minus(retryDays, ChronoUnit.DAYS));
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized void recordMiss(String key) {
        Map<String, String> misses = loadMisses();
        misses.put(key, Instant.now().toString());
        write(missesPath, misses);
    }

    public synchronized void clearMiss(String key) {
        Map<String, String> misses = loadMisses();
        if (misses.remove(key) != null) {
            write(missesPath, misses);
        }
    }

    public synchronized Map<String, String> misses() {
        return loadMisses();
    }

    public String crunchyrollIdOverride(String malId) {
        return loadOverrides().getOrDefault("malToCrunchyroll", Map.of()).get(malId);
    }

    public String malIdOverride(String crunchyrollId) {
        return loadOverrides().getOrDefault("crunchyrollToMal", Map.of()).get(crunchyrollId);
    }

    private Map<String, Map<String, String>> loadOverrides() {
        if (!Files.exists(overridesPath)) {
            Map<String, Map<String, String>> template = new LinkedHashMap<>();
            template.put("malToCrunchyroll", new LinkedHashMap<>());
            template.put("crunchyrollToMal", new LinkedHashMap<>());
            write(overridesPath, template);
            return template;
        }
        try {
            Map<String, Map<String, String>> overrides = objectMapper.readValue(overridesPath.toFile(), OVERRIDES);
            return overrides != null ? overrides : Map.of();
        } catch (IOException e) {
            log.warn("Ignoring unreadable title overrides file {}: {}", overridesPath, e.getMessage());
            return Map.of();
        }
    }

    private Map<String, String> loadMisses() {
        if (!Files.exists(missesPath)) {
            return new HashMap<>();
        }
        try {
            return new HashMap<>(objectMapper.readValue(missesPath.toFile(), STRING_MAP));
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    private void write(Path path, Object value) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
        } catch (IOException e) {
            log.warn("Failed to write {}: {}", path, e.getMessage());
        }
    }
}
