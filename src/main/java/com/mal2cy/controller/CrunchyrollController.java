package com.mal2cy.controller;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mal2cy.service.CrunchyrollService;
import com.mal2cy.service.CrunchyrollTvAuthService;

@RestController
@RequestMapping("/auth/crunchyroll")
public class CrunchyrollController {

    private final CrunchyrollService crunchyrollService;
    private final CrunchyrollTvAuthService tvAuthService;

    public CrunchyrollController(CrunchyrollService crunchyrollService, CrunchyrollTvAuthService tvAuthService) {
        this.crunchyrollService = crunchyrollService;
        this.tvAuthService = tvAuthService;
    }

    @GetMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate() throws IOException {
        List<Map<String, Object>> watchlist = crunchyrollService.getWatchlist();
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "accountId", crunchyrollService.getAccountId(),
                "watchlistCount", watchlist.size(),
                "tvLoginActivated", tvAuthService.isActivated()));
    }

    /**
     * Starts the one-time TV-style activation. Open this in a browser, then enter the
     * returned code at crunchyroll.com/activate; the app picks up the login by itself.
     */
    @GetMapping("/activate")
    public ResponseEntity<Map<String, Object>> activate() throws IOException {
        return ResponseEntity.ok(describe(tvAuthService.startActivation()));
    }

    @GetMapping("/activate/status")
    public ResponseEntity<Map<String, Object>> activationStatus() {
        CrunchyrollTvAuthService.Activation activation = tvAuthService.getActivation();
        if (activation == null) {
            return ResponseEntity.ok(Map.of(
                    "status", tvAuthService.isActivated() ? "activated" : "not_started"));
        }
        return ResponseEntity.ok(describe(activation));
    }

    private Map<String, Object> describe(CrunchyrollTvAuthService.Activation activation) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", activation.status());
        body.put("code", activation.userCode());
        body.put("enterCodeAt", CrunchyrollTvAuthService.ACTIVATION_URL);
        body.put("expiresAt", activation.expiresAt().toString());
        if (activation.message() != null) {
            body.put("message", activation.message());
        }
        return body;
    }
}
