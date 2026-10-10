package io.github.shambhavishashi15.springdocsrag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

// Dummy key: the chat and embedding clients need a credential to start, but tests never call the real Gemini API.
// Inline test properties take precedence over a real GEMINI_API_KEY environment variable.
@SpringBootTest(properties = "GEMINI_API_KEY=test-dummy-key")
@Import(TestcontainersConfiguration.class)
class SpringDocsRagApplicationTests {

	@Autowired
	private ApplicationContext context;

	@Test
	void contextLoads() {
	}

	@Test
	void usesGoogleGenAiAsTheOnlyEmbeddingModel() {
		assertThat(context.getBeansOfType(EmbeddingModel.class)).hasSize(1)
				.allSatisfy((name, model) -> assertThat(model).isInstanceOf(GoogleGenAiTextEmbeddingModel.class));
	}

}
