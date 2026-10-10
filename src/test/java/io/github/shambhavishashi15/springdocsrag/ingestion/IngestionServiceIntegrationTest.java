package io.github.shambhavishashi15.springdocsrag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.shambhavishashi15.springdocsrag.MockedAiIntegrationTest;
import io.github.shambhavishashi15.springdocsrag.embedding.EmbeddingQuotaExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Ingestion against a real pgvector with the embedding model mocked: chunks, metadata and idempotent re-runs.
class IngestionServiceIntegrationTest extends MockedAiIntegrationTest {

	// 3 sections on the profiles page (intro + 2) and 2 on the transactions page, each short enough for one chunk.
	private static final int TOTAL_CHUNKS = 5;

	@Autowired
	private IngestionService ingestionService;

	@Test
	void storesEveryChunkWithCitationMetadata() {
		assertThat(ingestionService.ingest()).isEqualTo(new IngestionResult(2, 0, TOTAL_CHUNKS));

		List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
				select metadata->>'pageTitle' as page, metadata->>'section' as section, metadata->>'url' as url
				from vector_store where metadata->>'source' = ? order by metadata->>'url'""", PROFILES_URL);
		assertThat(rows).extracting(row -> row.get("page"), row -> row.get("section"), row -> row.get("url"))
				.containsExactly(
						tuple("Spring Boot: Profiles", "Profiles", PROFILES_URL),
						tuple("Spring Boot: Profiles", "Adding Active Profiles", PROFILES_URL + "#profiles.adding-active-profiles"),
						tuple("Spring Boot: Profiles", "Profile Groups", PROFILES_URL + "#profiles.groups"));
		assertThat(jdbcTemplate.queryForObject("select content from vector_store where metadata->>'url' = ?",
				String.class, PROFILES_URL + "#profiles.groups"))
				.startsWith("Spring Boot: Profiles > Profile Groups\n\n");
	}

	@Test
	void skipsUnchangedPagesWithoutCallingTheEmbeddingModel() {
		ingestionService.ingest();
		clearInvocations(embeddingModel);

		assertThat(ingestionService.ingest()).isEqualTo(new IngestionResult(0, 2, 0));
		then(embeddingModel).should(never()).call(any());
		assertThat(chunkCount()).isEqualTo(TOTAL_CHUNKS);
	}

	@Test
	void reingestsOnlyTheChangedPageAndReplacesItsChunks() throws IOException {
		ingestionService.ingest();
		editProfilesPage();

		assertThat(ingestionService.ingest()).isEqualTo(new IngestionResult(1, 1, 3));
		assertThat(chunkCount()).isEqualTo(TOTAL_CHUNKS);
		assertThat(contents()).anyMatch(text -> text.contains("SPRING_PROFILES_ACTIVE"))
				.noneMatch(text -> text.contains("spring.profiles.active property"));
	}

	@Test
	void keepsAPagesOldChunksWhenEmbeddingFails() throws IOException {
		ingestionService.ingest();
		editProfilesPage();
		doThrow(new EmbeddingQuotaExceededException(Duration.ofHours(8), "Quota exceeded."))
				.when(embeddingModel).call(any());

		assertThatExceptionOfType(EmbeddingQuotaExceededException.class).isThrownBy(ingestionService::ingest);
		// The delete of the old chunks was rolled back with the failed add.
		assertThat(chunkCount()).isEqualTo(TOTAL_CHUNKS);
		assertThat(contents()).anyMatch(text -> text.contains("spring.profiles.active property"));
	}

	private void editProfilesPage() throws IOException {
		Path page = DOCS_DIR.resolve("profiles.html");
		Files.writeString(page, Files.readString(page)
				.replace("the spring.profiles.active property", "the SPRING_PROFILES_ACTIVE environment variable"));
	}

	private List<String> contents() {
		return jdbcTemplate.queryForList("select content from vector_store", String.class);
	}

}
