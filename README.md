# EduPulse AI RAG platform: Epic 1, Story 1, Task 1: PDF ingestion (complete)

Maven multi-module project: Spring Boot 4.0.7, Spring Cloud 2025.1.2, Spring AI 2.0.0, Java 21, PostgreSQL + pgvector, Ollama (embeddings).
Modules: `eureka-server`, `api-gateway`, `ingest-static` (this task), and placeholders `ingest-dynamic`, `retrieval-pre`, `retrieval-post` for the next stories.

## What Task 1 does

```
PDF in ./resources  ->  page text (PDFBox)  ->  cleaning  ->  structure-aware chunks (with overlap)
                    ->  embedding (Ollama nomic-embed-text, 768 dims)  ->  pgvector table `vector_store`
```

* **doc_id**: `doc-` + first 16 hex chars of SHA-256 of the lower-cased file path inside `resources/`. Stays the same when the content changes (needed for Task 2).
* **checksum**: SHA-256 of the file bytes.
* **Metadata on every chunk**: `doc_id`, `version` (1), `source`, `page`, `chunk_index`, `checksum`, plus `embedding_model` and `section`.
* **Chunk id**: deterministic UUID from `doc_id + checksum + chunk_index`; storing a chunk twice overwrites it.
* **Re-run of the same file**: skipped (`SKIPPED_UNCHANGED`), no duplicates.
* **Same path, different content**: reported as `CHANGED_NOT_UPDATED` and left untouched. Replacing old chunks is Task 2.
* **Failure while storing**: partial chunks of that document are removed, so a half-stored document is never mistaken for a complete one.
* **A bad file never stops a scan**: logged and reported as `FAILED`.
* **Path safety**: only PDFs inside the resources folder are accepted.

### Chunking rules
* Size and overlap are approximate tokens (1 token ~ 4 characters). `size >= 100`, `0 <= overlap <= size/2`; invalid values stop the service at startup.
* Headings (`1.2 Scope`, `Article 5. Penalties`, ALL CAPS lines) start a new chunk, without overlap across headings.
* Tables stay in one chunk and are split only between rows; rows are stored as `a | b | c`.
* Paragraphs are split on sentence boundaries. Chunks never cross a page, so `page` is exact.
* Garbled pages (over 30% unreadable characters) are skipped and logged. Ligatures, soft hyphens, control characters and line-end hyphenation are cleaned.

## Configuration

| Property | Env var (`.env`) | Default | Meaning |
|---|---|---|---|
| `app.chunk.size` | `CHUNK_SIZE` | 800 | max chunk size, approx. tokens |
| `app.chunk.overlap` | `CHUNK_OVERLAP` | 100 | overlap between consecutive chunks |
| `spring.ai.ollama.embedding.options.model` | `EMBEDDING_MODEL` | nomic-embed-text | embedding model, stored in metadata |
| `spring.ai.vectorstore.pgvector.dimensions` | `EMBEDDING_DIMENSIONS` | 768 | must match the model; changing the model means re-indexing |
| `spring.ai.ollama.base-url` | `OLLAMA_BASE_URL` | `http://host.docker.internal:11434` (Compose) / `http://localhost:11434` (IntelliJ) | where Ollama runs |
| `app.resources.path` | `APP_RESOURCES_PATH` | `/data/resources` (Compose) / `./resources` | folder with PDFs (mounted read-only) |
| `app.ingest.on-startup` | `APP_INGEST_ON_STARTUP` | true | scan the folder when the service is ready |

No secrets are committed. Copy `.env.example` to `.env` (git-ignored) and set `POSTGRES_PASSWORD` and `VENDOR_WEBHOOK_SECRET` (any values you choose; Ollama needs no API key).

## Run

```cmd
copy .env.example .env
notepad .env
ollama pull nomic-embed-text
docker compose up --build -d
docker compose ps
```
Only the gateway (8080) is public; Eureka (8761) and Postgres (5432) listen on localhost only. The full list of commands (tests, checks, cleanup) is in [`docs/RUNBOOK.md`](docs/RUNBOOK.md).

## REST API (through the gateway, port 8080)

| Method and path | Purpose |
|---|---|
| `POST /ingest-static/ingest/scan` | ingest every PDF in the resources folder |
| `POST /ingest-static/ingest/file?name=<file.pdf>` | ingest one PDF |
| `GET  /ingest-static/ingest/sample?count=20` | random stored chunks for the manual quality check (max 100) |

Status values: `INGESTED`, `SKIPPED_UNCHANGED`, `CHANGED_NOT_UPDATED`, `FAILED`. Errors for a single file return HTTP 400 with `{"error": "..."}`.

## Test results

Environment: Windows (10.0.26300), Docker Desktop, Ollama local, 2026-11-23.

| Check | Result |
|---|---|
| `docker compose up --build -d`: all six images build (main and test sources compile) | OK (about 100 s) |
| Startup order: pgvector, eureka, ingest-static healthy; gateway and the other services started | OK |
| Startup scan: 3 PDFs found and ingested | OK |
| `sample-policy.pdf`: 4 pages, 8 chunks | OK |
| `sample-tables.pdf`: 2 pages, 4 chunks | OK |
| `sample-two-columns.pdf`: 3 pages, 8 chunks | OK |
| Database counts per source (8 / 4 / 8, total 20) match the logs | OK |
| Missing file: `POST /ingest/file?name=nope.pdf` returns 400 `{"error":"File not found: nope.pdf"}` | OK |

Still to confirm and sign off by the team (see the runbook, sections 6 to 9):

- [ ] Re-run creates no duplicates (`SKIPPED_UNCHANGED`, count stays 20)
- [ ] Broken file does not stop the batch
- [ ] Manual check of 20 random chunks: no broken or garbled text; tables and columns look right. _Reviewer / date / result:_ ..........
- [ ] 34 unit tests green (`mvn test` or IntelliJ)

## Acceptance criteria (Task 1)

| Criterion | Where |
|---|---|
| Sample PDFs incl. tables and multiple columns ingested end to end | `resources/`, startup scan |
| Full metadata schema on every chunk | `Chunk.metadata()`; test `ingestsAPdfWithFullMetadataOnEveryChunk`; SQL check in the runbook |
| Chunk size, overlap, embedding model configurable and documented | Configuration table above |
| Re-running the same file creates no duplicates | tests `reRunningTheSameFileCreatesNoDuplicates`, `storingTheSameChunksTwiceStillGivesNoDuplicates`; runbook section 6 |
| Manual check of 20 random chunks | `GET /ingest/sample?count=20` or SQL in the runbook |
| Success and failure path tests; errors and key events logged; no secrets | `ingest-static/src/test` (34 tests), SLF4J logging, `.gitignore` + `.env.example` |

## Known limits

* Table and column detection are heuristics. Tables whose cells PDFBox returns on separate lines are stored as running text (no data lost). If this shows up in the manual check, the fix is the `setSortByPosition` setting in `PdfPageExtractor`.
* Scanned PDFs (images only) are rejected with a clear message; there is no OCR.
* The PDFBox warning `Using fallback font LiberationSans` is harmless: the container lacks the PDF's standard font and uses a substitute; text extraction is not affected.
* Updating, deleting and the manifest are Story 1 Tasks 2 to 4.

## Definition of done (baseline), for the team
- [ ] Code reviewed and merged to main
- [ ] Tests pass
- [ ] No secrets in the repository
- [ ] README updated
- [ ] Demo to the product owner
