package io.github.shambhavishashi15.springdocsrag.embedding;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

// Gemini rejected an embedding call with 429 and asked us to wait longer than our retry backoff allows
// (e.g. the free tier's daily quota), so retrying now is pointless.
public class EmbeddingQuotaExceededException extends RuntimeException {

	private final Duration retryAfter;

	public EmbeddingQuotaExceededException(Duration retryAfter, String geminiMessage) {
		super("Gemini embedding quota exceeded; retry in " + format(retryAfter) + ". Gemini said: " + geminiMessage);
		this.retryAfter = retryAfter;
	}

	public Duration getRetryAfter() {
		return retryAfter;
	}

	// "8h2m20s" instead of Duration's "PT8H2M20.879S".
	private static String format(Duration duration) {
		return duration.truncatedTo(ChronoUnit.SECONDS).toString().substring(2).toLowerCase(Locale.ROOT);
	}

}
