# Milestone 3 plan

Here's the plan for milestone 3: the RAG endpoint.

**Goal:** `POST /api/v1/ask` retrieves the most relevant chunks from pgvector, asks Gemini to answer **only** from them, and returns the answer with **citations (page + section)**. If the chunks don't contain the answer, it says so instead of guessing. Invalid requests and failures come back as RFC 9457 `ProblemDetail` JSON, and the API is browsable in Swagger UI.

**Out of scope (milestone 4):** the eval set and runner, the full test suite (ingestion against a real database, end to end `/ask`), Dockerfile, CI, and the final README. Milestone 3 has its own tests for the new code, with the `ChatModel`, `EmbeddingModel` and `VectorStore` mocked.

## Decisions (checked against the Spring AI 2.0.1 and springdoc jars)

| Item | Choice | Why |
|---|---|---|
| RAG wiring | Our own `AskService`: `VectorStore.similaritySearch` → numbered sources in the prompt → `ChatClient` | Alternative: Spring AI's `QuestionAnswerAdvisor` / `RetrievalAugmentationAdvisor`. They hide the prompt, and we need the numbering to map the model's citations back to page + section. Our version is ~100 lines that can be explained line by line. |
| Sources in the prompt | Retrieved chunks are **grouped by section** (deep-link URL), in rank order, and numbered `[1]`, `[2]`, … | One number per section means a citation is exactly "page + section", even when two overlapping chunks of the same section are retrieved. |
| How the model cites | Inline markers like `[2]` after each claim; we parse them with a regex | Alternative: structured JSON output (`entity()`). That's another failure mode (invalid JSON) and makes the answer text harder to read. Inline markers are the convention users know from search assistants. |
| Citations returned | Only the sources the answer actually cites, each as `{id, page, section, url}` | The `id` matches the `[n]` marker in the answer text. Returning every retrieved chunk would cite sections the answer never used. |
| "Not in the docs" | The system prompt asks for one fixed sentence, `I don't know based on the provided Spring documentation.`; we detect it and return `answered: false` with no citations. With no retrieved chunks at all, we return that answer **without calling the LLM**. | Explicit, testable refusal. The eval in milestone 4 measures how well the model follows it. |
| Retrieval settings | `app.ask.top-k: 6`, `app.ask.similarity-threshold: 0.0` (accept all) | 6 chunks ≈ 6,000 characters of context, well within Gemini's limits. The threshold stays at "accept all" until the eval gives data to tune it; a wrong guess would silently drop relevant chunks. |
| Temperature | Gemini's default (not set) | Google recommends keeping Gemini 3 models at their default temperature; grounding comes from the prompt rules, not from the sampling. |
| Validation | `spring-boot-starter-validation`; `AskRequest(@NotBlank @Size(max = 1000) String question)` + `@Valid` | Standard Bean Validation. The size cap keeps a single request from burning tokens. `ChatRequest` gets `@NotBlank` too. |
| Error handling | `GlobalExceptionHandler extends ResponseEntityExceptionHandler`, a `@RestControllerAdvice` returning `ProblemDetail` | The base class already turns every Spring MVC exception (malformed JSON, wrong method, …) into a `ProblemDetail`. We add only what's ours. |
| Mapped errors | **400** validation, with an `errors` map (field → message). **429** `EmbeddingQuotaExceededException`, with a `Retry-After` header in seconds. **429** `com.openai.errors.RateLimitException` (chat rate limit after the OpenAI SDK's own retries). **500** anything else, logged, with a generic message that leaks no internals. | Verified with a probe test against a fake server: a chat 429 reaches our code unwrapped as `RateLimitException`, and milestone 2's test shows the same for `EmbeddingQuotaExceededException`. |
| Chat 429 retry | Unchanged: the OpenAI SDK retries 429s itself with exponential backoff (`spring.ai.openai.max-retries`, default 3) | Already satisfies the resilience requirement for chat; the advice only handles what's left when retries are exhausted. |
| API docs | `org.springdoc:springdoc-openapi-starter-webmvc-ui` **3.1.1**, Swagger UI at `/swagger-ui.html` | Latest stable release, built against Spring Boot 4.1.0. Chosen over a hand-written UI: zero front-end code, and it documents the request/response records and the `ProblemDetail` errors automatically. |

## Step 1: Dependencies and configuration
- **`pom.xml`:** add `spring-boot-starter-validation` and `springdoc-openapi-starter-webmvc-ui` (version pinned in a property, since neither BOM manages it).
- **`application.yml`:** `app.ask.top-k` and `app.ask.similarity-threshold`, bound to an `AskProperties` record.

## Step 2: `AskService`
- **Retrieve:** `vectorStore.similaritySearch(SearchRequest.builder().query(question).topK(...).similarityThreshold(...))`. The question is embedded with the same Gemini model as the chunks.
- **Group:** chunks by `url` metadata (section deep link) into numbered `Source`s, keeping rank order.
- **Prompt:**
  - **System:** answer only from the sources, cite them as `[n]` after each claim, use the fixed refusal sentence when the answer isn't there, be concise.
  - **User:** the numbered sources (`[n] <chunk text>`, which already starts with "page title > section"), then the question. Both are passed as template parameters, so braces in code samples are never parsed as template syntax.
- **Answer:** refusal sentence → `answered: false`, no citations. Otherwise extract the cited numbers (`[2]`, `[1][3]`, `[1, 3]`), ignore any number that wasn't a source, and return those sources as citations.
- **Response:** `{"answer": "...", "answered": true, "citations": [{"id": 1, "page": "Spring Boot: Profiles", "section": "Adding Active Profiles", "url": "https://...#features.profiles.adding-active-profiles"}]}`

## Step 3: `AskController` + validation
- `POST /api/v1/ask` with `@Valid @RequestBody AskRequest`. Thin controller, like the others.
- `ChatController` gets `@Valid` and `ChatRequest` gets `@NotBlank`.

## Step 4: `GlobalExceptionHandler`
- **400:** override `handleMethodArgumentNotValid` and add `errors: {"question": "must not be blank"}` to the `ProblemDetail` the base class built.
- **429:** `EmbeddingQuotaExceededException` → title "Gemini quota exceeded", `Retry-After: <seconds>`. `RateLimitException` → title "Gemini rate limit exceeded".
- **500:** `@ExceptionHandler(Exception.class)` logs the stack trace and returns "An unexpected error occurred." More specific handlers (including the base class's) always win over this one.

## Step 5: Swagger UI
- Comes with the dependency. `/v3/api-docs` lists `/api/v1/ask`, `/api/v1/ingest` and `/api/v1/chat`; `/swagger-ui.html` lets you try them.

## Tests (no real Gemini calls)
- **`AskServiceTest`** (plain Mockito, mocked `VectorStore` and `ChatModel` behind a real `ChatClient`):
  - no chunks → refusal, and the chat model is never called
  - chunks of the same section become one source; the prompt contains the numbered sources and the question
  - only the cited sources come back as citations, unknown numbers are ignored
  - the refusal sentence → `answered: false`, no citations
- **`AskControllerTest`** (`@WebMvcTest`, mocked `AskService`):
  - 200 with the response JSON
  - blank or missing question → 400 `ProblemDetail` with `errors.question`
  - `EmbeddingQuotaExceededException` → 429 with `Retry-After`
  - unexpected exception → 500 with no internal details
- **Context test:** `/v3/api-docs` is served and contains `/api/v1/ask`.

## How you test it
- `./mvnw verify` (needs Docker for the context test).
- With the app running and the docs ingested:
  - `curl -s localhost:8080/api/v1/ask -H 'Content-Type: application/json' -d '{"question":"How do I activate a Spring profile?"}'` → an answer with `[n]` markers and matching citations.
  - `-d '{"question":"How do I bake bread?"}'` → `answered: false`, no citations.
  - `-d '{"question":""}'` → 400 `ProblemDetail` with `errors.question`.
  - Open `http://localhost:8080/swagger-ui.html`.
- While the daily embedding quota is exhausted, `/ask` returns 429 with `Retry-After` within a second.
