# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project

**spring-docs-rag** is a portfolio Retrieval-Augmented Generation (RAG) service I will present in job interviews. It answers questions about Spring using ~20–30 Spring documentation HTML pages. Every answer must be grounded only in retrieved chunks and must cite its sources (page + section).

## How to work with me

- I'm a Java/Spring Boot engineer with 6+ years of experience. I must be able to explain **every line** in an interview.
- Work in **small, reviewable steps**. Briefly explain each choice: why this approach, what the alternative was.
- **Never make large changes without asking first.** Propose the plan, wait for my go-ahead, then implement.
- Prefer idiomatic, conventional Spring code over clever code. No unnecessary abstractions or dependencies.
- Stay within the current milestone. Don't jump ahead.
- After each completed step, tell me how to test it, then commit with a clear message and push to main after I approve.
- Before every command or file change, explain in 1–2 plain sentences what it does and why, so I can approve it knowingly.

## Stack

- **Java 21** + the **latest stable Spring Boot that is compatible with the latest stable Spring AI release** (check start.spring.io), built with **Maven**.
- **Spring AI** for chat, embeddings and the vector store.
- **Postgres + pgvector**, run locally via `docker-compose`.
- **LLM and embeddings:** Google Gemini (free tier) via its **OpenAI-compatible endpoint**, using Spring AI's OpenAI client pointed at Gemini's base URL.
- **Environment:** GitHub Codespaces.

## Secrets

- The API key comes from the env var **`GEMINI_API_KEY`**. Never hard-code keys and never commit them (no keys in `application.yml`, tests, docs, or commit history).
- Reference it in config as `${GEMINI_API_KEY}`.

## What the service does

1. **Ingest** the Spring docs HTML pages in `/docs`: convert HTML to clean text, split into chunks with overlap, and keep section metadata (page title/URL, section heading).
2. **Embed** the chunks and store them in pgvector. Re-running ingestion must **not create duplicates**.
3. **Answer** questions at `POST /api/v1/ask`: retrieve relevant chunks, prompt the LLM to answer **only** from those chunks, and return the answer with **citations (page + section)**. If the context doesn't contain the answer, say so instead of guessing.
4. **Resilience:** handle Gemini **HTTP 429** rate limits with retry and exponential backoff.

## Milestones

1. **Skeleton:** Spring Boot project, a Gemini call via `/api/v1/chat`, `docker-compose` with pgvector.
2. **Ingestion:** HTML to clean text, chunking with overlap, embeddings, storage in pgvector, idempotent re-runs. Small unit tests for the pure ingestion logic (HTML parser, text splitter) are allowed here; the full test suite stays in milestone 4.
3. **RAG endpoint:** `/api/v1/ask` with citations, request validation, global exception handling (`@RestControllerAdvice`), and a minimal UI or Swagger (springdoc-openapi).
4. **Quality and delivery:** eval set (20 Q&A pairs), JUnit/Mockito tests with the LLM mocked, multi-stage Dockerfile, GitHub Actions CI, and a strong README.

**Current milestone: 2**

## Conventions

- API paths are versioned under `/api/v1`.
- Configuration lives in `application.yml`, overridable through environment variables.
- Tests must never call the real Gemini API. Mock the LLM and embedding clients.
