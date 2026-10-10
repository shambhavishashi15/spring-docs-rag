package io.github.shambhavishashi15.springdocsrag.embedding;

import java.util.List;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GeminiEmbeddingConfig {

	// Gemini rejects larger requests: "at most 100 requests can be in one batch".
	static final int GEMINI_MAX_BATCH_SIZE = 100;

	// Rate limits and transient server errors.
	private static final List<Integer> RETRYABLE_STATUS_CODES = List.of(429, 500, 502, 503, 504);

	// Replaces the auto-configured connection details to give the Google client longer retries.
	// The SDK's own retry is the only one that applies to these calls (Spring AI's RetryTemplate
	// doesn't retry the SDK's exceptions), and its default (5 attempts, ~15s total) is shorter
	// than the free tier's per-minute quota window.
	@Bean
	GoogleGenAiEmbeddingConnectionDetails googleGenAiEmbeddingConnectionDetails(
			@Value("${spring.ai.google.genai.embedding.api-key}") String apiKey, EmbeddingRetryProperties retry) {
		HttpRetryOptions retryOptions = HttpRetryOptions.builder()
				.attempts(retry.maxAttempts())
				.initialDelay(retry.initialDelay().toMillis() / 1000.0)
				.maxDelay(retry.maxDelay().toMillis() / 1000.0)
				.expBase(2.0)
				.httpStatusCodes(RETRYABLE_STATUS_CODES)
				.build();
		Client client = Client.builder()
				.apiKey(apiKey)
				.httpOptions(HttpOptions.builder().retryOptions(retryOptions).build())
				.build();
		return GoogleGenAiEmbeddingConnectionDetails.builder().apiKey(apiKey).genAiClient(client).build();
	}

	// Replaces PgVectorStore's default token-based batching: keeps its token limit and adds Gemini's input limit.
	@Bean
	BatchingStrategy pgVectorStoreBatchingStrategy() {
		return new MaxSizeBatchingStrategy(new TokenCountBatchingStrategy(), GEMINI_MAX_BATCH_SIZE);
	}

}
