package com.mal2cy.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mal2cy.model.AnimeEntry;

public interface AnimeEntryRepository extends JpaRepository<AnimeEntry, Long> {
    List<AnimeEntry> findBySource(String source);
    AnimeEntry findByMalId(String malId);
    AnimeEntry findByCrunchyrollId(String crunchyrollId);
}