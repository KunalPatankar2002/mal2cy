package com.mal2cy.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mal2cy.model.AnimeEntry;

public interface AnimeEntryRepository extends JpaRepository<AnimeEntry, Long> {
    List<AnimeEntry> findBySource(String source);

    List<AnimeEntry> findAllByMalIdOrderByIdAsc(String malId);

    List<AnimeEntry> findAllByCrunchyrollIdOrderByIdAsc(String crunchyrollId);

    /**
     * Returns the oldest entry for a MAL id. Older databases can hold duplicate rows,
     * so this never throws on non-unique results.
     */
    default AnimeEntry findByMalId(String malId) {
        List<AnimeEntry> entries = findAllByMalIdOrderByIdAsc(malId);
        return entries.isEmpty() ? null : entries.get(0);
    }

    default AnimeEntry findByCrunchyrollId(String crunchyrollId) {
        List<AnimeEntry> entries = findAllByCrunchyrollIdOrderByIdAsc(crunchyrollId);
        return entries.isEmpty() ? null : entries.get(0);
    }
}
