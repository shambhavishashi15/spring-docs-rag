package io.github.shambhavishashi15.springdocsrag.ingestion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// Ingests the pages in docs/manifest.csv. Re-runs are idempotent: unchanged pages are skipped,
// changed pages (or changed ingestion settings) replace all of that page's chunks.
@Service
public class IngestionService {

	private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

	private final HtmlSectionParser parser;

	private final OverlappingTextSplitter splitter;

	private final VectorStore vectorStore;

	private final JdbcTemplate jdbcTemplate;

	private final TransactionTemplate transactionTemplate;

	private final IngestionProperties properties;

	private final String embeddingModel;

	private final int embeddingDimensions;

	public IngestionService(HtmlSectionParser parser, OverlappingTextSplitter splitter, VectorStore vectorStore,
			JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate, IngestionProperties properties,
			@Value("${spring.ai.google.genai.embedding.text.options.model}") String embeddingModel,
			@Value("${spring.ai.google.genai.embedding.text.options.dimensions}") int embeddingDimensions) {
		this.parser = parser;
		this.splitter = splitter;
		this.vectorStore = vectorStore;
		this.jdbcTemplate = jdbcTemplate;
		this.transactionTemplate = transactionTemplate;
		this.properties = properties;
		this.embeddingModel = embeddingModel;
		this.embeddingDimensions = embeddingDimensions;
	}

	// synchronized: two concurrent runs could both see a page as missing and store it twice.
	public synchronized IngestionResult ingest() {
		int pagesIngested = 0;
		int pagesSkipped = 0;
		int chunksStored = 0;
		for (ManifestEntry page : readManifest()) {
			List<Section> sections = parser.parse(readHtml(page), page.url(), page.title());
			String fingerprint = fingerprint(page.title(), sections);
			if (isAlreadyStored(page.url(), fingerprint)) {
				log.info("Skipped unchanged page {}", page.url());
				pagesSkipped++;
				continue;
			}
			List<Document> chunks = toChunks(sections, fingerprint);
			// One transaction per page: if embedding fails, the delete rolls back and the old chunks stay.
			transactionTemplate.executeWithoutResult(status -> {
				vectorStore.delete(new FilterExpressionBuilder().eq("source", page.url()).build());
				vectorStore.add(chunks);
			});
			log.info("Ingested {} ({} chunks)", page.url(), chunks.size());
			pagesIngested++;
			chunksStored += chunks.size();
		}
		log.info("Ingestion done: {} pages ingested, {} skipped unchanged, {} chunks stored",
				pagesIngested, pagesSkipped, chunksStored);
		return new IngestionResult(pagesIngested, pagesSkipped, chunksStored);
	}

	private List<Document> toChunks(List<Section> sections, String fingerprint) {
		List<Document> chunks = new ArrayList<>();
		for (Section section : sections) {
			Document sectionDocument = Document.builder()
					.text(section.text())
					.metadata(Map.of(
							"source", section.pageUrl(),
							"pageTitle", section.pageTitle(),
							"section", section.heading(),
							"url", section.url(),
							"fingerprint", fingerprint))
					.build();
			// The prefix gives every chunk (not just a section's first) its page and section context.
			String prefix = section.pageTitle() + " > " + section.heading() + "\n\n";
			for (Document chunk : splitter.split(sectionDocument)) {
				chunks.add(chunk.mutate().text(prefix + chunk.getText()).build());
			}
		}
		return chunks;
	}

	// Covers everything that shapes the stored chunks, so changing any of it re-ingests the page.
	private String fingerprint(String pageTitle, List<Section> sections) {
		StringBuilder input = new StringBuilder()
				.append(properties.chunkSize()).append('|')
				.append(properties.chunkOverlap()).append('|')
				.append(embeddingModel).append('|')
				.append(embeddingDimensions).append('|')
				.append(pageTitle);
		for (Section section : sections) {
			input.append('\u0000').append(section.heading())
					.append('\u0000').append(section.anchor())
					.append('\u0000').append(section.text());
		}
		return HexFormat.of().formatHex(sha256(input.toString()));
	}

	// VectorStore can only search by similarity, so look up the metadata with plain SQL.
	private boolean isAlreadyStored(String source, String fingerprint) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
				"select exists(select 1 from vector_store where metadata->>'source' = ? and metadata->>'fingerprint' = ?)",
				Boolean.class, source, fingerprint));
	}

	private List<ManifestEntry> readManifest() {
		try {
			return Files.readAllLines(properties.docsDir().resolve("manifest.csv")).stream()
					.skip(1) // header
					.filter(line -> !line.isBlank())
					.map(line -> line.split(",", 3))
					.map(fields -> new ManifestEntry(fields[0], fields[1], fields[2]))
					.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read docs manifest", ex);
		}
	}

	private String readHtml(ManifestEntry page) {
		Path file = properties.docsDir().resolve(page.file() + ".html");
		if (!Files.exists(file)) {
			throw new IllegalStateException(file + " is missing; run scripts/fetch-docs.sh first");
		}
		try {
			return Files.readString(file);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read " + file, ex);
		}
	}

	private static byte[] sha256(String input) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is always available in the JDK", ex);
		}
	}

	private record ManifestEntry(String file, String url, String title) {
	}

}
