package com.mal2cy.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class FilesystemInitializer {

    private final String datasourceUrl;
    private final String tokenStorePath;
    private final String logFilePath;

    public FilesystemInitializer(
            @Value("${spring.datasource.url}") String datasourceUrl,
            @Value("${app.auth.token-store-path}") String tokenStorePath,
            @Value("${logging.file.name}") String logFilePath) {
        this.datasourceUrl = datasourceUrl;
        this.tokenStorePath = tokenStorePath;
        this.logFilePath = logFilePath;
    }

    @PostConstruct
    public void initializeDirectories() throws IOException {
        createParentDirectory(extractSqlitePath(datasourceUrl));
        createParentDirectory(tokenStorePath);
        createParentDirectory(logFilePath);
    }

    private void createParentDirectory(String filePath) throws IOException {
        Path parent = Path.of(filePath).toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private String extractSqlitePath(String jdbcUrl) {
        String prefix = "jdbc:sqlite:";
        if (jdbcUrl.startsWith(prefix)) {
            return jdbcUrl.substring(prefix.length());
        }
        return jdbcUrl;
    }
}
