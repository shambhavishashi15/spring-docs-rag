package io.github.shambhavishashi15.springdocsrag.web;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.openai.errors.RateLimitException;
import io.github.shambhavishashi15.springdocsrag.embedding.EmbeddingQuotaExceededException;
import io.github.shambhavishashi15.springdocsrag.gemini.GeminiErrorDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Turns errors into RFC 9457 ProblemDetail JSON. The base class already covers Spring MVC's own
// exceptions (malformed JSON, unsupported method, ...); this adds validation details and our errors.
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	// 400: the base class's ProblemDetail, plus which field failed and why.
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		Map<String, String> errors = new LinkedHashMap<>();
		for (FieldError error : ex.getBindingResult().getFieldErrors()) {
			errors.putIfAbsent(error.getField(), error.getDefaultMessage());
		}
		ProblemDetail problem = ex.getBody();
		problem.setProperty("errors", errors);
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	// 429: Gemini asked for a longer wait than our retry allows (e.g. the daily embedding quota).
	@ExceptionHandler(EmbeddingQuotaExceededException.class)
	ResponseEntity<ProblemDetail> handleEmbeddingQuotaExceeded(EmbeddingQuotaExceededException ex) {
		long retryAfterSeconds = toRetryAfterSeconds(ex.getRetryAfter());
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
				"The Gemini embedding quota is exhausted. Retry after " + retryAfterSeconds + " seconds.");
		problem.setTitle("Gemini quota exceeded");
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
				.body(problem);
	}

	// 429: Gemini's chat rate limit, still hit after the OpenAI SDK's own retries with backoff.
	// Logs which quota ran out (per minute or per day) and passes Gemini's suggested wait on as Retry-After.
	@ExceptionHandler(RateLimitException.class)
	ResponseEntity<ProblemDetail> handleChatRateLimit(RateLimitException ex) {
		GeminiErrorDetails details = errorDetails(ex);
		log.warn("Gemini chat rate limit: quotaId={}, retryDelay={}", details.quotaId().orElse("unknown"),
				details.retryDelay().map(Duration::toString).orElse("unknown"));
		Optional<Long> retryAfterSeconds = details.retryDelay().map(GlobalExceptionHandler::toRetryAfterSeconds);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
				retryAfterSeconds.map(seconds -> "Gemini's rate limit was exceeded. Retry after " + seconds + " seconds.")
						.orElse("Gemini's rate limit was exceeded. Try again in a minute."));
		problem.setTitle("Gemini rate limit exceeded");
		ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
		retryAfterSeconds.ifPresent(seconds -> response.header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds)));
		return response.body(problem);
	}

	// 500: anything else. Logged in full, but the client gets no internal details.
	@ExceptionHandler(Exception.class)
	ProblemDetail handleUnexpected(Exception ex) {
		log.error("Unexpected error", ex);
		return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
	}

	// The OpenAI SDK keeps Gemini's error JSON as its own JsonValue (Jackson 2); convert it to a Jackson 3 tree.
	// Never fails: a body we can't read only means the log and response carry no details.
	private static GeminiErrorDetails errorDetails(RateLimitException ex) {
		try {
			JsonNode body = JsonMapper.shared().valueToTree(ex.body().convert(Object.class));
			return GeminiErrorDetails.from(body);
		}
		catch (RuntimeException conversionFailure) {
			return new GeminiErrorDetails(Optional.empty(), Optional.empty());
		}
	}

	// Retry-After is in whole seconds; round up so clients never retry too early.
	private static long toRetryAfterSeconds(Duration delay) {
		return (delay.toMillis() + 999) / 1000;
	}

}
