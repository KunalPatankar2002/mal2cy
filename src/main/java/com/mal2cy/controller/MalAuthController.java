package com.mal2cy.controller;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mal2cy.service.MalOAuthBootstrapService;

@RestController
@RequestMapping("/auth/mal")
public class MalAuthController {

    private final MalOAuthBootstrapService malOAuthBootstrapService;

    public MalAuthController(MalOAuthBootstrapService malOAuthBootstrapService) {
        this.malOAuthBootstrapService = malOAuthBootstrapService;
    }

    @GetMapping("/start")
    public ResponseEntity<Void> startAuthorization() {
        return ResponseEntity.status(302)
                .location(URI.create(malOAuthBootstrapService.buildAuthorizationUrl()))
                .build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Map<String, String>> completeAuthorization(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription) throws IOException {
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "error", error,
                    "message", errorDescription != null ? errorDescription : "MAL authorization failed before a code was issued."));
        }
        if (code == null || state == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Missing MAL authorization code or state. Start again from /auth/mal/start."));
        }

        malOAuthBootstrapService.completeAuthorization(code, state);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "message", "MAL tokens saved successfully. You can close this page and start the sync."));
    }
}
