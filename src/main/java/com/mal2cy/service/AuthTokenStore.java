package com.mal2cy.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class AuthTokenStore {

    private static final TypeReference<Map<String, String>> TOKEN_MAP_TYPE = new TypeReference<>() {
    };

    private final Path tokenStorePath;
    private final ObjectMapper objectMapper;

    public AuthTokenStore(@Value("${app.auth.token-store-path:./data/auth-tokens.json}") String tokenStorePath,
            ObjectMapper objectMapper) {
        this.tokenStorePath = Path.of(tokenStorePath);
        this.objectMapper = objectMapper;
    }

    public synchronized Map<String, String> loadTokens() {
        if (!Files.exists(tokenStorePath)) {
            return new HashMap<>();
        }

        try {
            return objectMapper.readValue(tokenStorePath.toFile(), TOKEN_MAP_TYPE);
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    public synchronized void saveMalTokens(String accessToken, String refreshToken) throws IOException {
        Map<String, String> tokens = loadTokens();
        tokens.put("malAccessToken", accessToken);
        tokens.put("malRefreshToken", refreshToken);

        writeState(tokens);
    }

    public synchronized String loadValue(String key) {
        return loadTokens().get(key);
    }

    public synchronized void saveValue(String key, String value) throws IOException {
        Map<String, String> state = loadTokens();
        if (value == null) {
            state.remove(key);
        } else {
            state.put(key, value);
        }

        writeState(state);
    }

    private void writeState(Map<String, String> state) throws IOException {
        Map<String, String> persistedState = new HashMap<>(state);

        Path parent = tokenStorePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tokenStorePath.toFile(), persistedState);
    }
}
