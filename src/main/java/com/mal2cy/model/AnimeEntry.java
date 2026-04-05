package com.mal2cy.model;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "anime_entries")
public class AnimeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private String malId;
    private String crunchyrollId;
    private String status; // watching, completed, etc.
    private LocalDateTime lastUpdated;
    private String source; // mal or crunchyroll

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getMalId() { return malId; }
    public void setMalId(String malId) { this.malId = malId; }

    public String getCrunchyrollId() { return crunchyrollId; }
    public void setCrunchyrollId(String crunchyrollId) { this.crunchyrollId = crunchyrollId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(LocalDateTime lastUpdated) { this.lastUpdated = lastUpdated; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}