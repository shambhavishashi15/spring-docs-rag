# spring-docs-rag

A Retrieval-Augmented Generation (RAG) service that answers questions about Spring using a set of Spring documentation pages. Answers are grounded only in retrieved chunks and cite their sources (page + section). Built with Java 21, Spring Boot, Spring AI, pgvector and Google Gemini.

**Status:** work in progress — milestone 3 (RAG endpoint).

## Prerequisites

- Java 21 (the Maven wrapper downloads Maven itself)
- Docker (for Postgres + pgvector, and for Testcontainers in the tests)
- A Google Gemini API key from [Google AI Studio](https://aistudio.google.com/apikey); the free tier is enough

## How to run

1. Download the Spring docs pages listed in `docs/manifest.csv`:

   ```bash
   scripts/fetch-docs.sh
   ```

2. Start Postgres with the pgvector extension:

   ```bash
   docker compose up -d
   ```

3. Set your API key (it is read from the environment and never stored in the repo) and start the app:

   ```bash
   export GEMINI_API_KEY=your-key
   ./mvnw spring-boot:run
   ```

4. Ingest the docs (parse, chunk, embed, store in pgvector):

   ```bash
   curl -X POST localhost:8080/api/v1/ingest
   ```

   It returns a summary such as `{"pagesIngested":15,"pagesSkipped":0,"chunksStored":448}`. A first run on the free tier can take a few minutes because the per-minute embedding quota is waited out with exponential backoff; if the daily quota is used up, the call fails fast instead.

5. Ask a question:

   ```bash
   curl -s localhost:8080/api/v1/ask -H 'Content-Type: application/json' \
     -d '{"question": "How do I activate a Spring profile?"}'
   ```

   The answer cites its sources as `[n]` markers, and `citations` maps each one to its page, section and deep link. If the docs don't contain the answer, `answered` is `false` and the answer says so. Errors are returned as RFC 9457 `ProblemDetail` JSON (400 for invalid requests, 429 when Gemini's quota is exhausted, with `Retry-After`).

   Or try the API in Swagger UI at <http://localhost:8080/swagger-ui.html>.

To run the tests: `./mvnw test` (needs Docker; the tests never call the Gemini API).

## Idempotent ingestion

Each page gets a SHA-256 fingerprint of its parsed sections plus the ingestion settings (chunk size, overlap, embedding model and dimensions), stored in every chunk's metadata. On a re-run, unchanged pages are skipped without any API calls, and changed pages have all their chunks replaced in one transaction, so there are never duplicates or stale chunks.

## Spring documentation

The docs HTML is not redistributed in this repository: the pages are "Copyright Broadcom. All Rights Reserved.", so only their URLs (`docs/manifest.csv`) are committed and `scripts/fetch-docs.sh` downloads them from [docs.spring.io](https://docs.spring.io).
