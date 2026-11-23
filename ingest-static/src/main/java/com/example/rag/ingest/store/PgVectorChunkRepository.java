package com.example.rag.ingest.store;

import com.example.rag.ingest.config.IngestProperties;
import com.example.rag.ingest.model.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores chunks through Spring AI's {@link VectorStore} (which calls the embedding model)
 * and uses plain SQL on the vector_store table for lookups on the metadata.
 */
@Repository
public class PgVectorChunkRepository implements ChunkRepository {

    private static final Logger log = LoggerFactory.getLogger(PgVectorChunkRepository.class);
    private static final int BATCH_SIZE = 50;

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;
    private final IngestProperties properties;

    public PgVectorChunkRepository(VectorStore vectorStore, JdbcTemplate jdbc, IngestProperties properties) {
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public boolean existsByDocIdAndChecksum(String docId, String checksum) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vector_store WHERE metadata->>'doc_id' = ? AND metadata->>'checksum' = ?",
                Long.class, docId, checksum);
        return count != null && count > 0;
    }

    @Override
    public boolean existsByDocId(String docId) {
        return countByDocId(docId) > 0;
    }

    @Override
    public void saveAll(List<Chunk> chunks) {
        List<Document> batch = new ArrayList<>(BATCH_SIZE);
        for (Chunk chunk : chunks) {
            batch.add(Document.builder()
                    .id(chunk.id())
                    .text(chunk.text())
                    .metadata(chunk.metadata(properties.embeddingModel()))
                    .build());
            if (batch.size() == BATCH_SIZE) {
                vectorStore.add(List.copyOf(batch));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            vectorStore.add(List.copyOf(batch));
        }
        log.info("Stored {} chunks", chunks.size());
    }

    @Override
    public int deleteByDocId(String docId) {
        int deleted = jdbc.update("DELETE FROM vector_store WHERE metadata->>'doc_id' = ?", docId);
        log.info("Deleted {} chunks of {}", deleted, docId);
        return deleted;
    }

    @Override
    public long countByDocId(String docId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vector_store WHERE metadata->>'doc_id' = ?", Long.class, docId);
        return count == null ? 0 : count;
    }

    @Override
    public List<StoredChunk> sample(int count) {
        return jdbc.query(
                "SELECT id::text, content, metadata::text FROM vector_store ORDER BY random() LIMIT ?",
                (rs, i) -> new StoredChunk(rs.getString(1), rs.getString(2), rs.getString(3)),
                count);
    }
}
