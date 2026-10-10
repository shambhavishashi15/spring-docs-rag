package io.github.shambhavishashi15.springdocsrag.embedding;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Retry/backoff for Gemini embedding calls, bound from app.embedding.retry.* in application.yml.
@ConfigurationProperties("app.embedding.retry")
public record EmbeddingRetryProperties(int maxAttempts, Duration initialDelay, Duration maxDelay) {
}
