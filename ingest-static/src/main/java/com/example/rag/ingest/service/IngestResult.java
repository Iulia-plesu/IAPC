package com.example.rag.ingest.service;

/** Outcome of ingesting one file. */
public record IngestResult(String docId, String source, Status status, int chunks, String message) {

    public enum Status {
        /** New document: chunks were embedded and stored. */
        INGESTED,
        /** Same path and same checksum as a stored document: nothing to do. */
        SKIPPED_UNCHANGED,
        /** Same path, different checksum. Replacing the old chunks is Story 1, Task 2. */
        CHANGED_NOT_UPDATED,
        /** The file could not be ingested; see message. Other files are not affected. */
        FAILED
    }
}
