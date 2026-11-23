# RAG platform (EduPulse AI): Epic 1, Story 1, Task 1: PDF ingestion

Maven multi-module project: Spring Boot 4.0.7, Spring Cloud 2025.1.2, Spring AI 2.0.0, Java 21, pgvector.
Modules: `eureka-server`, `api-gateway`, `ingest-static` (this task), `ingest-dynamic`, `retrieval-pre`, `retrieval-post`
(the last three are empty placeholders for the next stories).

## What Task 1 does

`PDF` -> page-by-page text (PDFBox) -> cleaning -> **structure-aware chunking with overlap** -> embedding + storage in pgvector.

* **doc_id**: `doc-` + first 16 hex chars of SHA-256 of the file path inside `resources/` (lower case). It does not change when the content changes.
* **checksum**: SHA-256 of the file bytes.
* **Metadata on every chunk**: `doc_id`, `version` (1), `source`, `page`, `chunk_index`, `checksum`, plus `embedding_model` and `section` (last heading).
* **Chunk id** is a deterministic UUID from `doc_id + checksum + chunk_index`, so storing the same chunk twice overwrites it.
* **Re-running the same file**: skipped (same doc_id and checksum). A file with the same path but a different checksum is reported as `CHANGED_NOT_UPDATED` and left untouched: replacing old chunks is Task 2.
* **Partial failure**: if embedding/storing fails half-way, the chunks of that document are removed so a half-stored document is never mistaken for a complete one.
* **One bad file never stops a scan**; it is logged and reported as `FAILED`.

### Chunking rules (`StructureAwareChunker`)
* Size and overlap are in approximate tokens (1 token ~ 4 chars). Rules: `size >= 100`, `0 <= overlap <= size/2` (checked at startup).
* A heading (numbered such as `1.2 Scope`, `Article 5. Penalties`, or an ALL CAPS line) closes the current chunk and starts a new one. No overlap across headings.
* A table (rows with wide gaps / `|` / short numeric rows) stays in one chunk and is only split between rows. Rows are written as `a | b | c`.
* Paragraphs are split on sentence boundaries. Chunks never cross a page, so `page` is exact.
* Garbled pages (more than 30% unreadable characters) are skipped and logged. Ligatures, soft hyphens, control characters and line-end hyphenation are cleaned.

## Configuration

| Property (env var) | Default | Meaning |
|---|---|---|
| `app.chunk.size` (`APP_CHUNK_SIZE` / `CHUNK_SIZE` in .env) | 800 | max chunk size, approx. tokens |
| `app.chunk.overlap` (`APP_CHUNK_OVERLAP` / `CHUNK_OVERLAP`) | 100 | overlap between consecutive chunks |
| `spring.ai.ollama.embedding.options.model` (`EMBEDDING_MODEL`) | nomic-embed-text | Ollama embedding model, stored in metadata |
| `spring.ai.vectorstore.pgvector.dimensions` (`EMBEDDING_DIMENSIONS`) | 768 | must match the model; changing the model means re-indexing |
| `app.resources.path` (`APP_RESOURCES_PATH`) | `./resources` | folder with the PDFs |
| `app.ingest.on-startup` (`APP_INGEST_ON_STARTUP`) | true | scan the folder when the service is ready |

Ollama needs no API key. Secrets live only in `.env` (git-ignored). Copy `.env.example` to `.env` and set `POSTGRES_PASSWORD` (any password you invent) and `VENDOR_WEBHOOK_SECRET` (any random string; used in Story 2).

**Ollama**: install it, then run `ollama pull nomic-embed-text` (embeddings, 768 dimensions) and optionally `ollama pull llama3.2` (chat, used by later stories). Check with `ollama list`. If you use another embedding model, set `EMBEDDING_MODEL` and `EMBEDDING_DIMENSIONS` (e.g. `mxbai-embed-large` = 1024) and re-index.

## Run with IntelliJ IDEA

1. **Open**: File > Open > select the root `pom.xml` > "Open as Project". Wait for Maven import. Project SDK: JDK 21 (File > Project Structure).
2. **Config**: copy `.env.example` to `.env` and fill the two required values. Make sure Ollama is running with `nomic-embed-text` pulled.
3. **Database only**: in a terminal in the project root run `docker compose up -d pgvector` (needs Docker Desktop; compose also checks the two variables above).
4. **Run configuration** for `IngestStaticApplication` (Run > Edit Configurations > Spring Boot):
   * Working directory: `$ProjectFileDir$` (so `./resources` is found)
   * Environment variables: `DB_PASSWORD=<your POSTGRES_PASSWORD>;EUREKA_CLIENT_ENABLED=false` (Ollama is found at http://localhost:11434 by default)
5. Run it. On start it ingests `./resources/*.pdf` (3 samples included). Logs show chunk counts per file.
6. **Tests**: right click `ingest-static/src/test` > Run Tests (no database or API key needed).
7. **Whole stack**: `docker compose up --build -d`, then `docker compose ps`. Containers reach Ollama through `host.docker.internal`. On Windows/Mac (Docker Desktop) this works out of the box. On Linux start Ollama with `OLLAMA_HOST=0.0.0.0`.

## Try it (through the gateway, port 8080)

```
curl -X POST http://localhost:8080/ingest-static/ingest/scan
curl -X POST "http://localhost:8080/ingest-static/ingest/file?name=sample-tables.pdf"
curl "http://localhost:8080/ingest-static/ingest/sample?count=20"      # 20 random chunks for the manual quality check
```
Running `scan` twice: the second answer shows `SKIPPED_UNCHANGED` for every file and the row count in `vector_store` does not change
(`SELECT count(*) FROM vector_store;`).

## Acceptance criteria (Task 1) and where they are met

| Criterion | Where |
|---|---|
| Sample PDFs incl. tables and multiple columns ingested | `resources/sample-policy.pdf`, `sample-tables.pdf`, `sample-two-columns.pdf` |
| Full metadata schema on every chunk | `Chunk.metadata()`; test `ingestsAPdfWithFullMetadataOnEveryChunk` |
| Size, overlap, embedding model configurable and documented | table above, `application.yml` |
| Re-run creates no duplicates | `reRunningTheSameFileCreatesNoDuplicates`, `storingTheSameChunksTwiceStillGivesNoDuplicates` |
| Manual check of 20 random chunks | `GET /ingest/sample?count=20` (**to be done by the team and signed off below**) |
| Success path + failure path tests | `ingest-static/src/test` (33 unit tests) |
| No secrets in repo; errors and key events logged | `.gitignore`, `.env.example`; SLF4J logs in extractor, chunker, service |

Manual check record: _date / reviewer / result_ ......

## Known limits and things to verify on the first real run

* **Not compiled in the environment where it was written** (no JDK/Maven there). The Spring AI 2.0 / Boot 4 names used (including the `spring.ai.ollama.*` properties and `spring-ai-starter-model-ollama`) are `Document.builder().id().text().metadata().build()`, `VectorStore.add(List<Document>)` and the starters from your settings. If IntelliJ flags one, it is a one-line change in `PgVectorChunkRepository`.
* **Table and column detection are heuristics.** Whether PDFBox returns a table row on one line depends on how the PDF was produced. Use `/ingest/sample` and the manual check on the three sample PDFs. If tables look flattened, the fix is a setting in `PdfPageExtractor` (`setSortByPosition`).
* Scanned PDFs (images only) have no text layer and are rejected with a clear message. OCR is out of scope.

## Baseline (definition of done) checklist, for the team
- [ ] Code reviewed and merged to main  - [ ] Tests pass (`mvn test`)  - [ ] No secrets committed  - [ ] README updated  - [ ] Demo to the product owner
