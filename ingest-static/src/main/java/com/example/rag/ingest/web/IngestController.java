package com.example.rag.ingest.web;

import com.example.rag.ingest.IngestException;
import com.example.rag.ingest.service.IngestResult;
import com.example.rag.ingest.service.IngestionService;
import com.example.rag.ingest.store.ChunkRepository;
import com.example.rag.ingest.store.StoredChunk;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Through the gateway: POST /ingest-static/ingest/scan, POST /ingest-static/ingest/file?name=..., GET /ingest-static/ingest/sample */
@RestController
@RequestMapping("/ingest")
public class IngestController {

    private final IngestionService service;
    private final ChunkRepository repository;

    public IngestController(IngestionService service, ChunkRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    /** Ingests every PDF in the resources folder. */
    @PostMapping("/scan")
    public List<IngestResult> scan() {
        return service.ingestAll();
    }

    /** Ingests one PDF, e.g. name=sample-tables.pdf */
    @PostMapping("/file")
    public IngestResult file(@RequestParam String name) {
        return service.ingestByName(name);
    }

    /** Random stored chunks, for the manual check of garbled text (default 20, max 100). */
    @GetMapping("/sample")
    public List<StoredChunk> sample(@RequestParam(defaultValue = "20") int count) {
        return repository.sample(Math.max(1, Math.min(count, 100)));
    }

    @ExceptionHandler(IngestException.class)
    public ResponseEntity<Map<String, String>> handle(IngestException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
