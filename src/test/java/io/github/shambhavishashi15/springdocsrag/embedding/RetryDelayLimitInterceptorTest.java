package io.github.shambhavishashi15.springdocsrag.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RetryDelayLimitInterceptorTest {

	private final RetryDelayLimitInterceptor interceptor = new RetryDelayLimitInterceptor(Duration.ofSeconds(60));

	@Test
	void failsFastWhenGeminiAsksForALongerWaitThanMaxDelay() {
		// Shape of Gemini's real 429 for the free tier's daily embedding quota.
		assertThatExceptionOfType(EmbeddingQuotaExceededException.class)
				.isThrownBy(() -> interceptor.failIfRetryDelayTooLong(errorBody("28940s")))
				.withMessageStartingWith("Gemini embedding quota exceeded; retry in 8h2m20s.")
				.withMessageContaining("Quota exceeded for metric")
				.satisfies(ex -> assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofSeconds(28940)));
	}

	@Test
	void leavesShortWaitsToTheSdksBackoff() {
		assertThatCode(() -> interceptor.failIfRetryDelayTooLong(errorBody("40s"))).doesNotThrowAnyException();
		assertThatCode(() -> interceptor.failIfRetryDelayTooLong(errorBody("60s"))).doesNotThrowAnyException();
	}

	@Test
	void parsesFractionalSeconds() {
		assertThatCode(() -> interceptor.failIfRetryDelayTooLong(errorBody("59.9s"))).doesNotThrowAnyException();
		assertThatExceptionOfType(EmbeddingQuotaExceededException.class)
				.isThrownBy(() -> interceptor.failIfRetryDelayTooLong(errorBody("60.5s")))
				.satisfies(ex -> assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofMillis(60500)));
	}

	@Test
	void ignoresA429WithoutRetryInfo() {
		String body = """
				{"error": {"code": 429, "message": "Resource exhausted.", "status": "RESOURCE_EXHAUSTED"}}""";
		assertThatCode(() -> interceptor.failIfRetryDelayTooLong(body)).doesNotThrowAnyException();
	}

	@Test
	void ignoresABodyThatIsNotJson() {
		assertThatCode(() -> interceptor.failIfRetryDelayTooLong("Too Many Requests")).doesNotThrowAnyException();
	}

	private static String errorBody(String retryDelay) {
		return """
				{
				  "error": {
				    "code": 429,
				    "message": "You exceeded your current quota.\\n* Quota exceeded for metric: generativelanguage.googleapis.com/embed_content_free_tier_requests, limit: 1000\\n",
				    "status": "RESOURCE_EXHAUSTED",
				    "details": [
				      {"@type": "type.googleapis.com/google.rpc.QuotaFailure",
				       "violations": [{"quotaId": "EmbedContentRequestsPerDayPerProjectPerModel-FreeTier"}]},
				      {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "%s"}
				    ]
				  }
				}""".formatted(retryDelay);
	}

}
