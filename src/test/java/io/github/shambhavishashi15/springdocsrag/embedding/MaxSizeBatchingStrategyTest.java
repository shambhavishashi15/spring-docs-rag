package io.github.shambhavishashi15.springdocsrag.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;

class MaxSizeBatchingStrategyTest {

	// Stands in for token-based batching: everything in one batch.
	private static final BatchingStrategy SINGLE_BATCH = List::of;

	@Test
	void splitsBatchesLargerThanTheMaxSize() {
		List<Document> documents = documents(250);
		List<List<Document>> batches = new MaxSizeBatchingStrategy(SINGLE_BATCH, 100).batch(documents);
		assertThat(batches).extracting(List::size).containsExactly(100, 100, 50);
		assertThat(batches.stream().flatMap(List::stream).toList()).isEqualTo(documents);
	}

	@Test
	void keepsTheDelegatesBatchesWhenTheyAreSmallEnough() {
		List<Document> documents = documents(30);
		BatchingStrategy twoBatches = docs -> List.of(docs.subList(0, 10), docs.subList(10, 30));
		assertThat(new MaxSizeBatchingStrategy(twoBatches, 100).batch(documents))
				.extracting(List::size).containsExactly(10, 20);
	}

	@Test
	void rejectsNonPositiveMaxSize() {
		assertThatIllegalArgumentException().isThrownBy(() -> new MaxSizeBatchingStrategy(SINGLE_BATCH, 0));
	}

	private static List<Document> documents(int count) {
		return IntStream.range(0, count).mapToObj(i -> new Document("chunk " + i)).toList();
	}

}
