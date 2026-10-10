# After the Gemini quota resets

Everything that still needs real Gemini calls, in order. Last night's work was verified without Gemini only: `./mvnw verify` (50 tests, AI models mocked), local fake servers for the retry paths and the eval runner, a Docker smoke test, and a green CI run.

**When:** Gemini's daily embedding quota (`EmbedContentRequestsPerDayPerProjectPerModel-FreeTier`, 1,000 requests for `gemini-embedding-2`) answered "retry in 8h2m" at 15:58 UTC on 2026-10-10, so it resets around 00:00 UTC on 2026-10-11.

**Budget:** a full ingestion embeds ~448 chunks, and the eval adds 20 question embeddings and 20 chat calls. That should fit in one day's quota, but avoid needless full re-ingestions.

## 1. Start everything from `main`

```bash
git pull
scripts/fetch-docs.sh
docker compose up -d
export GEMINI_API_KEY=your-key
./mvnw spring-boot:run
```

## 2. Milestone 2, step 6 test (full ingestion, then an idempotent re-run)

`vector_store` currently holds 15 chunks of 1 page from the failed run. Start from an empty table:

```bash
docker compose exec postgres psql -U springdocsrag -d springdocsrag -c "truncate vector_store"
curl -s -X POST localhost:8080/api/v1/ingest   # expect {"pagesIngested":15,"pagesSkipped":0,"chunksStored":~448}
curl -s -X POST localhost:8080/api/v1/ingest   # expect {"pagesIngested":0,"pagesSkipped":15,"chunksStored":0}
docker compose exec postgres psql -U springdocsrag -d springdocsrag \
  -c "select count(*), count(distinct metadata->>'source') from vector_store"   # expect ~448 | 15
```

- The first call may take a few minutes: it waits through the **per-minute** 429s (5s doubling to 60s).
- If it instead returns **429 within a second**, the **daily** quota isn't reset yet. That's the fail-fast path working. Re-run later; pages already stored are skipped.

## 3. Milestone 3 live checks of `/api/v1/ask`

```bash
curl -s localhost:8080/api/v1/ask -H 'Content-Type: application/json' -d '{"question":"How do I activate a Spring profile?"}'
curl -s localhost:8080/api/v1/ask -H 'Content-Type: application/json' -d '{"question":"How do I configure OAuth2 login with Spring Security?"}'
```

- **First call:** an answer with `[n]` markers and `answered: true`, and citations pointing at the Profiles page's sections.
- **Second call:** `answered: false`, no citations.
- **Check that the model follows the prompt format:** markers like `[1]`, and the exact refusal sentence. If it doesn't, fix the prompt in `AskService` before the eval.
- **Swagger UI:** run one question through `/swagger-ui.html`.

## 4. Run the eval

```bash
./mvnw test -Dtest=EvalRunner -Deval.baseUrl=http://localhost:8080
```

- **Duration:** about 2 minutes (a 5s pause between questions). If Gemini's chat rate limit bites, raise the pause with `-Deval.delay=10`.
- **Review `eval/report.md`:** for each failed question, decide whether the cause is retrieval (expected page not cited), generation (key fact missing), or grounding (wrong refusal).

## 5. Tune only if the report shows a clear problem

- **Changes that don't re-ingest:** `app.ask.top-k`, `app.ask.similarity-threshold` and the prompt only affect `/ask`, so re-run the eval after each change.
- **Changes that re-ingest everything:** `chunk-size` and `chunk-overlap` are part of the fingerprint, so changing them re-ingests all pages and costs ~448 embedding requests.
- **Record each change** and its effect in a short "Step 5: tuning" section of `plans/milestone-4.md`.

## 6. Publish the score

- Commit `eval/report.md` from the final run.
- In `README.md`, replace the four `_pending first run_` cells with the scores, and change the status line to milestone 4 complete.
- Commit and push; CI should stay green.

## 7. Optional: the Docker image against real Gemini

```bash
docker build -t spring-docs-rag .
docker run --rm --network host -e GEMINI_API_KEY -v "$PWD/docs:/app/docs:ro" spring-docs-rag
```

Then ask one question as in step 3; the app on port 8080 must be stopped first. Tonight's smoke test covered only startup, Swagger UI and a 400.
