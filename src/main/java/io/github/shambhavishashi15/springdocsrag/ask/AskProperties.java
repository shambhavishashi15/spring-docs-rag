package io.github.shambhavishashi15.springdocsrag.ask;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Retrieval settings for /api/v1/ask, bound from app.ask.* in application.yml.
@ConfigurationProperties("app.ask")
public record AskProperties(int topK, double similarityThreshold) {
}
