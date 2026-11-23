package com.example.rag.ingest.service;

import com.example.rag.ingest.IngestException;
import com.example.rag.ingest.config.IngestProperties;
import com.example.rag.ingest.model.Chunk;
import com.example.rag.ingest.pdf.PdfPageExtractor;
import com.example.rag.ingest.service.IngestResult.Status;
import com.example.rag.ingest.support.InMemoryChunkRepository;
import com.example.rag.ingest.support.PdfTestFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionServiceTest {

    @TempDir
    Path resources;

    InMemoryChunkRepository repository = new InMemoryChunkRepository();
    IngestionService service;

    @BeforeEach
    void setUp() {
        IngestProperties props = new IngestProperties(
                new IngestProperties.Resources(resources.toString()),
                new IngestProperties.Chunk(200, 20),
                "test-embedding-model",
                new IngestProperties.Ingest(false));
        service = new IngestionService(repository, new PdfPageExtractor(), props);
    }

    private Path policyPdf(String name, String topic) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("1. Introduction");
        IntStream.rangeClosed(1, 40).forEach(i ->
                lines.add("Sentence " + i + " explains the " + topic + " rule number " + i + " in plain words."));
        return PdfTestFiles.singleColumn(resources.resolve(name),
                List.of(lines.subList(0, 20), List.of("2. Second page"), lines.subList(20, 41)));
    }

    @Test
    void ingestsAPdfWithFullMetadataOnEveryChunk() throws IOException {
        policyPdf("policy.pdf", "retention");

        IngestResult result = service.ingestByName("policy.pdf");

        assertThat(result.status()).isEqualTo(Status.INGESTED);
        assertThat(result.chunks()).isEqualTo(repository.byId.size()).isGreaterThan(1);
        for (Chunk c : repository.byId.values()) {
            assertThat(c.metadata("test-embedding-model")).containsKeys(
                    "doc_id", "version", "source", "page", "chunk_index", "checksum");
            assertThat(c.docId()).isEqualTo(result.docId());
            assertThat(c.source()).isEqualTo("policy.pdf");
            assertThat(c.page()).isBetween(1, 3);
            assertThat(c.text()).isNotBlank();
        }
    }

    @Test
    void reRunningTheSameFileCreatesNoDuplicates() throws IOException {
        policyPdf("policy.pdf", "retention");

        IngestResult first = service.ingestByName("policy.pdf");
        int stored = repository.byId.size();
        IngestResult second = service.ingestByName("policy.pdf");

        assertThat(second.status()).isEqualTo(Status.SKIPPED_UNCHANGED);
        assertThat(second.docId()).isEqualTo(first.docId());
        assertThat(repository.byId).hasSize(stored);
        assertThat(repository.saveCalls).isEqualTo(1);
    }

    @Test
    void storingTheSameChunksTwiceStillGivesNoDuplicates() throws IOException {
        policyPdf("policy.pdf", "retention");
        service.ingestByName("policy.pdf");
        List<Chunk> stored = new ArrayList<>(repository.byId.values());

        repository.saveAll(stored); // ids are deterministic, so this overwrites

        assertThat(repository.byId).hasSize(stored.size());
    }

    @Test
    void changedFileIsDetectedAndOldChunksAreLeftUntouched() throws IOException {
        policyPdf("policy.pdf", "retention");
        service.ingestByName("policy.pdf");
        int stored = repository.byId.size();

        policyPdf("policy.pdf", "changed content").toFile().setLastModified(System.currentTimeMillis());
        IngestResult result = service.ingestByName("policy.pdf");

        assertThat(result.status()).isEqualTo(Status.CHANGED_NOT_UPDATED);
        assertThat(repository.byId).hasSize(stored);
    }

    @Test
    void failedStorageRemovesPartialChunks() throws IOException {
        policyPdf("policy.pdf", "retention");
        repository.failOnSave = true;

        assertThatThrownBy(() -> service.ingestByName("policy.pdf"))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("policy.pdf");
        assertThat(repository.byId).isEmpty();

        // after the failure a retry works, because no half-stored document was left behind
        repository.failOnSave = false;
        assertThat(service.ingestByName("policy.pdf").status()).isEqualTo(Status.INGESTED);
    }

    @Test
    void scanContinuesAfterABrokenFile() throws IOException {
        policyPdf("good.pdf", "retention");
        Files.writeString(resources.resolve("broken.pdf"), "not a pdf");

        List<IngestResult> results = service.ingestAll();

        assertThat(results).hasSize(2);
        assertThat(results).filteredOn(r -> r.source().equals("broken.pdf"))
                .singleElement().satisfies(r -> assertThat(r.status()).isEqualTo(Status.FAILED));
        assertThat(results).filteredOn(r -> r.source().equals("good.pdf"))
                .singleElement().satisfies(r -> assertThat(r.status()).isEqualTo(Status.INGESTED));
    }

    @Test
    void pdfWithoutTextIsRejected() throws IOException {
        PdfTestFiles.singleColumn(resources.resolve("empty.pdf"), List.of(List.of(" ")));

        assertThatThrownBy(() -> service.ingestByName("empty.pdf"))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("No extractable text");
        assertThat(repository.byId).isEmpty();
    }

    @Test
    void pathsOutsideTheResourcesFolderAreRejected() throws IOException {
        Path outside = Files.writeString(resources.getParent().resolve("outside.pdf"), "x");
        try {
            assertThatThrownBy(() -> service.ingestByName("../outside.pdf"))
                    .isInstanceOf(IngestException.class)
                    .hasMessageContaining("inside the resources folder");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void missingAndNonPdfFilesAreRejected() throws IOException {
        Files.writeString(resources.resolve("notes.txt"), "hello");

        assertThatThrownBy(() -> service.ingestByName("nope.pdf"))
                .isInstanceOf(IngestException.class).hasMessageContaining("not found");
        assertThatThrownBy(() -> service.ingestByName("notes.txt"))
                .isInstanceOf(IngestException.class).hasMessageContaining("Only PDF");
    }
}
