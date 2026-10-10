package io.github.shambhavishashi15.springdocsrag;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.shambhavishashi15.springdocsrag.embedding.MaxSizeBatchingStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// Dummy key: the chat and embedding clients need a credential to start, but tests never call the real Gemini API.
// Inline test properties take precedence over a real GEMINI_API_KEY environment variable.
@SpringBootTest(properties = "GEMINI_API_KEY=test-dummy-key")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class SpringDocsRagApplicationTests {

	@Autowired
	private ApplicationContext context;

	@Autowired
	private MockMvcTester mvc;

	@Test
	void contextLoads() {
	}

	@Test
	void usesGoogleGenAiAsTheOnlyEmbeddingModel() {
		assertThat(context.getBeansOfType(EmbeddingModel.class)).hasSize(1)
				.allSatisfy((name, model) -> assertThat(model).isInstanceOf(GoogleGenAiTextEmbeddingModel.class));
	}

	@Test
	void capsPgVectorStoreBatchesAtGeminisLimit() {
		assertThat(context.getBean(BatchingStrategy.class)).isInstanceOf(MaxSizeBatchingStrategy.class);
	}

	@Test
	void servesTheOpenApiSpecForSwaggerUi() {
		assertThat(mvc.get().uri("/v3/api-docs")).hasStatusOk()
				.bodyJson().extractingPath("$.paths").asMap()
				.containsKeys("/api/v1/ask", "/api/v1/ingest", "/api/v1/chat");
	}

}
