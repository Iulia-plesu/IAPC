package com.example.rag.ingest.model;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One piece of a document, ready to be embedded and stored.
 *
 * @param docId      stable id of the document (derived from its path, not its content)
 * @param version    document version (always 1 until Story 2, Task 2)
 * @param source     file name relative to the resources folder
 * @param page       1-based PDF page the chunk comes from
 * @param chunkIndex position of the chunk inside the document, starting at 0
 * @param checksum   SHA-256 of the whole file
 * @param section    last heading seen before the chunk (may be null)
 */
public record Chunk(String docId, int version, String source, int page, int chunkIndex,
                    String checksum, String section, String text) {

    /**
     * Deterministic id, so storing the same chunk twice overwrites instead of duplicating.
     * It is a UUID because Spring AI's pgvector table uses a uuid primary key.
     */
    public String id() {
        String key = docId + ":" + checksum + ":" + chunkIndex;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** The metadata schema stored next to every vector. */
    public Map<String, Object> metadata(String embeddingModel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("doc_id", docId);
        m.put("version", version);
        m.put("source", source);
        m.put("page", page);
        m.put("chunk_index", chunkIndex);
        m.put("checksum", checksum);
        m.put("embedding_model", embeddingModel);
        if (section != null && !section.isBlank()) {
            m.put("section", section);
        }
        return m;
    }
}
