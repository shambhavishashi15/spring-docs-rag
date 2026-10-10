package io.github.shambhavishashi15.springdocsrag.embedding;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;

// Caps the number of documents per embedding request on top of another strategy
// (e.g. token-based batching, which limits tokens but not the number of inputs).
public class MaxSizeBatchingStrategy implements BatchingStrategy {

	private final BatchingStrategy delegate;

	private final int maxBatchSize;

	public MaxSizeBatchingStrategy(BatchingStrategy delegate, int maxBatchSize) {
		if (maxBatchSize <= 0) {
			throw new IllegalArgumentException("maxBatchSize must be positive");
		}
		this.delegate = delegate;
		this.maxBatchSize = maxBatchSize;
	}

	@Override
	public List<List<Document>> batch(List<Document> documents) {
		List<List<Document>> batches = new ArrayList<>();
		for (List<Document> batch : delegate.batch(documents)) {
			for (int start = 0; start < batch.size(); start += maxBatchSize) {
				batches.add(batch.subList(start, Math.min(start + maxBatchSize, batch.size())));
			}
		}
		return batches;
	}

}
