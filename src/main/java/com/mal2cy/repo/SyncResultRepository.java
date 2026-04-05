package com.mal2cy.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mal2cy.model.SyncResult;

public interface SyncResultRepository extends JpaRepository<SyncResult, Long> {
}