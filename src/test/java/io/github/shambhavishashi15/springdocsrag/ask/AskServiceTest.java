package io.github.shambhavishashi15.springdocsrag.ask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

class AskServiceTest {

	private static final String PROFILES_URL = "https://docs.spring.io/spring-boot/reference/features/profiles.html";

	private final VectorStore vectorStore = mock();

	private final ChatModel chatModel = mock();

	private final AskService askService =
			new AskService(vectorStore, ChatClient.builder(chatModel), new AskProperties(6, 0.0));

	// ChatClient starts from the model's default options on every call; a bare mock would return null.
	@BeforeEach
	void defaultChatOptions() {
		given(chatModel.getOptions()).willReturn(ChatOptions.builder().build());
	}

	@Test
	void refusesWithoutCallingTheModelWhenNothingIsRetrieved() {
		given(vectorStore.similaritySearch(any(SearchRequest.class))).willReturn(List.of());

		AskResponse response = askService.ask("How do I bake bread?");

		assertThat(response).isEqualTo(new AskResponse(AskService.NOT_IN_DOCS, false, List.of()));
		then(chatModel).shouldHaveNoInteractions();
	}

	@Test
	void searchesWithTheQuestionAndConfiguredSettings() {
		given(vectorStore.similaritySearch(any(SearchRequest.class))).willReturn(List.of());

		askService.ask("How do I activate a profile?");

		ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
		then(vectorStore).should().similaritySearch(request.capture());
		assertThat(request.getValue().getQuery()).isEqualTo("How do I activate a profile?");
		assertThat(request.getValue().getTopK()).isEqualTo(6);
		assertThat(request.getValue().getSimilarityThreshold()).isEqualTo(0.0);
	}

	@Test
	void numbersSourcesBySectionAndSendsThemWithTheQuestion() {
		given(vectorStore.similaritySearch(any(SearchRequest.class))).willReturn(List.of(
				chunk("Profiles > Adding Active Profiles\n\nUse spring.profiles.active.", "#adding-active-profiles"),
				chunk("Profiles > Profile Groups\n\nGroups bundle profiles.", "#groups"),
				// Second chunk of the first section: joins source [1] instead of becoming [3].
				chunk("Profiles > Adding Active Profiles\n\nOr the {code} attribute.", "#adding-active-profiles")));
		givenModelAnswers("Set spring.profiles.active [1].");

		askService.ask("How do I activate a profile?");

		String userMessage = sentPrompt().getUserMessage().getText();
		assertThat(userMessage)
				.contains("[1]\nProfiles > Adding Active Profiles\n\nUse spring.profiles.active.\n\n"
						+ "Profiles > Adding Active Profiles\n\nOr the {code} attribute.")
				.contains("[2]\nProfiles > Profile Groups")
				.doesNotContain("[3]")
				.endsWith("Question: How do I activate a profile?");
		assertThat(sentPrompt().getSystemMessage().getText()).contains("only the numbered sources", AskService.NOT_IN_DOCS);
	}

	@Test
	void citesOnlyTheSourcesTheAnswerReferences() {
		given(vectorStore.similaritySearch(any(SearchRequest.class))).willReturn(List.of(
				chunk("Profiles > Adding Active Profiles\n\n...", "#adding-active-profiles"),
				chunk("Profiles > Profile Groups\n\n...", "#groups"),
				chunk("Profiles > Programmatically Setting Profiles\n\n...", "#programmatic")));
		// [2] is never cited; [7] is not a source and is ignored.
		givenModelAnswers("Set spring.profiles.active [1, 3]. Or call setAdditionalProfiles [3][7].");

		AskResponse response = askService.ask("How do I activate a profile?");

		assertThat(response.answered()).isTrue();
		assertThat(response.answer()).isEqualTo("Set spring.profiles.active [1, 3]. Or call setAdditionalProfiles [3][7].");
		assertThat(response.citations()).containsExactly(
				new Citation(1, "Spring Boot: Profiles", "Section #adding-active-profiles", PROFILES_URL + "#adding-active-profiles"),
				new Citation(3, "Spring Boot: Profiles", "Section #programmatic", PROFILES_URL + "#programmatic"));
	}

	@Test
	void returnsNoCitationsWhenTheModelSaysTheDocsDontContainTheAnswer() {
		given(vectorStore.similaritySearch(any(SearchRequest.class))).willReturn(List.of(
				chunk("Profiles > Adding Active Profiles\n\n...", "#adding-active-profiles")));
		givenModelAnswers(AskService.NOT_IN_DOCS);

		AskResponse response = askService.ask("How do I bake bread?");

		assertThat(response).isEqualTo(new AskResponse(AskService.NOT_IN_DOCS, false, List.of()));
	}

	private static Document chunk(String text, String anchor) {
		return Document.builder()
				.text(text)
				.metadata(Map.of(
						"source", PROFILES_URL,
						"pageTitle", "Spring Boot: Profiles",
						"section", "Section " + anchor,
						"url", PROFILES_URL + anchor))
				.build();
	}

	private void givenModelAnswers(String answer) {
		given(chatModel.call(any(Prompt.class)))
				.willReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(answer)))));
	}

	private Prompt sentPrompt() {
		ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
		then(chatModel).should().call(prompt.capture());
		return prompt.getValue();
	}

}
