# Milestone 2 plan

Here's the plan for milestone 2: ingestion. Nothing has been created yet.

**Goal:** turn the Spring docs HTML pages in `/docs` into clean, section-aware chunks with overlap, embed them with Gemini, and store them in pgvector. Re-running ingestion must not create duplicates.

**Out of scope (milestone 3+):** `/api/v1/ask`, retrieval, validation, `@RestControllerAdvice`, Swagger, the eval set, and the full test suite.

## Decisions (checked against the Spring AI 2.0.1 jars in the local Maven cache)

| Item | Choice | Why |
|---|---|---|
| Vector store | `spring-ai-starter-vector-store-pgvector` (managed by the Spring AI BOM) | Auto-configures `PgVectorStore`, a `JdbcTemplate` and the Postgres driver. Alternative: plain JDBC and our own SQL, which is more code for no gain. |
| HTML parsing | `org.jsoup:jsoup` directly | Spring AI's `JsoupDocumentReader` returns one `Document` per page. We need **one per section** to cite page + section, so we walk the headings ourselves. Pulling in the reader only to get Jsoup transitively would be misleading. |
| Chunking | Our own `OverlappingTextSplitter extends TextSplitter` | Spring AI 2.0.1's `TokenTextSplitter` has no overlap setting (its builder only has chunk size, min chars, max chunks, and punctuation options). Extending Spring AI's `TextSplitter` keeps metadata handling idiomatic. |
| Embedding model | `gemini-embedding-001` (override with `GEMINI_EMBEDDING_MODEL`) | Gemini's embedding model on the OpenAI-compatible endpoint. I'll confirm the current model name against the models list in step 2. |
| Embedding dimensions | **768** (`spring.ai.openai.embedding.dimensions`) | The default is 3072, but pgvector's HNSW index supports at most 2000 dimensions for the `vector` type. 768 is one of Gemini's recommended sizes and uses 4× less storage. I'll verify that the OpenAI-compatible endpoint honors `dimensions`. |
| Schema | `spring.ai.vectorstore.pgvector.initialize-schema: true`, with `dimensions: 768` set explicitly | Spring AI creates the `vector_store` table and the HNSW index. Setting the dimensions explicitly avoids an embedding API call at startup just to discover them. Alternative: Flyway. That's cleaner long-term, but it's a new dependency for a single table. |
| Idempotency | Content hash per page: skip unchanged pages, replace changed ones | See step 5. This also saves free-tier quota on re-runs, which matters more than speed. |

## Step 0: Collect the docs into `/docs`
- **Pages:** pick ~25 pages from docs.spring.io that cover topics people actually ask about. Proposed list (I'll confirm each URL before downloading):
  - Spring Framework: IoC container, bean scopes, Java-based configuration, AOP, declarative transactions, Web MVC annotated controllers, Web MVC exception handling, validation
  - Spring Boot: SpringApplication, externalized configuration, profiles, logging, auto-configuration, Actuator endpoints, testing, SQL databases, Docker Compose support
  - Spring Data JPA: repositories and query methods
  - Spring Security: servlet architecture
  - Spring AI: ChatClient, vector databases
- **Manifest:** `docs/manifest.csv` with columns `file,url,title`. A small `scripts/fetch-docs.sh` downloads each URL with `curl` into `docs/<file>.html`. The manifest is the single source of truth for the page URL, which we need for citations.
- **Commit the HTML** so ingestion is reproducible and doesn't depend on the live site. The Spring docs are Apache-2.0 licensed; I'll note that in the README.
- **How you test it:** run `ls docs/*.html | wc -l` to check the page count, then open a file to confirm it's real content and not an error page.

## Step 1: Connect the app to pgvector
- **Dependency:** add `spring-ai-starter-vector-store-pgvector`.
- **`application.yml`:** add the datasource with env overrides and dev defaults that match `compose.yaml`:
  - `url: ${DB_URL:jdbc:postgresql://localhost:5432/springdocsrag}`
  - `username: ${DB_USERNAME:springdocsrag}` and `password: ${DB_PASSWORD:springdocsrag}`
- **pgvector settings:** `index-type: HNSW`, `distance-type: COSINE_DISTANCE`, `dimensions: 768`, `initialize-schema: true`.
- **Impact on the existing `contextLoads` test:** the app now needs a database to start. **Question for you** (see the end of this plan).
- **How you test it:** run `docker compose up -d`, then `./mvnw spring-boot:run`, then run `\d vector_store` in `psql`. It should show the table with a `vector(768)` column and the HNSW index.

## Step 2: Embedding configuration
- **`application.yml`:** `spring.ai.openai.embedding.model: ${GEMINI_EMBEDDING_MODEL:gemini-embedding-001}` and `spring.ai.openai.embedding.dimensions: 768`. Embeddings reuse the base URL and key from milestone 1.
- **Checks:**
  - the exact model name (from Gemini's models list)
  - that 768 is honored: the returned vector length must be 768
- **How you test it:** run a one-off `curl` against Gemini's `/embeddings` endpoint with `dimensions: 768` and check the vector length. No new app code in this step.

## Step 3: HTML → clean sections (`HtmlSectionParser`)
- **Package `ingestion`:** a `record Section(String pageTitle, String url, String heading, String text)`.
- **Parsing:** Jsoup selects the page's main article element and drops noise:
  - `nav`, header/footer, `script`/`style`
  - the table of contents, "edit this page" links, and breadcrumbs
- **Sections:** walk the content, starting a new section at each `h2`/`h3`. The section heading becomes the citation, and an h3 is recorded as `"h2 heading > h3 heading"` so citations stay unambiguous.
- **Code blocks:** keep them as text with their line breaks preserved. Spring answers often depend on code snippets.
- **Selectors:** they depend on the docs' HTML structure (Antora), so I'll inspect a downloaded page before writing them.
- **How you test it:** see the testing question at the end.

## Step 4: Chunking with overlap (`OverlappingTextSplitter`)
- **Splitting:** split **per section**, so a chunk never crosses a section boundary and its citation is always correct.
- **Size and overlap:** ~1,200 characters per chunk with ~200 characters of overlap, breaking on paragraph or sentence boundaries where possible. Both values are configurable under `app.ingestion.chunk-size` / `chunk-overlap` via a `@ConfigurationProperties` record.
  - **Why characters, not tokens:** they're easy to explain and debug, and at this size we stay well under the embedding model's input limit. Alternative: token-based splitting with JTokkit, which Spring AI already uses. It's more precise but harder to reason about in an interview.
- **Chunk content:** each chunk's text is prefixed with `"<page title> > <section heading>"` so the embedding carries the context. For example, a chunk that only says "set it to true" still matches questions about the right property.
- **Chunk metadata:** `source` (url), `pageTitle`, `section`, `chunkIndex`, `contentHash`.

## Step 5: `IngestionService` + idempotent re-runs
- **Flow per page:**
  1. read the manifest
  2. parse into sections
  3. compute a SHA-256 of the page's clean text
  4. **if** chunks with the same `source` and `contentHash` already exist → **skip** the page (no embedding calls)
  5. **else** delete the page's old chunks (`vectorStore.delete(filter: source == url)`), split, and `vectorStore.add(chunks)`
- **Why this design:**
  - Rerunning with no changes creates **no duplicates** and makes **no API calls**.
  - A changed page is fully replaced, so stale chunks from a shrunken page can't linger.
  - Alternative: deterministic chunk IDs (`UUID.nameUUIDFromBytes(url + index)`) with upsert. That prevents duplicates, but it re-embeds everything on every run and leaves stale tail chunks behind.
- **Hash lookup:** a single `JdbcTemplate` query on `metadata->>'source'` / `metadata->>'contentHash'`. `VectorStore` has no "find by metadata" without a similarity search.
- **Batching:** `PgVectorStore` embeds in batches via Spring AI's `BatchingStrategy`, so ~25 pages means a modest number of requests, not one per chunk. I'll check Gemini's per-request input limit.
- **How you test it:** run ingestion, then `select count(*), count(distinct metadata->>'source') from vector_store`. Run it again: the count is unchanged and the log says "skipped N unchanged pages". Edit one HTML file and rerun: only that page is re-ingested.

## Step 6: Trigger + 429 resilience
- **Trigger:** `POST /api/v1/ingest`, which returns a summary like `{"pagesIngested": 3, "pagesSkipped": 22, "chunksStored": 140}`. **Question for you** (see the end).
- **429 handling:** a full ingestion is the first thing that will hit Gemini's free-tier rate limits.
  - **Two retry layers:** Spring AI 2.x calls Gemini through the official OpenAI Java SDK, which has its own retry (`spring.ai.openai.*.max-retries`), and Spring AI also has `spring.ai.retry.*` (max attempts, exponential backoff, `on-http-codes: 429`). I'll check which layer actually applies to embedding calls in 2.0.1 and configure that one, so we don't end up with two layers multiplying retries.
  - **Backoff values:** initial interval ~2s, multiplier 2, max ~60s, about 6 attempts. Free-tier limits are per minute, so the backoff needs to reach tens of seconds.
- **How you test it:** with the full set of pages, ingestion completes. Logs show the retries if a 429 occurs. To force a 429, temporarily set a tiny batch size.

## Step 7: README
- **Updates:**
  - status → milestone 2
  - how to start Postgres, set `GEMINI_API_KEY`, and run ingestion
  - the idempotency approach in two sentences

After each step I'll give you the test commands and wait for your approval before committing and pushing.

## Questions before I start
1. **Pages:** is the proposed page list OK, or do you want a narrower focus (e.g. only Spring Boot)?
2. **Trigger:** should ingestion be a `POST /api/v1/ingest` endpoint (my recommendation: easy to demo, and returns a summary) or an `ApplicationRunner` on startup behind `app.ingestion.enabled`?
3. **`contextLoads` test with a database:** my recommendation is **Testcontainers** with the pgvector image and `@ServiceConnection`. That's idiomatic Boot 4: a real database in tests, while the LLM and embeddings stay mocked. Alternative: exclude the datasource auto-configuration in that test. That's simpler, but it no longer tests the real wiring.
4. **Unit tests now or in milestone 4?** The parser and splitter are pure functions and easy to test without any LLM. I'd add a few small tests in steps 3–4, but CLAUDE.md puts tests in milestone 4, so it's your call.
