package io.github.shambhavishashi15.springdocsrag.ingestion;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Ingestion settings, bound from app.ingestion.* in application.yml.
@ConfigurationProperties("app.ingestion")
public record IngestionProperties(Path docsDir, int chunkSize, int chunkOverlap) {
}
