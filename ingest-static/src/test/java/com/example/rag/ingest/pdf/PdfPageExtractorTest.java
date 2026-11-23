package com.example.rag.ingest.pdf;

import com.example.rag.ingest.IngestException;
import com.example.rag.ingest.model.PageText;
import com.example.rag.ingest.support.PdfTestFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfPageExtractorTest {

    private final PdfPageExtractor extractor = new PdfPageExtractor();

    @TempDir
    Path dir;

    @Test
    void extractsOnePageTextPerPdfPage() throws IOException {
        Path pdf = PdfTestFiles.singleColumn(dir.resolve("two-pages.pdf"),
                List.of(List.of("1. Introduction", "First page text."), List.of("2. Scope", "Second page text.")));

        List<PageText> pages = extractor.extract(pdf);

        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).page()).isEqualTo(1);
        assertThat(pages.get(0).text()).contains("First page text.").doesNotContain("Second");
        assertThat(pages.get(1).page()).isEqualTo(2);
        assertThat(pages.get(1).text()).contains("Second page text.");
    }

    @Test
    void keepsEachColumnTogether() throws IOException {
        Path pdf = PdfTestFiles.twoColumns(dir.resolve("columns.pdf"),
                List.of("LEFT line one", "LEFT line two", "LEFT line three"),
                List.of("RIGHT line one", "RIGHT line two", "RIGHT line three"));

        String text = extractor.extract(pdf).get(0).text();

        assertThat(text.indexOf("LEFT line three")).isGreaterThan(-1)
                .isLessThan(text.indexOf("RIGHT line one"));
    }

    @Test
    void corruptFileFailsWithIngestException() throws IOException {
        Path fake = Files.writeString(dir.resolve("fake.pdf"), "this is not a pdf");

        assertThatThrownBy(() -> extractor.extract(fake))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("fake.pdf");
    }

    @Test
    void missingFileFailsWithIngestException() {
        assertThatThrownBy(() -> extractor.extract(dir.resolve("missing.pdf")))
                .isInstanceOf(IngestException.class);
    }
}
