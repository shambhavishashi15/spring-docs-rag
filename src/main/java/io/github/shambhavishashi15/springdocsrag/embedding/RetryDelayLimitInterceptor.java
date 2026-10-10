package io.github.shambhavishashi15.springdocsrag.embedding;

import java.io.IOException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

import okhttp3.Interceptor;
import okhttp3.Response;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Stops the Google SDK's retry loop when Gemini asks for a longer wait than our backoff allows
// (the free tier's daily quota answers "retry in 8h"). Gemini sends that wait only in the JSON body
// (google.rpc.RetryInfo), which the SDK's RetryInterceptor never reads.
// Must be a network interceptor: those run inside the SDK's RetryInterceptor, once per attempt.
// It throws an unchecked exception because the SDK retries IOExceptions.
class RetryDelayLimitInterceptor implements Interceptor {

	private static final String RETRY_INFO_TYPE = "type.googleapis.com/google.rpc.RetryInfo";

	// Gemini's error bodies are a few KB; this only bounds what we buffer.
	private static final long MAX_ERROR_BODY_BYTES = 64 * 1024;

	private final Duration maxDelay;

	RetryDelayLimitInterceptor(Duration maxDelay) {
		this.maxDelay = maxDelay;
	}

	@Override
	public Response intercept(Chain chain) throws IOException {
		Response response = chain.proceed(chain.request());
		if (response.code() == 429) {
			// peekBody leaves the body readable for the SDK if we pass the response on.
			String body = response.peekBody(MAX_ERROR_BODY_BYTES).string();
			try {
				failIfRetryDelayTooLong(body);
			}
			catch (EmbeddingQuotaExceededException ex) {
				response.close();
				throw ex;
			}
		}
		return response;
	}

	// A 429 without a parseable RetryInfo is left to the SDK's normal backoff.
	void failIfRetryDelayTooLong(String errorBody) {
		JsonNode error;
		try {
			error = JsonMapper.shared().readTree(errorBody).path("error");
		}
		catch (JacksonException ex) {
			return;
		}
		Optional<Duration> retryDelay = retryDelay(error);
		if (retryDelay.isPresent() && retryDelay.get().compareTo(maxDelay) > 0) {
			throw new EmbeddingQuotaExceededException(retryDelay.get(), error.path("message").asString("").strip());
		}
	}

	// RetryInfo.retryDelay is a protobuf Duration in JSON form: seconds with an "s" suffix, e.g. "28940s" or "40.5s".
	private static Optional<Duration> retryDelay(JsonNode error) {
		for (JsonNode detail : error.path("details")) {
			if (RETRY_INFO_TYPE.equals(detail.path("@type").asString(""))) {
				try {
					return Optional.of(Duration.parse("PT" + detail.path("retryDelay").asString("").toUpperCase(Locale.ROOT)));
				}
				catch (DateTimeParseException ex) {
					return Optional.empty();
				}
			}
		}
		return Optional.empty();
	}

}
