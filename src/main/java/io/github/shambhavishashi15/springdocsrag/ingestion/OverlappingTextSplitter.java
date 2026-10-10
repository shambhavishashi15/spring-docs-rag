package io.github.shambhavishashi15.springdocsrag.ingestion;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.stereotype.Component;

// Character-based splitter with overlap. Spring AI's TokenTextSplitter has no overlap option.
// TextSplitter.split(Document) copies metadata to each chunk and adds chunk_index/total_chunks.
@Component
public class OverlappingTextSplitter extends TextSplitter {

	// Break points from best to worst: paragraph, line (e.g. in code), sentence, word.
	private static final List<String> BREAKS = List.of("\n\n", "\n", ". ", " ");

	private final int chunkSize;

	private final int chunkOverlap;

	public OverlappingTextSplitter(IngestionProperties properties) {
		if (properties.chunkSize() <= 0) {
			throw new IllegalArgumentException("chunk-size must be positive");
		}
		// Breaks land in the second half of a window, so overlap must stay below half to always move forward.
		if (properties.chunkOverlap() < 0 || properties.chunkOverlap() >= properties.chunkSize() / 2) {
			throw new IllegalArgumentException("chunk-overlap must be at least 0 and less than half of chunk-size");
		}
		this.chunkSize = properties.chunkSize();
		this.chunkOverlap = properties.chunkOverlap();
	}

	@Override
	protected List<String> splitText(String text) {
		List<String> chunks = new ArrayList<>();
		int start = 0;
		while (start < text.length()) {
			int end = Math.min(start + chunkSize, text.length());
			if (end < text.length()) {
				end = findBreak(text, start, end);
			}
			String chunk = text.substring(start, end).strip();
			if (!chunk.isEmpty()) {
				chunks.add(chunk);
			}
			if (end == text.length()) {
				break;
			}
			start = startOfNextWord(text, end - chunkOverlap, end);
		}
		return chunks;
	}

	// Last break point in the second half of [start, end), or end itself if there is none.
	private int findBreak(String text, int start, int end) {
		int earliest = start + chunkSize / 2;
		for (String separator : BREAKS) {
			int index = text.lastIndexOf(separator, end - separator.length());
			if (index >= earliest) {
				return index + separator.length();
			}
		}
		return end;
	}

	// Moves from position to the start of the next word so a chunk never begins mid-word.
	private static int startOfNextWord(String text, int position, int limit) {
		int index = position;
		if (index > 0 && !Character.isWhitespace(text.charAt(index - 1))) {
			while (index < limit && !Character.isWhitespace(text.charAt(index))) {
				index++;
			}
		}
		while (index < limit && Character.isWhitespace(text.charAt(index))) {
			index++;
		}
		return index;
	}

}
