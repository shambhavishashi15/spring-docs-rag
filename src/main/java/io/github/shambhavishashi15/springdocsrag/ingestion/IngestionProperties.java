package io.github.shambhavishashi15.springdocsrag.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Chunking settings, bound from app.ingestion.* in application.yml.
@ConfigurationProperties("app.ingestion")
public record IngestionProperties(int chunkSize, int chunkOverlap) {
}
