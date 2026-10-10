# Milestone 2 plan

Here's the plan for milestone 2: ingestion.

**Goal:** turn the Spring docs HTML pages in `/docs` into clean, section-aware chunks with overlap, embed them with Gemini, and store them in pgvector. Re-running ingestion must not create duplicates.

**Out of scope (milestone 3+):** `/api/v1/ask`, retrieval, validation, `@RestControllerAdvice`, Swagger, the eval set, and the full test suite. Small unit tests for the parser and splitter **are** in scope (see CLAUDE.md).

## Decisions (checked against the Spring AI 2.0.1 jars in the local Maven cache)

| Item | Choice | Why |
|---|---|---|
| Vector store | `spring-ai-starter-vector-store-pgvector` (managed by the Spring AI BOM) | Auto-configures `PgVectorStore`, a `JdbcTemplate` and the Postgres driver. Alternative: plain JDBC and our own SQL, which is more code for no gain. |
| HTML parsing | `org.jsoup:jsoup` directly | Spring AI's `JsoupDocumentReader` returns one `Document` per page. We need **one per section** to cite page + section, so we walk the headings ourselves. |
| Chunking | Our own `OverlappingTextSplitter extends TextSplitter` | Spring AI 2.0.1's `TokenTextSplitter` has no overlap setting. Extending Spring AI's `TextSplitter` keeps metadata handling idiomatic. |
| Embedding model | `gemini-embedding-2` (override with `GEMINI_EMBEDDING_MODEL`) | Newest stable (non-preview) Gemini embedding model, which is the same rule we used for the chat model. Verified in step 2: it honors `dimensions: 768`, batches multiple inputs, and returns unit-length vectors. `gemini-embedding-001` also works, but returns un-normalized vectors when truncated. |
| Embedding dimensions | **768** | The default is 3072, but pgvector's HNSW index supports at most 2000 dimensions for `vector`. 768 is one of Gemini's recommended sizes. |
| Schema | `spring.ai.vectorstore.pgvector.initialize-schema: true`, with `dimensions: 768` set explicitly | Spring AI creates the table and the HNSW index. Setting the dimensions explicitly avoids an embedding API call at startup. |
| Idempotency | A per-page **fingerprint** covering the page text and the ingestion settings. Unchanged → skip, changed → replace. | See step 5. No duplicates, and no wasted free-tier quota on re-runs. |
| Ingestion trigger | `POST /api/v1/ingest` | Easy to demo, and it returns a summary. |
| Tests that need a database | Testcontainers (pgvector image) + `@ServiceConnection` | Idiomatic Boot 4: a real database in tests, while the LLM and embeddings stay mocked. |
| Unit tests | Small unit tests for the parser and splitter, written in steps 3–4 | They're pure functions, so no LLM and no database are needed. |

## Step 0: Collect the docs
- **Scope:** about 15 pages, **Spring Boot + core Spring Framework only.**
  - Topics: beans/IoC, Java config, transactions, profiles, externalized config, MVC exception handling, validation, Actuator, testing, logging, auto-configuration
  - Dropped: Security, Data JPA and Spring AI
- **Manifest:** `docs/manifest.csv` with columns `file,url,title`. `scripts/fetch-docs.sh` downloads each URL with `curl` into `docs/<file>.html`. The manifest is the single source of truth for the page URL, which we need for citations.
- **License check before committing any HTML:** I'll confirm the license from the actual Spring docs pages (footer and linked license) and report exactly what it says.
  - **If redistribution is clearly allowed:** commit the HTML, with attribution in the README.
  - **Otherwise:** commit only the manifest and fetch script, and gitignore `docs/*.html`. Anyone who clones the repo runs the script first.
- **How you test it:** run `scripts/fetch-docs.sh`, then `ls docs/*.html | wc -l` should print 15. Open a file to confirm it's real content and not an error page.

## Step 1: Connect the app to pgvector
- **Dependency:** add `spring-ai-starter-vector-store-pgvector`.
- **`application.yml`:**
  - the datasource (`${DB_URL:jdbc:postgresql://localhost:5432/springdocsrag}`, `${DB_USERNAME:springdocsrag}`, `${DB_PASSWORD:springdocsrag}`)
  - pgvector settings: `index-type: HNSW`, `distance-type: COSINE_DISTANCE`, `dimensions: 768`, `initialize-schema: true`
- **Tests:** add the Testcontainers dependencies (`spring-boot-testcontainers`, `testcontainers-postgresql`, versions managed by Boot). The `contextLoads` test gets a `@ServiceConnection` `PostgreSQLContainer` that uses the same `pgvector/pgvector:0.8.7-pg17` image as `compose.yaml`.
- **How you test it:**
  - `./mvnw test` passes (Docker must be running).
  - `docker compose up -d` + `./mvnw spring-boot:run`, then `\d vector_store` in `psql` shows a `vector(768)` column and the HNSW index.

## Step 2: Embedding configuration
- **`application.yml`:** `spring.ai.openai.embedding.model: ${GEMINI_EMBEDDING_MODEL:gemini-embedding-001}` and `spring.ai.openai.embedding.dimensions: 768`.
- **Checks:** the exact model name, and that the OpenAI-compatible endpoint honors `dimensions` (the returned vector length must be 768).
- **How you test it:** run a one-off `curl` against Gemini's `/embeddings` endpoint and check the vector length.

## Step 3: HTML → clean sections (`HtmlSectionParser`)
- **Package `ingestion`:** a `record Section(String pageTitle, String pageUrl, String heading, String anchor, String text)`, with `url()` returning the deep link `pageUrl#anchor`. Every Antora `h2`/`h3` has a stable `id`, so citations can link straight to the section.
- **Parsing:** Jsoup selects `article.doc`, which leaves out the site nav, header and footer. Inside it we remove:
  - breadcrumbs, prev/next pagination, and tab labels
  - every code-tab panel except the first. The others (Kotlin, YAML) repeat the same example.
- **Sections:**
  - The page intro (`#preamble`) is a section headed by the page's `h1`.
  - A new section starts at each `h2`/`h3`. An h3 is recorded as `"h2 heading > h3 heading"`.
  - `h4` content stays inside its h3 section, and sections with no text are skipped.
- **Text:**
  - Code (`pre`) is kept verbatim, with line breaks and indentation.
  - Other text has its whitespace collapsed, and blocks are separated by blank lines (which step 4 splits on).
  - Admonitions become `"Note: …"` / `"Warning: …"`.
- **Checked on all 15 real pages:** after noise removal, every word in the article appears in some section.
- **Unit tests:** `HtmlSectionParserTest` runs against a small fixture HTML in `src/test/resources`. It checks:
  - that noise is removed
  - the section boundaries
  - the h2 > h3 headings
  - that code is preserved
- **How you test it:** run `./mvnw test -Dtest=HtmlSectionParserTest`.

## Step 4: Chunking with overlap (`OverlappingTextSplitter`)
- **Splitting:** per section, so a chunk never crosses a section boundary.
- **Size and overlap:** ~1,200 characters with ~200 overlap, breaking on paragraph or sentence boundaries where possible. Configurable via a `@ConfigurationProperties` record (`app.ingestion.chunk-size`, `chunk-overlap`).
  - **Why characters, not tokens:** they're easy to explain, and we stay well under the embedding input limit. Alternative: token-based splitting with JTokkit.
- **Chunk content:** each chunk's text is prefixed with `"<page title> > <section heading>"` so the embedding carries the context.
- **Chunk metadata:** `source` (url), `pageTitle`, `section`, `chunkIndex`, `fingerprint`.
- **Unit tests:** `OverlappingTextSplitterTest` checks:
  - that chunks stay within the size limit
  - that consecutive chunks share the configured overlap
  - that short text yields a single chunk
  - that splitting doesn't break mid-word
- **How you test it:** run `./mvnw test -Dtest=OverlappingTextSplitterTest`.

## Step 5: `IngestionService` + idempotent re-runs
- **Fingerprint per page:** SHA-256 of
  - the page's clean text
  - `chunkSize`
  - `chunkOverlap`
  - the embedding model name
  - the dimensions

  Changing **any** of these settings changes every fingerprint, so everything is re-ingested.
- **Flow per page:**
  1. parse
  2. compute the fingerprint
  3. **if** chunks exist with the same `source` and `fingerprint` → **skip** (no embedding calls)
  4. **else** delete the page's old chunks (`vectorStore.delete(filter: source == url)`), split, and `vectorStore.add(chunks)`
- **Why:**
  - An unchanged re-run creates **no duplicates** and makes **no API calls**.
  - A changed page (or changed settings) is fully replaced, so stale chunks can't linger.
  - Alternative: deterministic chunk IDs with upsert. That re-embeds everything every run and leaves stale tail chunks behind.
- **Caveats:**
  - Changing `dimensions` also changes the column type (`vector(768)`), so the table has to be dropped and recreated. I'll document that rather than automate it.
  - Changes to the parser or splitter *code* aren't captured by the fingerprint. For those, clear the table, which I'll also document.
- **Hash lookup:** a single `JdbcTemplate` query on `metadata->>'source'` / `metadata->>'fingerprint'`. `VectorStore` has no "find by metadata" without a similarity search.
- **Batching:** `PgVectorStore` embeds in batches via Spring AI's `BatchingStrategy`. I'll check Gemini's per-request input limit.
- **How you test it:**
  - Ingest, then run `select count(*), count(distinct metadata->>'source') from vector_store`.
  - Rerun: the count is unchanged and the log says "skipped N unchanged pages".
  - Edit one HTML file: only that page is re-ingested.
  - Change `chunk-overlap`: every page is re-ingested.

## Step 6: `POST /api/v1/ingest` + 429 resilience
- **Endpoint:** a thin `IngestionController` that returns `{"pagesIngested": 3, "pagesSkipped": 12, "chunksStored": 140}`.
- **429 handling:**
  - Spring AI 2.x has the OpenAI SDK's own retry (`spring.ai.openai.*.max-retries`) and also `spring.ai.retry.*`. I'll check which layer applies to embedding calls in 2.0.1 and configure only that one.
  - Backoff: initial interval ~2s, multiplier 2, max ~60s, about 6 attempts.
- **How you test it:** `curl -X POST localhost:8080/api/v1/ingest` completes for all pages. Logs show the retries if a 429 occurs.

## Step 7: README
- **Updates:**
  - status → milestone 2
  - how to fetch the docs, start Postgres, set `GEMINI_API_KEY`, and run ingestion
  - the fingerprint approach and its caveats
  - docs attribution/license

After each step I'll give you the test commands and wait for your approval before committing and pushing.
