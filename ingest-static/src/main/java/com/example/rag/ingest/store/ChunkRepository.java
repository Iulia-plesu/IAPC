package com.example.rag.ingest.store;

import com.example.rag.ingest.model.Chunk;

import java.util.List;

/** Storage port for chunks. The real implementation uses pgvector; tests use a map. */
public interface ChunkRepository {

    boolean existsByDocIdAndChecksum(String docId, String checksum);

    boolean existsByDocId(String docId);

    /** Embeds and stores the chunks. Chunks with the same id overwrite each other. */
    void saveAll(List<Chunk> chunks);

    /** Removes every chunk of a document (used to clean up after a failed ingest). */
    int deleteByDocId(String docId);

    long countByDocId(String docId);

    /** Random chunks, for the manual "no garbled text" check. */
    List<StoredChunk> sample(int count);
}
