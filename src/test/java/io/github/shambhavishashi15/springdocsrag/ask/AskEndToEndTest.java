package io.github.shambhavishashi15.springdocsrag.ask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;

import io.github.shambhavishashi15.springdocsrag.MockedAiIntegrationTest;
import io.github.shambhavishashi15.springdocsrag.ingestion.IngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

// POST /api/v1/ask through the whole app: real ingestion and pgvector retrieval, mocked Gemini models.
class AskEndToEndTest extends MockedAiIntegrationTest {

	@Autowired
	private IngestionService ingestionService;

	@Autowired
	private MockMvcTester mvc;

	@BeforeEach
	void ingestFixtures() {
		ingestionService.ingest();
	}

	@Test
	void sendsTheMostSimilarSectionAsSourceOneAndReturnsItsCitation() {
		givenModelAnswers("Set the spring.profiles.active property [1].");

		assertThat(ask("Which property sets the active profiles?"))
				.hasStatusOk()
				.bodyJson().isLenientlyEqualTo("""
						{"answer": "Set the spring.profiles.active property [1].", "answered": true,
						 "citations": [{"id": 1, "page": "Spring Boot: Profiles", "section": "Adding Active Profiles",
						                "url": "%s#profiles.adding-active-profiles"}]}""".formatted(PROFILES_URL));

		ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
		then(chatModel).should().call(prompt.capture());
		assertThat(prompt.getValue().getUserMessage().getText())
				.startsWith("Sources:\n\n[1]\nSpring Boot: Profiles > Adding Active Profiles\n\n")
				.endsWith("Question: Which property sets the active profiles?");
	}

	@Test
	void reportsWhenTheDocsDontContainTheAnswer() {
		givenModelAnswers(AskService.NOT_IN_DOCS);

		assertThat(ask("How do I configure OAuth2 login?"))
				.hasStatusOk()
				.bodyJson().isLenientlyEqualTo("""
						{"answer": "%s", "answered": false, "citations": []}""".formatted(AskService.NOT_IN_DOCS));
	}

	private void givenModelAnswers(String answer) {
		given(chatModel.call(any(Prompt.class)))
				.willReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(answer)))));
	}

	private MvcTestResult ask(String question) {
		return mvc.post().uri("/api/v1/ask").contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\": \"" + question + "\"}").exchange();
	}

}
