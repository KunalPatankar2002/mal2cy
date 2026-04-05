package com.mal2cy.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mal2cy.service.CrunchyrollService;

@RestController
@RequestMapping("/auth/crunchyroll")
public class CrunchyrollController {

    private final CrunchyrollService crunchyrollService;

    public CrunchyrollController(CrunchyrollService crunchyrollService) {
        this.crunchyrollService = crunchyrollService;
    }

    @GetMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate() throws IOException {
        List<Map<String, Object>> watchlist = crunchyrollService.getWatchlist();
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "accountId", crunchyrollService.getAccountId(),
                "watchlistCount", watchlist.size()));
    }
}
