package io.github.shambhavishashi15.springdocsrag.gemini;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

// What Gemini says about a 429 in its error JSON: which quota ran out and how long to wait.
// Gemini sends both only in the body's "details" (google.rpc.QuotaFailure and google.rpc.RetryInfo),
// never as headers. Accepts the native body {"error": {...}}, the array-wrapped body
// [{"error": {...}}] of the OpenAI-compatible endpoint, or the error object itself (as the OpenAI SDK
// hands it over).
public record GeminiErrorDetails(Optional<String> quotaId, Optional<Duration> retryDelay) {

	private static final String QUOTA_FAILURE_TYPE = "type.googleapis.com/google.rpc.QuotaFailure";

	private static final String RETRY_INFO_TYPE = "type.googleapis.com/google.rpc.RetryInfo";

	public static GeminiErrorDetails from(JsonNode body) {
		JsonNode error = body.isArray() ? body.path(0) : body;
		if (error.has("error")) {
			error = error.path("error");
		}
		Optional<String> quotaId = Optional.empty();
		Optional<Duration> retryDelay = Optional.empty();
		for (JsonNode detail : error.path("details")) {
			String type = detail.path("@type").asString("");
			if (QUOTA_FAILURE_TYPE.equals(type)) {
				quotaId = Optional.ofNullable(detail.path("violations").path(0).path("quotaId").asString(null));
			}
			else if (RETRY_INFO_TYPE.equals(type)) {
				retryDelay = parseDelay(detail.path("retryDelay").asString(""));
			}
		}
		return new GeminiErrorDetails(quotaId, retryDelay);
	}

	// RetryInfo.retryDelay is a protobuf Duration in JSON form: seconds with an "s" suffix, e.g. "28940s" or "40.5s".
	private static Optional<Duration> parseDelay(String value) {
		try {
			return Optional.of(Duration.parse("PT" + value.toUpperCase(Locale.ROOT)));
		}
		catch (DateTimeParseException ex) {
			return Optional.empty();
		}
	}

}
