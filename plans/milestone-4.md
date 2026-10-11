# Milestone 4 plan

Here's the plan for milestone 4: quality and delivery.

**Goal:** prove the service works and make it easy to run and review. That means an eval set that scores answers against the real docs, a test suite with every AI model mocked, a Docker image, CI on every push, and a README that explains the architecture and the trade-offs.

**Scope tonight: code only.** The Gemini free-tier quota is exhausted until it resets, so nothing here calls Gemini. The eval runner is written but **not run**; `plans/tomorrow.md` lists what still needs a real run.

## Decisions

| Item | Choice | Why |
|---|---|---|
| Eval set | `eval/questions.json`: 20 questions. 18 answerable, covering all 15 pages, each with an `expectedPage` and 1–3 `keyFacts`. 2 unanswerable (OAuth2 login, Kafka listener concurrency), expected "not in the docs". | Every key fact was checked to appear verbatim on its expected page, and both unanswerable topics appear on none of the 15 pages. |
| Key-fact matching | Case-insensitive substring; `\|` separates accepted alternatives (e.g. `logging.file.name\|logging.file.path`) | Simple and explainable. Facts are identifiers (property names, annotations, class names) that a correct answer has to mention, so substring matching is reliable. Alternative: an LLM judge. That costs quota and is itself non-deterministic. |
| Scores | Per question: key facts found, expected page cited (a citation `url` starts with `expectedPage`), correct refusal behaviour (answers the answerable ones, refuses the others). A question **passes** when all its checks pass. | Three separate signals show *why* a question fails: retrieval (wrong source), generation (missing fact), or grounding (wrong refusal). |
| Eval runner | `EvalRunner`, a JUnit class in `src/test` that is **skipped unless `-Deval.baseUrl` is set**. It calls the running app's `POST /api/v1/ask`, writes `eval/report.md` and prints the summary. | Reuses the test classpath (Jackson, `RestClient`) and the app's own `AskResponse` record, with no new plugin or dependency. Skipped by default, so `./mvnw verify` and CI never call Gemini. |
| Eval pacing | A 5s pause between questions (`-Deval.delay`). On a 429, it waits `Retry-After` and retries up to 3 times, but aborts when Gemini asks for more than 2 minutes (the daily quota). | 20 questions × (1 embedding + 1 chat call) stays inside the free tier's per-minute limits. |
| Integration tests | `IngestionServiceIntegrationTest` and `AskEndToEndTest`. Real pgvector (Testcontainers), real ingestion and retrieval; `EmbeddingModel` and `ChatModel` replaced with `@MockitoBean`. The embedding mock returns deterministic bag-of-words vectors, so similarity search is meaningful. | Covers what the unit tests can't: the SQL fingerprint lookup, the transaction around delete + add, and retrieval → prompt → citations through HTTP. Alternative: a hand-written fake bean, but a mock replaces the real bean (exactly one `EmbeddingModel`) and lets us verify that re-runs make no calls. |
| Test fixtures | Two small Antora-style pages plus a manifest, copied to a temp directory per test (`app.ingestion.docs-dir`) | The real docs aren't in the repo (not redistributed), so CI can't use them. A temp copy lets a test edit a page safely. |
| Dockerfile | Multi-stage. Build stage: `eclipse-temurin:21-jdk`, `./mvnw package -DskipTests`, then Spring Boot's layered extraction (`-Djarmode=tools extract --layers --launcher`). Run stage: `eclipse-temurin:21-jre`, non-root user, one `COPY` per layer. | Dependencies change rarely, so their layers stay cached and only the application layer is rebuilt. Tests run in CI, not in the image build (Testcontainers needs Docker). |
| Docs in the container | Not baked in. Mount `docs/` at `/app/docs` (`DOCS_DIR`). | The HTML isn't redistributed, and an image counts as distribution. |
| CI | `.github/workflows/ci.yml` on push and pull request to `main`: `actions/checkout@v7`, `actions/setup-java@v6` (Temurin 21, Maven cache), `./mvnw -B verify`, then `docker build`. | GitHub's Ubuntu runners have Docker, so Testcontainers works. No secrets: tests never call Gemini. Building the image catches Dockerfile breakage. |
| README | Status, quick start, architecture (Mermaid diagram of ingestion and ask flows), design decisions and trade-offs from the plans, testing, eval (score placeholder until the real run), Docker, CI badge. | Mermaid renders natively on GitHub. |

## Step 1: Eval set and runner
- `eval/questions.json` as described above.
- `src/test/java/.../eval/EvalRunner.java`:
  - Run: `./mvnw test -Dtest=EvalRunner -Deval.baseUrl=http://localhost:8080`
  - Output: `eval/report.md` with a summary table and one row per question (answer excerpt, facts found, cited pages).
- **Checked tonight without Gemini:** against a local stub server that returns canned `/ask` responses.

## Step 2: Integration tests with mocked AI models
- **`IngestionServiceIntegrationTest`:**
  - first run stores every page's chunks with the expected metadata
  - re-run skips every page and makes **no** embedding calls
  - editing one page re-ingests only that page
  - an embedding failure (`EmbeddingQuotaExceededException`) rolls back the page's delete, so the old chunks stay
- **`AskEndToEndTest`:** ingest the fixtures, `POST /api/v1/ask` through MockMvc. It checks that:
  - the relevant section is retrieved and sent as source `[1]`
  - the cited section comes back as a citation with its deep link
  - a "not in the docs" reply returns `answered: false`

## Step 3: Dockerfile and CI
- `Dockerfile`, `.dockerignore` (no `target/`, `.git/` or docs HTML in the build context).
- `.github/workflows/ci.yml`.
- **Checked tonight:** `docker build` succeeds; the container starts, serves Swagger UI and answers a blank question with 400 (no Gemini call). After the push, the CI run on GitHub passes.

## Step 4: README
- Architecture section with a Mermaid diagram, design decisions with trade-offs, eval section with a score placeholder, Docker and CI instructions.

## Step 5: Chat model switch
- **Problem:** the first live eval stopped at question 14 with a chat 429. `gemini-3.8-flash` allows only 20 requests per day and 5 per minute on the free tier, which is less than one 20-question eval run.
- **Change:** the default chat model (`spring.ai.openai.chat.model`) is now `gemini-3.5-flash-lite`: 500 requests per day and 15 per minute. It's the exact, non-preview name from the key's model list (not the moving `gemini-flash-lite-latest` alias).
- **Trade-off:** a smaller model. The task is restating retrieved text with `[n]` markers, and the eval measures whether it still does that well. `GEMINI_CHAT_MODEL` switches back without a code change.
- **Also:** chat 429s now log Gemini's quota id and retry delay (and set `Retry-After`), and the eval runner saves after every question and resumes, so a quota stop no longer loses a run.

## How you test it
- `./mvnw verify`: all tests pass, with no Gemini calls.
- `docker build -t spring-docs-rag .` and the `docker run` command from the README.
- The CI badge on GitHub is green.
- After the quota resets: run the eval (see `plans/tomorrow.md`).
