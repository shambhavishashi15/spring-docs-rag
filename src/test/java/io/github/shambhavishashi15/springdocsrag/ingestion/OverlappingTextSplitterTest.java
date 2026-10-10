package io.github.shambhavishashi15.springdocsrag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class OverlappingTextSplitterTest {

	private static final int CHUNK_SIZE = 100;

	private static final int CHUNK_OVERLAP = 20;

	private final OverlappingTextSplitter splitter =
			new OverlappingTextSplitter(new IngestionProperties(CHUNK_SIZE, CHUNK_OVERLAP));

	// Unique words make overlaps and cut words detectable; sentences and paragraphs give break points.
	private static final String TEXT = buildText(300);

	@Test
	void keepsShortTextAsOneChunk() {
		assertThat(splitter.splitText("Short section text.")).containsExactly("Short section text.");
	}

	@Test
	void keepsEveryChunkWithinChunkSize() {
		assertThat(splitter.splitText(TEXT)).hasSizeGreaterThan(10)
				.allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(CHUNK_SIZE));
	}

	@Test
	void sharesOverlapBetweenConsecutiveChunks() {
		List<String> chunks = splitter.splitText(TEXT);
		for (int i = 1; i < chunks.size(); i++) {
			int overlap = overlapLength(chunks.get(i - 1), chunks.get(i));
			assertThat(overlap).as("overlap between chunk %d and %d", i - 1, i).isBetween(1, CHUNK_OVERLAP);
		}
	}

	@Test
	void neverCutsAWordInHalf() {
		List<String> originalWords = List.of(TEXT.split("\\s+"));
		for (String chunk : splitter.splitText(TEXT)) {
			assertThat(originalWords).containsAll(List.of(chunk.split("\\s+")));
		}
	}

	@Test
	void losesNoTextOnceOverlapsAreRemoved() {
		List<String> chunks = splitter.splitText(TEXT);
		StringBuilder rebuilt = new StringBuilder(chunks.get(0));
		for (int i = 1; i < chunks.size(); i++) {
			rebuilt.append(' ').append(chunks.get(i).substring(overlapLength(chunks.get(i - 1), chunks.get(i))));
		}
		assertThat(List.of(rebuilt.toString().split("\\s+"))).containsExactly(TEXT.split("\\s+"));
	}

	@Test
	void prefersParagraphBreaks() {
		String first = "First paragraph " + "a".repeat(50) + ".";
		String second = "Second paragraph " + "b".repeat(50) + ".";
		assertThat(splitter.splitText(first + "\n\n" + second).get(0)).isEqualTo(first);
	}

	@Test
	void copiesMetadataAndAddsChunkIndexWhenSplittingADocument() {
		Document section = Document.builder().text(TEXT).metadata("source", "https://example.com/page").build();
		List<Document> chunks = splitter.split(section);
		assertThat(chunks).hasSizeGreaterThan(1);
		for (int i = 0; i < chunks.size(); i++) {
			assertThat(chunks.get(i).getMetadata())
					.containsEntry("source", "https://example.com/page")
					.containsEntry("chunk_index", i);
		}
	}

	@Test
	void rejectsOverlapOfHalfTheChunkSizeOrMore() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new OverlappingTextSplitter(new IngestionProperties(100, 50)));
	}

	@Test
	void rejectsNonPositiveChunkSize() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new OverlappingTextSplitter(new IngestionProperties(0, 0)));
	}

	// Length of the longest suffix of previous that is also a prefix of next.
	private static int overlapLength(String previous, String next) {
		for (int length = Math.min(previous.length(), next.length()); length > 0; length--) {
			if (previous.endsWith(next.substring(0, length))) {
				return length;
			}
		}
		return 0;
	}

	private static String buildText(int wordCount) {
		List<String> parts = new ArrayList<>();
		for (int i = 0; i < wordCount; i++) {
			String separator = (i % 20 == 19) ? ".\n\n" : (i % 7 == 6) ? ". " : " ";
			parts.add("w" + i + separator);
		}
		return String.join("", parts).strip();
	}

}
