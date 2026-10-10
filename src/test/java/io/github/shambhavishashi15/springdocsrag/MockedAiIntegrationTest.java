package io.github.shambhavishashi15.springdocsrag;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.Answers;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// The whole app with a real pgvector (Testcontainers), but no Gemini: the EmbeddingModel and ChatModel
// beans are replaced with Mockito mocks. Subclasses share one cached context.
@SpringBootTest(properties = "GEMINI_API_KEY=test-dummy-key")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
public abstract class MockedAiIntegrationTest {

	protected static final String PROFILES_URL = "https://docs.example.org/boot/profiles.html";

	private static final List<String> FIXTURES = List.of("manifest.csv", "profiles.html", "transactions.html");

	private static final int DIMENSIONS = 768;

	// The real docs aren't in the repo, so ingestion reads small fixture pages from a temp copy that tests may edit.
	protected static final Path DOCS_DIR = createTempDir();

	// CALLS_REAL_METHODS: EmbeddingModel's default methods (embed(...), used by PgVectorStore) delegate to
	// call(), which each test stubs below.
	@MockitoBean(answers = Answers.CALLS_REAL_METHODS)
	protected EmbeddingModel embeddingModel;

	@MockitoBean
	protected ChatModel chatModel;

	@Autowired
	protected JdbcTemplate jdbcTemplate;

	@DynamicPropertySource
	static void docsDir(DynamicPropertyRegistry registry) {
		registry.add("app.ingestion.docs-dir", DOCS_DIR::toString);
	}

	@BeforeEach
	void resetDocsAndModels() throws IOException {
		jdbcTemplate.execute("truncate vector_store");
		for (String fixture : FIXTURES) {
			try (InputStream in = getClass().getResourceAsStream("/pipeline/docs/" + fixture)) {
				Files.copy(in, DOCS_DIR.resolve(fixture), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		// doAnswer: with CALLS_REAL_METHODS, when(mock.call(...)) would invoke the abstract method while stubbing.
		doAnswer(invocation -> bagOfWords(invocation.getArgument(0))).when(embeddingModel).call(any());
		// ChatClient starts from the model's default options on every call; a bare mock would return null.
		given(chatModel.getOptions()).willReturn(ChatOptions.builder().build());
	}

	protected int chunkCount() {
		return jdbcTemplate.queryForObject("select count(*) from vector_store", Integer.class);
	}

	// Deterministic stand-in for Gemini: each word (4+ letters) adds 1 to a hashed dimension, then the vector
	// is normalized. Texts sharing words get a high cosine similarity, so retrieval behaves meaningfully.
	private static EmbeddingResponse bagOfWords(EmbeddingRequest request) {
		List<Embedding> embeddings = new ArrayList<>();
		for (String text : request.getInstructions()) {
			float[] vector = new float[DIMENSIONS];
			for (String word : text.toLowerCase(Locale.ROOT).split("\\W+")) {
				if (word.length() >= 4) {
					vector[Math.floorMod(word.hashCode(), DIMENSIONS)]++;
				}
			}
			embeddings.add(new Embedding(normalize(vector), embeddings.size()));
		}
		return new EmbeddingResponse(embeddings);
	}

	private static float[] normalize(float[] vector) {
		double sumOfSquares = 0;
		for (float value : vector) {
			sumOfSquares += value * value;
		}
		if (sumOfSquares == 0) {
			vector[0] = 1; // a text without words still needs a valid (non-zero) vector
			return vector;
		}
		float length = (float) Math.sqrt(sumOfSquares);
		for (int i = 0; i < vector.length; i++) {
			vector[i] /= length;
		}
		return vector;
	}

	private static Path createTempDir() {
		try {
			return Files.createTempDirectory("spring-docs-rag-test-docs");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
