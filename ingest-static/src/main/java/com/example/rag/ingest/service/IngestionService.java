package com.example.rag.ingest.service;

import com.example.rag.ingest.IngestException;
import com.example.rag.ingest.chunk.StructureAwareChunker;
import com.example.rag.ingest.config.IngestProperties;
import com.example.rag.ingest.model.Chunk;
import com.example.rag.ingest.model.DocumentIdentity;
import com.example.rag.ingest.model.PageText;
import com.example.rag.ingest.pdf.PdfPageExtractor;
import com.example.rag.ingest.service.IngestResult.Status;
import com.example.rag.ingest.store.ChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Story 1, Task 1: PDF -> text -> structure-aware chunks -> embeddings -> vector store. */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int VERSION = 1;

    private final ChunkRepository repository;
    private final PdfPageExtractor extractor;
    private final StructureAwareChunker chunker;
    private final Path root;

    public IngestionService(ChunkRepository repository, PdfPageExtractor extractor, IngestProperties properties) {
        this.repository = repository;
        this.extractor = extractor;
        this.chunker = new StructureAwareChunker(properties.chunk().size(), properties.chunk().overlap());
        this.root = Path.of(properties.resources().path()).toAbsolutePath().normalize();
    }

    /** Ingests every PDF under the resources folder. A failing file never stops the others. */
    public List<IngestResult> ingestAll() {
        if (!Files.isDirectory(root)) {
            throw new IngestException("Resources folder does not exist: " + root);
        }
        List<Path> pdfs;
        try (Stream<Path> files = Files.walk(root)) {
            pdfs = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IngestException("Cannot list " + root + ": " + e.getMessage(), e);
        }
        log.info("Found {} PDF files in {}", pdfs.size(), root);

        List<IngestResult> results = new ArrayList<>();
        for (Path pdf : pdfs) {
            try {
                results.add(ingestFile(pdf));
            } catch (RuntimeException e) {
                log.error("Ingest failed for {}: {}", pdf.getFileName(), e.getMessage());
                results.add(new IngestResult(null, relative(pdf), Status.FAILED, 0, e.getMessage()));
            }
        }
        return results;
    }

    /** Ingests one file given by its name relative to the resources folder. */
    public IngestResult ingestByName(String name) {
        return ingestFile(root.resolve(name));
    }

    IngestResult ingestFile(Path file) {
        Path path = file.toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            throw new IngestException("File must be inside the resources folder");
        }
        if (!Files.isRegularFile(path)) {
            throw new IngestException("File not found: " + relative(path));
        }
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IngestException("Only PDF files are supported: " + relative(path));
        }

        String source = relative(path);
        String docId = DocumentIdentity.docId(root, path);
        String checksum;
        try {
            checksum = DocumentIdentity.checksum(path);
        } catch (IOException e) {
            throw new IngestException("Cannot read " + source + ": " + e.getMessage(), e);
        }

        if (repository.existsByDocIdAndChecksum(docId, checksum)) {
            log.info("Skipping {} (doc_id={}): unchanged", source, docId);
            return new IngestResult(docId, source, Status.SKIPPED_UNCHANGED,
                    (int) repository.countByDocId(docId), "Already ingested, same checksum");
        }
        if (repository.existsByDocId(docId)) {
            log.warn("{} changed since it was ingested. Replacing old chunks is handled in Task 2", source);
            return new IngestResult(docId, source, Status.CHANGED_NOT_UPDATED, 0,
                    "File changed; the update process (Task 2) is not implemented yet. Stored chunks left untouched");
        }

        List<PageText> pages = extractor.extract(path);
        List<Chunk> chunks = chunker.chunk(docId, VERSION, source, checksum, pages);
        if (chunks.isEmpty()) {
            throw new IngestException("No extractable text in " + source + " (scanned or garbled PDF?)");
        }

        try {
            repository.saveAll(chunks);
        } catch (RuntimeException e) {
            // A half-stored document would later be mistaken for a complete one, so remove it.
            log.error("Storing {} failed, removing partial chunks: {}", source, e.getMessage());
            repository.deleteByDocId(docId);
            throw new IngestException("Storing " + source + " failed: " + e.getMessage(), e);
        }
        log.info("Ingested {} (doc_id={}, {} pages, {} chunks)", source, docId, pages.size(), chunks.size());
        return new IngestResult(docId, source, Status.INGESTED, chunks.size(), "Ingested");
    }

    private String relative(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        return (abs.startsWith(root) ? root.relativize(abs) : abs).toString().replace('\\', '/');
    }
}
