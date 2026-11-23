# EduPulse AI RAG platform: Epic 1, Story 1, Task 1 (checkpoint 1 of 2)

> **Status: intermediate checkpoint.** PDF extraction, cleaning and structure-aware chunking are done and unit-tested.
> Embedding, vector storage and the REST endpoints come in checkpoint 2 (the same task, completed).

Maven multi-module project: Spring Boot 4.0.7, Spring Cloud 2025.1.2, Spring AI 2.0.0, Java 21, PostgreSQL + pgvector, Ollama for embeddings.

## What is in this checkpoint

| Area | Content |
|---|---|
| Project skeleton | Parent POM, six modules (`eureka-server`, `api-gateway`, `ingest-static`, and placeholders `ingest-dynamic`, `retrieval-pre`, `retrieval-post`), `Dockerfile`, `docker-compose.yml`, `init-db/`, `.env.example`, `.gitignore` |
| `PdfPageExtractor` | Reads a PDF page by page with PDFBox, so every chunk keeps its page number |
| `TextCleaner` | Unicode and ligature fixes, control/invisible characters, line-end hyphenation, detection of garbled pages |
| `StructureAwareChunker` | Headings start new chunks, tables are kept together, paragraphs split on sentences, **overlap** between chunks, hard size limit, chunks never cross a page |
| `Chunk`, `DocumentIdentity` | Stable `doc_id` (from the file path), SHA-256 `checksum`, full metadata schema (`doc_id`, `version`, `source`, `page`, `chunk_index`, `checksum`, + `embedding_model`, `section`), deterministic chunk ids |
| `IngestProperties` | Chunk size, overlap, embedding model and resources folder are configurable (see below) |
| Tests | 25 unit tests: cleaner (6), identity (4), PDF extractor (4), chunker (11). No database, no Ollama needed |
| Sample data | `resources/sample-policy.pdf`, `sample-tables.pdf`, `sample-two-columns.pdf` |

**Not in this checkpoint:** embedding and storage in pgvector, skipping unchanged files, REST endpoints, startup scan.

## Requirements

* JDK 21, IntelliJ IDEA (Maven is bundled), Docker Desktop, Ollama with `nomic-embed-text` (`ollama pull nomic-embed-text`).
* Windows, macOS or Linux.

## Configuration

| Property | Env var | Default | Meaning |
|---|---|---|---|
| `app.chunk.size` | `CHUNK_SIZE` | 800 | max chunk size in approx. tokens (1 token ~ 4 chars), must be >= 100 |
| `app.chunk.overlap` | `CHUNK_OVERLAP` | 100 | overlap between consecutive chunks, between 0 and size/2 |
| `spring.ai.ollama.embedding.options.model` | `EMBEDDING_MODEL` | nomic-embed-text | embedding model (recorded in metadata) |
| `spring.ai.vectorstore.pgvector.dimensions` | `EMBEDDING_DIMENSIONS` | 768 | must match the model |
| `app.resources.path` | `APP_RESOURCES_PATH` | `./resources` | folder with PDFs |

No secrets are stored in the repository. Copy `.env.example` to `.env` (git-ignored) and set `POSTGRES_PASSWORD` and `VENDOR_WEBHOOK_SECRET` (any values you choose; Ollama needs no API key).

## How it was tested

Environment: Windows (10.0.26300), Docker Desktop, IntelliJ IDEA, Ollama running locally.

| Check | How | Result |
|---|---|---|
| Docker engine reachable | `docker info` | OK after starting Docker Desktop |
| Database container | `docker compose up -d pgvector` | container created and started |
| Database healthy + extensions | `docker compose ps` and `docker compose exec pgvector psql -U rag -d ragdb -c "\dx"` | `vector` extension present |
| Ollama reachable | `curl.exe http://localhost:11434` | `Ollama is running` |
| Unit tests (25) | IntelliJ: right-click `ingest-static/src/test` > Run Tests | see note below |

* [ ] Unit tests: all 25 green in IntelliJ (tick after you have run them).
* The complete project (checkpoint 2, which contains all of this code) was also built with `docker compose up --build -d`. That build compiles the main and test sources of every module.

Run the unit tests from the command line without installing Maven:

```cmd
docker run --rm -v "%cd%":/build -v "%USERPROFILE%\.m2":/root/.m2 -w /build maven:3.9-eclipse-temurin-21 mvn -B -pl ingest-static -am test
```
Expected: `Tests run: 25, Failures: 0, Errors: 0`.

## Known limits

* Table and column detection are heuristics (see `StructureAwareChunker`). They are checked on the three sample PDFs in checkpoint 2.
* Scanned PDFs without a text layer are not supported (no OCR).

## Next: checkpoint 2

Embedding + pgvector storage, idempotent re-ingest (no duplicates), REST endpoints (`/ingest/scan`, `/ingest/file`, `/ingest/sample`), startup scan, service tests.
