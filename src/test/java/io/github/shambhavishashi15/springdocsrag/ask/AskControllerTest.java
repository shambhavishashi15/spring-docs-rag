package io.github.shambhavishashi15.springdocsrag.ask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Duration;
import java.util.List;

import com.openai.core.http.Headers;
import com.openai.errors.RateLimitException;
import io.github.shambhavishashi15.springdocsrag.embedding.EmbeddingQuotaExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

// Web layer only: the controller, validation and GlobalExceptionHandler, with AskService mocked.
@WebMvcTest(AskController.class)
class AskControllerTest {

	@Autowired
	private MockMvcTester mvc;

	@MockitoBean
	private AskService askService;

	@Test
	void returnsTheAnswerWithCitations() {
		given(askService.ask("How do I activate a profile?")).willReturn(new AskResponse(
				"Set spring.profiles.active [1].", true,
				List.of(new Citation(1, "Spring Boot: Profiles", "Adding Active Profiles", "https://docs.spring.io/x#y"))));

		assertThat(ask("{\"question\": \"How do I activate a profile?\"}"))
				.hasStatusOk()
				.bodyJson().isLenientlyEqualTo("""
						{"answer": "Set spring.profiles.active [1].", "answered": true,
						 "citations": [{"id": 1, "page": "Spring Boot: Profiles", "section": "Adding Active Profiles",
						                "url": "https://docs.spring.io/x#y"}]}""");
	}

	@Test
	void rejectsABlankQuestionWithAProblemDetail() {
		assertThat(ask("{\"question\": \"  \"}"))
				.hasStatus(HttpStatus.BAD_REQUEST)
				.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
				.bodyJson().isLenientlyEqualTo("""
						{"status": 400, "title": "Bad Request", "errors": {"question": "must not be blank"}}""");
		then(askService).shouldHaveNoInteractions();
	}

	@Test
	void rejectsAMissingQuestion() {
		assertThat(ask("{}"))
				.hasStatus(HttpStatus.BAD_REQUEST)
				.bodyJson().extractingPath("$.errors.question").isEqualTo("must not be blank");
	}

	@Test
	void rejectsATooLongQuestion() {
		assertThat(ask("{\"question\": \"" + "a".repeat(1001) + "\"}"))
				.hasStatus(HttpStatus.BAD_REQUEST)
				.bodyJson().extractingPath("$.errors.question").isEqualTo("size must be between 0 and 1000");
	}

	@Test
	void rejectsMalformedJson() {
		assertThat(ask("{\"question\": "))
				.hasStatus(HttpStatus.BAD_REQUEST)
				.hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
	}

	@Test
	void mapsTheEmbeddingQuotaTo429WithRetryAfter() {
		given(askService.ask(anyString()))
				.willThrow(new EmbeddingQuotaExceededException(Duration.ofMillis(28940_400), "Quota exceeded."));

		assertThat(ask("{\"question\": \"What is a bean?\"}"))
				.hasStatus(HttpStatus.TOO_MANY_REQUESTS)
				.hasHeader("Retry-After", "28941")
				.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
				.bodyJson().isLenientlyEqualTo("""
						{"status": 429, "title": "Gemini quota exceeded",
						 "detail": "The Gemini embedding quota is exhausted. Retry after 28941 seconds."}""");
	}

	@Test
	void mapsTheChatRateLimitTo429() {
		given(askService.ask(anyString()))
				.willThrow(RateLimitException.builder().headers(Headers.builder().build()).build());

		assertThat(ask("{\"question\": \"What is a bean?\"}"))
				.hasStatus(HttpStatus.TOO_MANY_REQUESTS)
				.bodyJson().extractingPath("$.title").isEqualTo("Gemini rate limit exceeded");
	}

	@Test
	void hidesUnexpectedErrorsBehindAGeneric500() {
		given(askService.ask(anyString())).willThrow(new IllegalStateException("jdbc:postgresql://secret-host"));

		assertThat(ask("{\"question\": \"What is a bean?\"}"))
				.hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
				.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
				.bodyText().doesNotContain("secret-host").contains("An unexpected error occurred.");
	}

	private MvcTestResult ask(String json) {
		return mvc.post().uri("/api/v1/ask").contentType(MediaType.APPLICATION_JSON).content(json).exchange();
	}

}
