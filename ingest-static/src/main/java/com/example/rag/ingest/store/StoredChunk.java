package com.example.rag.ingest.store;

/** A chunk read back from the vector store (used for the manual quality check). */
public record StoredChunk(String id, String content, String metadata) {
}
