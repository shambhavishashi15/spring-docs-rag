package io.github.shambhavishashi15.springdocsrag.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GeminiErrorDetailsTest {

	// The shape of a real free-tier 429 (trimmed).
	private static final String ERROR = """
			{"code": 429, "message": "You exceeded your current quota.", "status": "RESOURCE_EXHAUSTED",
			 "details": [
			   {"@type": "type.googleapis.com/google.rpc.QuotaFailure",
			    "violations": [{"quotaMetric": "generativelanguage.googleapis.com/generate_content_free_tier_requests",
			                    "quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]},
			   {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "41s"}
			 ]}""";

	@Test
	void readsTheErrorObject() {
		assertDetails(json(ERROR));
	}

	@Test
	void readsTheNativeBody() {
		assertDetails(json("{\"error\": " + ERROR + "}"));
	}

	@Test
	void readsTheArrayWrappedBodyOfTheOpenAiCompatibleEndpoint() {
		assertDetails(json("[{\"error\": " + ERROR + "}]"));
	}

	@Test
	void readsFractionalDelays() {
		JsonNode error = json("""
				{"details": [{"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "40.5s"}]}""");
		assertThat(GeminiErrorDetails.from(error).retryDelay()).contains(Duration.ofMillis(40_500));
	}

	@Test
	void isEmptyWithoutDetails() {
		GeminiErrorDetails details = GeminiErrorDetails.from(json("{\"code\": 429, \"message\": \"Too many requests\"}"));
		assertThat(details.quotaId()).isEmpty();
		assertThat(details.retryDelay()).isEmpty();
	}

	@Test
	void ignoresAnUnparseableDelay() {
		JsonNode error = json("""
				{"details": [{"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "soon"}]}""");
		assertThat(GeminiErrorDetails.from(error).retryDelay()).isEmpty();
	}

	private static void assertDetails(JsonNode body) {
		GeminiErrorDetails details = GeminiErrorDetails.from(body);
		assertThat(details.quotaId()).contains("GenerateRequestsPerDayPerProjectPerModel-FreeTier");
		assertThat(details.retryDelay()).contains(Duration.ofSeconds(41));
	}

	private static JsonNode json(String text) {
		return JsonMapper.shared().readTree(text);
	}

}
