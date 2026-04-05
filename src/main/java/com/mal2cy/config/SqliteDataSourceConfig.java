package com.mal2cy.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class SqliteDataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(
            @Value("${spring.datasource.url}") String datasourceUrl,
            @Value("${spring.datasource.driver-class-name}") String driverClassName) throws IOException {
        createParentDirectory(extractSqlitePath(datasourceUrl));

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(driverClassName);
        dataSource.setUrl(datasourceUrl);
        return dataSource;
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
