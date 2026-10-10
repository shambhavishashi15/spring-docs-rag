package io.github.shambhavishashi15.springdocsrag.ingestion;

public record IngestionResult(int pagesIngested, int pagesSkipped, int chunksStored) {
}
