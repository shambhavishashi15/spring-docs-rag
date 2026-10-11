package io.github.shambhavishashi15.springdocsrag.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.shambhavishashi15.springdocsrag.eval.EvalRunner.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Drives EvalRunner.run against a local fake /api/v1/ask (no app, no Gemini) to check that results are
// saved after every question and that a re-run resumes instead of starting over.
class EvalRunnerResumeTest {

	private static final String PAGE = "https://docs.spring.io/spring-boot/reference/features/profiles.html";

	@TempDir
	Path dir;

	private final List<String> asked = new CopyOnWriteArrayList<>();

	// Questions the fake answers with 429 and a day-long Retry-After (Gemini's daily quota).
	private volatile Set<String> quotaExhaustedFor = Set.of();

	// Questions the fake answers with 500.
	private volatile Set<String> failingFor = Set.of();

	private HttpServer server;

	private Path questions;

	private Path results;

	private Path report;

	@BeforeEach
	void startFakeApp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/api/v1/ask", this::answer);
		server.start();
		questions = dir.resolve("questions.json");
		results = dir.resolve("results.json");
		report = dir.resolve("report.md");
		writeQuestions("Q2");
	}

	@AfterEach
	void stopFakeApp() {
		server.stop(0);
	}

	@Test
	void savesEachResultAndResumesAfterAQuotaStop() throws Exception {
		quotaExhaustedFor = Set.of("Q2");
		assertThatIllegalStateException().isThrownBy(() -> run(false))
				.withMessageContaining("Retry-After 86400s")
				.withMessageContaining("1/3 results are saved");
		assertThat(asked).containsExactly("Q1", "Q2");
		assertThat(savedIds()).containsExactly("q1");
		assertThat(Files.readString(report)).contains("**Incomplete:** 1/3");

		asked.clear();
		quotaExhaustedFor = Set.of();
		List<Result> all = run(false);

		assertThat(asked).containsExactly("Q2", "Q3");
		assertThat(all).extracting(result -> result.question().id()).containsExactly("q1", "q2", "q3");
		assertThat(all).allMatch(Result::passed);
		assertThat(Files.readString(report)).doesNotContain("Incomplete").contains("| Questions passed | 3/3 |");
	}

	@Test
	void savesTheAnswerCitationsAndEveryCheck() throws Exception {
		run(false);

		JsonNode first = JsonMapper.shared().readTree(results.toFile()).path(0);
		assertThat(first.path("question").path("question").asString()).isEqualTo("Q1");
		assertThat(first.path("response").path("answer").asString()).isEqualTo("Use spring.profiles.active [1].");
		assertThat(first.path("response").path("citations").path(0).path("url").asString()).isEqualTo(PAGE);
		assertThat(first.path("factsFound").path(0).asString()).isEqualTo("spring.profiles.active");
		assertThat(first.path("pageCited").asBoolean()).isTrue();
		assertThat(first.path("refusalCorrect").asBoolean()).isTrue();
		assertThat(first.path("passed").asBoolean()).isTrue();
	}

	@Test
	void startsOverWhenFresh() throws Exception {
		run(false);
		asked.clear();

		run(true);

		assertThat(asked).containsExactly("Q1", "Q2", "Q3");
	}

	@Test
	void asksAgainWhenAQuestionWasEdited() throws Exception {
		run(false);
		asked.clear();
		writeQuestions("Q2 reworded");

		run(false);

		assertThat(asked).containsExactly("Q2 reworded");
	}

	@Test
	void asksAgainWhenTheSavedResultWasAnError() throws Exception {
		failingFor = Set.of("Q3");
		run(false);
		assertThat(savedIds()).containsExactly("q1", "q2", "q3");
		asked.clear();
		failingFor = Set.of();

		List<Result> all = run(false);

		assertThat(asked).containsExactly("Q3");
		assertThat(all).allMatch(Result::passed);
	}

	private List<Result> run(boolean fresh) throws Exception {
		String baseUrl = "http://localhost:" + server.getAddress().getPort();
		return EvalRunner.run(RestClient.create(baseUrl), baseUrl, questions, results, report, Duration.ZERO, fresh);
	}

	private List<String> savedIds() throws IOException {
		JsonNode saved = JsonMapper.shared().readTree(results.toFile());
		return saved.valueStream().map(result -> result.path("question").path("id").asString()).toList();
	}

	private void writeQuestions(String secondQuestion) throws IOException {
		Files.writeString(questions, """
				{"questions": [
				  {"id": "q1", "question": "Q1", "answerable": true, "expectedPage": "%1$s", "keyFacts": ["spring.profiles.active"]},
				  {"id": "q2", "question": "%2$s", "answerable": true, "expectedPage": "%1$s", "keyFacts": ["spring.profiles.active"]},
				  {"id": "q3", "question": "Q3", "answerable": true, "expectedPage": "%1$s", "keyFacts": ["spring.profiles.active"]}
				]}""".formatted(PAGE, secondQuestion));
	}

	private void answer(HttpExchange exchange) throws IOException {
		String question = JsonMapper.shared().readTree(exchange.getRequestBody()).path("question").asString();
		asked.add(question);
		if (quotaExhaustedFor.contains(question)) {
			exchange.getResponseHeaders().add("Retry-After", "86400");
			respond(exchange, 429, "{\"status\": 429, \"title\": \"Gemini rate limit exceeded\"}");
		}
		else if (failingFor.contains(question)) {
			respond(exchange, 500, "{\"status\": 500}");
		}
		else {
			respond(exchange, 200, """
					{"answer": "Use spring.profiles.active [1].", "answered": true,
					 "citations": [{"id": 1, "page": "Spring Boot: Profiles", "section": "Profiles", "url": "%s"}]}"""
					.formatted(PAGE));
		}
	}

	private static void respond(HttpExchange exchange, int status, String json) throws IOException {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

}
