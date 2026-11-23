package com.example.rag.ingest.support;

import com.example.rag.ingest.model.Chunk;
import com.example.rag.ingest.store.ChunkRepository;
import com.example.rag.ingest.store.StoredChunk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test double: same id overwrites, like the real vector store. */
public class InMemoryChunkRepository implements ChunkRepository {

    public final Map<String, Chunk> byId = new LinkedHashMap<>();
    public boolean failOnSave;
    public int saveCalls;

    @Override
    public boolean existsByDocIdAndChecksum(String docId, String checksum) {
        return byId.values().stream().anyMatch(c -> c.docId().equals(docId) && c.checksum().equals(checksum));
    }

    @Override
    public boolean existsByDocId(String docId) {
        return countByDocId(docId) > 0;
    }

    @Override
    public void saveAll(List<Chunk> chunks) {
        saveCalls++;
        // store half first, then fail, to prove the service cleans up partial writes
        for (int i = 0; i < chunks.size(); i++) {
            if (failOnSave && i == chunks.size() / 2) {
                throw new IllegalStateException("embedding service unavailable");
            }
            byId.put(chunks.get(i).id(), chunks.get(i));
        }
    }

    @Override
    public int deleteByDocId(String docId) {
        int before = byId.size();
        byId.values().removeIf(c -> c.docId().equals(docId));
        return before - byId.size();
    }

    @Override
    public long countByDocId(String docId) {
        return byId.values().stream().filter(c -> c.docId().equals(docId)).count();
    }

    @Override
    public List<StoredChunk> sample(int count) {
        return byId.entrySet().stream().limit(count)
                .map(e -> new StoredChunk(e.getKey(), e.getValue().text(), e.getValue().metadata("test").toString()))
                .toList();
    }
}
