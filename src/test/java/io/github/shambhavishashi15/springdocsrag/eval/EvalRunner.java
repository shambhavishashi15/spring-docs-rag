package io.github.shambhavishashi15.springdocsrag.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import io.github.shambhavishashi15.springdocsrag.ask.AskRequest;
import io.github.shambhavishashi15.springdocsrag.ask.AskResponse;
import io.github.shambhavishashi15.springdocsrag.ask.Citation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

// Scores POST /api/v1/ask on a running app against eval/questions.json and writes eval/report.md.
// It makes real Gemini calls, so it is skipped unless eval.baseUrl is set (./mvnw verify and CI never run it):
//   ./mvnw test -Dtest=EvalRunner -Deval.baseUrl=http://localhost:8080
@EnabledIfSystemProperty(named = "eval.baseUrl", matches = ".+")
class EvalRunner {

	private static final Path QUESTIONS = Path.of("eval/questions.json");

	private static final Path REPORT = Path.of("eval/report.md");

	private static final int MAX_ATTEMPTS = 3;

	// A longer Retry-After means the daily quota is gone: stop instead of sleeping for hours.
	private static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(2);

	@Test
	void scoreAnswers() throws Exception {
		String baseUrl = System.getProperty("eval.baseUrl");
		// Pause between questions to stay inside the free tier's per-minute limits.
		Duration delay = Duration.ofSeconds(Long.getLong("eval.delay", 5));
		RestClient client = RestClient.create(baseUrl);
		EvalSet evalSet = JsonMapper.shared().readValue(QUESTIONS.toFile(), EvalSet.class);

		List<Result> results = new ArrayList<>();
		for (EvalQuestion question : evalSet.questions()) {
			if (!results.isEmpty()) {
				Thread.sleep(delay);
			}
			Result result = evaluate(client, question);
			System.out.printf("%s %s %s%n", question.id(), result.passed() ? "PASS" : "FAIL", question.question());
			results.add(result);
		}
		Files.writeString(REPORT, report(results, baseUrl));
		System.out.println(summary(results));
		System.out.println("Report written to " + REPORT.toAbsolutePath());
	}

	private static Result evaluate(RestClient client, EvalQuestion question) throws InterruptedException {
		try {
			return score(question, ask(client, question.question()));
		}
		catch (RestClientException ex) {
			// One failing question (e.g. a 500) shouldn't end the run; it's reported as failed.
			return Result.error(question, ex.getMessage());
		}
	}

	private static AskResponse ask(RestClient client, String question) throws InterruptedException {
		for (int attempt = 1;; attempt++) {
			try {
				return client.post()
						.uri("/api/v1/ask")
						.contentType(MediaType.APPLICATION_JSON)
						.body(new AskRequest(question))
						.retrieve()
						.body(AskResponse.class);
			}
			catch (HttpClientErrorException.TooManyRequests ex) {
				String header = ex.getResponseHeaders() == null ? null : ex.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
				Duration retryAfter = Duration.ofSeconds(header == null ? 60 : Long.parseLong(header));
				if (attempt == MAX_ATTEMPTS || retryAfter.compareTo(MAX_RETRY_AFTER) > 0) {
					throw new IllegalStateException("Gemini quota exhausted (Retry-After " + retryAfter.toSeconds()
							+ "s); run the eval again after the quota resets", ex);
				}
				Thread.sleep(retryAfter);
			}
		}
	}

	static Result score(EvalQuestion question, AskResponse response) {
		String answer = response.answer().toLowerCase(Locale.ROOT);
		List<String> factsFound = question.keyFacts().stream()
				.filter(fact -> containsAny(answer, fact.split("\\|")))
				.toList();
		boolean pageCited = question.answerable() && response.citations().stream()
				.anyMatch(citation -> citation.url().startsWith(question.expectedPage()));
		boolean refusalCorrect = response.answered() == question.answerable();
		return new Result(question, response, null, factsFound, pageCited, refusalCorrect);
	}

	private static boolean containsAny(String answer, String[] alternatives) {
		for (String alternative : alternatives) {
			if (answer.contains(alternative.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	private static String summary(List<Result> results) {
		List<Result> answerable = results.stream().filter(result -> result.question().answerable()).toList();
		int facts = answerable.stream().mapToInt(result -> result.question().keyFacts().size()).sum();
		int factsFound = answerable.stream().mapToInt(result -> result.factsFound().size()).sum();
		return """
				| Metric | Score |
				|---|---|
				| Questions passed | %d/%d |
				| Key facts found (answerable questions) | %d/%d |
				| Expected page cited (answerable questions) | %d/%d |
				| Correct refusal behaviour (answered vs. "not in the docs") | %d/%d |
				""".formatted(
				count(results, Result::passed), results.size(),
				factsFound, facts,
				count(answerable, Result::pageCited), answerable.size(),
				count(results, Result::refusalCorrect), results.size());
	}

	private static String report(List<Result> results, String baseUrl) {
		StringBuilder out = new StringBuilder()
				.append("# Eval report\n\n")
				.append("Run at ").append(Instant.now()).append(" against ").append(baseUrl)
				.append(" with `eval/questions.json`.\n\n")
				.append(summary(results))
				.append("\n## Questions\n");
		for (Result result : results) {
			EvalQuestion question = result.question();
			out.append("\n### ").append(question.id()).append(result.passed() ? " ✅ " : " ❌ ")
					.append(question.question()).append("\n\n");
			if (result.error() != null) {
				out.append("- **Error:** ").append(result.error()).append('\n');
				continue;
			}
			if (question.answerable()) {
				out.append("- **Key facts:** ").append(result.factsFound().size()).append('/')
						.append(question.keyFacts().size()).append(" found ").append(question.keyFacts()).append('\n')
						.append("- **Expected page cited:** ").append(result.pageCited() ? "yes" : "no")
						.append(" (").append(question.expectedPage()).append(")\n");
			}
			out.append("- **Answered:** ").append(result.response().answered())
					.append(question.answerable() ? " (expected an answer)" : " (expected \"not in the docs\")").append('\n')
					.append("- **Citations:** ");
			List<Citation> citations = result.response().citations();
			out.append(citations.isEmpty() ? "none" : String.join("; ", citations.stream()
					.map(citation -> "[" + citation.id() + "] " + citation.page() + " > " + citation.section())
					.toList())).append("\n\n");
			result.response().answer().lines().forEach(line -> out.append("> ").append(line).append('\n'));
		}
		return out.toString();
	}

	private static int count(List<Result> results, Predicate<Result> predicate) {
		return (int) results.stream().filter(predicate).count();
	}

	record EvalSet(List<EvalQuestion> questions) {
	}

	record EvalQuestion(String id, String question, boolean answerable, String expectedPage, List<String> keyFacts) {

		EvalQuestion {
			keyFacts = (keyFacts == null) ? List.of() : keyFacts;
		}

	}

	record Result(EvalQuestion question, AskResponse response, String error, List<String> factsFound,
			boolean pageCited, boolean refusalCorrect) {

		static Result error(EvalQuestion question, String error) {
			return new Result(question, null, error, List.of(), false, false);
		}

		boolean passed() {
			boolean factsComplete = factsFound.size() == question.keyFacts().size();
			return error == null && refusalCorrect && (!question.answerable() || (factsComplete && pageCited));
		}

	}

}
