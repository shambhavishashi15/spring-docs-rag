package io.github.shambhavishashi15.springdocsrag.ask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

// Answers a question from the ingested docs only: retrieve chunks, number them by section,
// let the model answer with [n] citations, and map the cited numbers back to page + section.
@Service
public class AskService {

	static final String NOT_IN_DOCS = "I don't know based on the provided Spring documentation.";

	// ChatClient renders prompts as templates, so they contain no braces other than the parameters below.
	private static final String SYSTEM_PROMPT = """
			You answer questions about Spring using only the numbered sources in the user message.
			Rules:
			- Use only facts stated in the sources. Never add outside knowledge, even if you know the answer.
			- Every sentence that states a fact must end with the number of its source in square brackets, like [1] or [1][3]. This applies to one-sentence answers too. An answer without [n] markers is invalid.
			- If the sources do not contain the answer, reply with exactly this sentence and nothing else: %s
			- Be concise. Put code in Markdown code blocks.
			""".formatted(NOT_IN_DOCS);

	// Sources and question are template parameters, so braces in code samples are never parsed as template syntax.
	private static final String USER_PROMPT = """
			Sources:

			{sources}

			Question: {question}""";

	// "[2]", or a list like "[1, 3]". At most 3 digits, so parsing can't overflow.
	private static final Pattern CITATION_MARKER = Pattern.compile("\\[(\\d{1,3}(?:\\s*,\\s*\\d{1,3})*)]");

	private final VectorStore vectorStore;

	private final ChatClient chatClient;

	private final AskProperties properties;

	public AskService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder, AskProperties properties) {
		this.vectorStore = vectorStore;
		this.chatClient = chatClientBuilder.build();
		this.properties = properties;
	}

	public AskResponse ask(String question) {
		List<Document> chunks = vectorStore.similaritySearch(SearchRequest.builder()
				.query(question)
				.topK(properties.topK())
				.similarityThreshold(properties.similarityThreshold())
				.build());
		if (chunks.isEmpty()) {
			return notInDocs();
		}
		List<Source> sources = groupBySection(chunks);
		String answer = chatClient.prompt()
				.system(SYSTEM_PROMPT)
				.user(user -> user.text(USER_PROMPT)
						.param("sources", format(sources))
						.param("question", question))
				.call()
				.content();
		if (answer == null || answer.contains(NOT_IN_DOCS)) {
			return notInDocs();
		}
		return new AskResponse(answer.strip(), true, citations(answer, sources));
	}

	// One source per section (deep-link URL), in rank order, so a citation is exactly "page + section"
	// even when overlapping chunks of the same section are retrieved.
	private static List<Source> groupBySection(List<Document> chunks) {
		Map<String, List<Document>> bySection = new LinkedHashMap<>();
		for (Document chunk : chunks) {
			bySection.computeIfAbsent((String) chunk.getMetadata().get("url"), url -> new ArrayList<>()).add(chunk);
		}
		List<Source> sources = new ArrayList<>();
		for (List<Document> sectionChunks : bySection.values()) {
			sources.add(new Source(sources.size() + 1, sectionChunks));
		}
		return sources;
	}

	// Each chunk's text already starts with "page title > section", so the model sees where it comes from.
	private static String format(List<Source> sources) {
		StringBuilder out = new StringBuilder();
		for (Source source : sources) {
			out.append('[').append(source.id()).append("]\n");
			for (Document chunk : source.chunks()) {
				out.append(chunk.getText()).append("\n\n");
			}
		}
		return out.toString().strip();
	}

	// Only the sources the answer cites; numbers that weren't a source are ignored.
	private static List<Citation> citations(String answer, List<Source> sources) {
		Set<Integer> cited = new TreeSet<>();
		Matcher matcher = CITATION_MARKER.matcher(answer);
		while (matcher.find()) {
			for (String id : matcher.group(1).split(",")) {
				cited.add(Integer.parseInt(id.strip()));
			}
		}
		return sources.stream()
				.filter(source -> cited.contains(source.id()))
				.map(Source::toCitation)
				.toList();
	}

	private static AskResponse notInDocs() {
		return new AskResponse(NOT_IN_DOCS, false, List.of());
	}

	private record Source(int id, List<Document> chunks) {

		Citation toCitation() {
			Map<String, Object> metadata = chunks.get(0).getMetadata();
			return new Citation(id, (String) metadata.get("pageTitle"), (String) metadata.get("section"),
					(String) metadata.get("url"));
		}

	}

}
