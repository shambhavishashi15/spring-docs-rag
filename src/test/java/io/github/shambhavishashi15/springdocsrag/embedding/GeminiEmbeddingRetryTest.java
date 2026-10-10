package io.github.shambhavishashi15.springdocsrag.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.genai.Client;
import com.google.genai.errors.ClientException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;

// Runs the real Google SDK client from GeminiEmbeddingConfig (with its retry and our interceptor)
// against a local fake Gemini that always answers 429, and counts the requests.
class GeminiEmbeddingRetryTest {

	// 3 attempts with short delays keep the test fast; max-delay 1s is the fail-fast threshold.
	private static final EmbeddingRetryProperties RETRY =
			new EmbeddingRetryProperties(3, Duration.ofMillis(10), Duration.ofSeconds(1));

	private final AtomicInteger requests = new AtomicInteger();

	private HttpServer server;

	private String retryDelay;

	@BeforeEach
	void startFakeGemini() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/", exchange -> {
			requests.incrementAndGet();
			byte[] body = """
					{"error": {"code": 429, "message": "Quota exceeded.", "status": "RESOURCE_EXHAUSTED",
					  "details": [{"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "%s"}]}}"""
					.formatted(retryDelay).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(429, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		// The SDK's static hook for its default base URL (used here instead of generativelanguage.googleapis.com).
		Client.setDefaultBaseUrls(Optional.of("http://localhost:" + server.getAddress().getPort() + "/"), Optional.empty());
	}

	@AfterEach
	void stopFakeGemini() {
		Client.setDefaultBaseUrls(Optional.empty(), Optional.empty());
		server.stop(0);
	}

	@Test
	void stopsAfterOneRequestWhenGeminiAsksForALongerWaitThanMaxDelay() {
		retryDelay = "28940s";
		assertThatExceptionOfType(EmbeddingQuotaExceededException.class)
				.isThrownBy(() -> embeddingModel().embed(List.of("some chunk")))
				.satisfies(ex -> assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofSeconds(28940)));
		assertThat(requests).hasValue(1);
	}

	@Test
	void keepsRetryingShortWaits() {
		retryDelay = "0.5s";
		assertThatThrownBy(() -> embeddingModel().embed(List.of("some chunk"))).isInstanceOf(ClientException.class);
		assertThat(requests).hasValue(RETRY.maxAttempts());
	}

	private static GoogleGenAiTextEmbeddingModel embeddingModel() {
		return new GoogleGenAiTextEmbeddingModel(
				new GeminiEmbeddingConfig().googleGenAiEmbeddingConnectionDetails("test-dummy-key", RETRY),
				GoogleGenAiTextEmbeddingOptions.builder().model("gemini-embedding-2").dimensions(768).build());
	}

}
