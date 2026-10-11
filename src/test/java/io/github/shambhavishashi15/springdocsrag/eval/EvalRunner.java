package io.github.shambhavishashi15.springdocsrag.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

// Scores POST /api/v1/ask on a running app against eval/questions.json and writes eval/report.md.
// It makes real Gemini calls, so ./mvnw verify and CI never run it: its name doesn't match Surefire's default
// *Test patterns, and it is skipped unless eval.baseUrl is set. Run it explicitly against a running app:
//   ./mvnw test -Dtest=EvalRunner -Deval.baseUrl=http://localhost:8080
// Every result is saved to eval/results.json as soon as it arrives, and a re-run resumes from there, so a
// quota stop loses nothing. After changing the prompt or retrieval settings, start over with -Deval.fresh=true.
@EnabledIfSystemProperty(named = "eval.baseUrl", matches = ".+")
class EvalRunner {

	private static final Path QUESTIONS = Path.of("eval/questions.json");

	private static final Path RESULTS = Path.of("eval/results.json");

	private static final Path REPORT = Path.of("eval/report.md");

	private static final int MAX_ATTEMPTS = 3;

	// A longer Retry-After means the daily quota is gone: stop instead of sleeping for hours.
	private static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(2);

	private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

	@Test
	void scoreAnswers() throws Exception {
		String baseUrl = System.getProperty("eval.baseUrl");
		// Pause between questions to stay inside the free tier's per-minute limits.
		Duration delay = Duration.ofSeconds(Long.getLong("eval.delay", 5));
		boolean fresh = Boolean.getBoolean("eval.fresh");
		List<Result> results = run(RestClient.create(baseUrl), baseUrl, QUESTIONS, RESULTS, REPORT, delay, fresh);
		System.out.println(summary(results));
		System.out.println("Report written to " + REPORT.toAbsolutePath());
	}

	static List<Result> run(RestClient client, String baseUrl, Path questionsFile, Path resultsFile, Path reportFile,
			Duration delay, boolean fresh) throws IOException, InterruptedException {
		List<EvalQuestion> questions = JSON.readValue(questionsFile.toFile(), EvalSet.class).questions();
		Map<EvalQuestion, Result> kept = fresh ? Map.of() : keptResults(resultsFile);
		if (!kept.isEmpty()) {
			System.out.printf("Resuming: %d/%d results kept from %s (use -Deval.fresh=true to start over)%n",
					questions.stream().filter(kept::containsKey).count(), questions.size(), resultsFile);
		}

		List<Result> results = new ArrayList<>();
		boolean askedAny = false;
		for (EvalQuestion question : questions) {
			Result previous = kept.get(question);
			if (previous != null) {
				results.add(previous);
				continue;
			}
			if (askedAny) {
				Thread.sleep(delay);
			}
			askedAny = true;
			Result result;
			try {
				result = evaluate(client, question);
			}
			catch (QuotaExhaustedException ex) {
				throw new IllegalStateException(ex.getMessage() + "; " + results.size() + "/" + questions.size()
						+ " results are saved in " + resultsFile + ", re-run after the quota resets to resume", ex);
			}
			System.out.printf("%s %s %s%n", question.id(), result.passed() ? "PASS" : "FAIL", question.question());
			results.add(result);
			save(results, questions.size(), baseUrl, resultsFile, reportFile);
		}
		save(results, questions.size(), baseUrl, resultsFile, reportFile);
		return results;
	}

	// Results worth keeping: answered without an error, for a question that is still worded the same.
	// Matching on the whole EvalQuestion means an edited question or key fact is asked again.
	private static Map<EvalQuestion, Result> keptResults(Path resultsFile) throws IOException {
		if (!Files.exists(resultsFile)) {
			return Map.of();
		}
		List<Result> saved = JSON.readValue(resultsFile.toFile(), new TypeReference<List<Result>>() {
		});
		return saved.stream()
				.filter(result -> result.error() == null)
				.collect(Collectors.toMap(Result::question, Function.identity(), (first, second) -> second));
	}

	// Writes to a temporary file first, so an interrupted run never leaves half a file behind.
	private static void save(List<Result> results, int total, String baseUrl, Path resultsFile, Path reportFile)
			throws IOException {
		writeAtomically(resultsFile, JSON.writeValueAsString(results));
		writeAtomically(reportFile, report(results, total, baseUrl));
	}

	private static void writeAtomically(Path target, String content) throws IOException {
		Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(temporary, content);
		Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
					throw new QuotaExhaustedException("Gemini quota exhausted (Retry-After " + retryAfter.toSeconds() + "s)");
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

	private static String report(List<Result> results, int total, String baseUrl) {
		StringBuilder out = new StringBuilder()
				.append("# Eval report\n\n")
				.append("Run at ").append(Instant.now()).append(" against ").append(baseUrl)
				.append(" with `eval/questions.json`.\n\n");
		if (results.size() < total) {
			out.append("**Incomplete:** ").append(results.size()).append('/').append(total)
					.append(" questions so far; re-run to resume.\n\n");
		}
		out.append(summary(results)).append("\n## Questions\n");
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

	// "passed" is derived from the other fields: written to results.json for readers, ignored when read back.
	@JsonIgnoreProperties(value = "passed", allowGetters = true)
	record Result(EvalQuestion question, AskResponse response, String error, List<String> factsFound,
			boolean pageCited, boolean refusalCorrect) {

		static Result error(EvalQuestion question, String error) {
			return new Result(question, null, error, List.of(), false, false);
		}

		@JsonProperty("passed")
		boolean passed() {
			boolean factsComplete = factsFound.size() == question.keyFacts().size();
			return error == null && refusalCorrect && (!question.answerable() || (factsComplete && pageCited));
		}

	}

	// Gemini asked for a longer wait than the eval should sleep: stop the run (the results so far are saved).
	static class QuotaExhaustedException extends RuntimeException {

		QuotaExhaustedException(String message) {
			super(message);
		}

	}

}
