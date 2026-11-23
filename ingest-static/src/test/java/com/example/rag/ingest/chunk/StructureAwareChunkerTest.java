package com.example.rag.ingest.chunk;

import com.example.rag.ingest.model.Chunk;
import com.example.rag.ingest.model.PageText;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructureAwareChunkerTest {

    private static final String DOC = "doc-test";
    private static final String CHECKSUM = "abc123";

    private final StructureAwareChunker chunker = new StructureAwareChunker(200, 20);

    private static String sentences(int count, String topic) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> "Sentence " + i + " describes the " + topic + " rule for category " + i + ".")
                .collect(Collectors.joining(" "));
    }

    private List<Chunk> chunk(PageText... pages) {
        return chunker.chunk(DOC, 1, "policy.pdf", CHECKSUM, List.of(pages));
    }

    private static String lastWords(String text, int n) {
        String[] words = text.strip().split("\\s+");
        return String.join(" ", java.util.Arrays.copyOfRange(words, Math.max(0, words.length - n), words.length));
    }

    @Test
    void headingStartsNewChunkAndSetsSection() {
        String page = "1. Introduction\n" + sentences(4, "intro") + "\n2. Data Retention\n" + sentences(4, "retention");

        List<Chunk> chunks = chunk(new PageText(1, page));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).section()).isEqualTo("1. Introduction");
        assertThat(chunks.get(1).section()).isEqualTo("2. Data Retention");
        assertThat(chunks.get(0).text()).doesNotContain("Data Retention");
    }

    @Test
    void longParagraphIsSplitWithinSizeLimitAndOverlaps() {
        List<Chunk> chunks = chunk(new PageText(1, sentences(40, "storage")));

        assertThat(chunks.size()).isGreaterThan(2);
        for (Chunk c : chunks) {
            assertThat(StructureAwareChunker.tokens(c.text())).isLessThanOrEqualTo(200);
        }
        for (int i = 1; i < chunks.size(); i++) {
            String endOfPrevious = lastWords(chunks.get(i - 1).text(), 3);
            assertThat(chunks.get(i).text()).contains(endOfPrevious);
        }
    }

    @Test
    void zeroOverlapProducesNoSharedText() {
        StructureAwareChunker noOverlap = new StructureAwareChunker(200, 0);
        List<Chunk> chunks = noOverlap.chunk(DOC, 1, "policy.pdf", CHECKSUM, List.of(new PageText(1, sentences(40, "storage"))));

        assertThat(chunks.size()).isGreaterThan(2);
        assertThat(chunks.get(1).text()).doesNotContain(lastWords(chunks.get(0).text(), 3));
    }

    @Test
    void chunkIndexIsSequentialAndPageIsExact() {
        List<Chunk> chunks = chunk(
                new PageText(1, "1. First\n" + sentences(30, "alpha")),
                new PageText(2, "2. Second\n" + sentences(30, "beta")));

        assertThat(chunks).extracting(Chunk::chunkIndex)
                .containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks).filteredOn(c -> c.text().contains("alpha")).extracting(Chunk::page).containsOnly(1);
        assertThat(chunks).filteredOn(c -> c.text().contains("beta")).extracting(Chunk::page).containsOnly(2);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.docId()).isEqualTo(DOC);
            assertThat(c.version()).isEqualTo(1);
            assertThat(c.source()).isEqualTo("policy.pdf");
            assertThat(c.checksum()).isEqualTo(CHECKSUM);
        });
    }

    @Test
    void tableStaysInOneChunkAndRowsAreFormatted() {
        String page = "3. Fees\n" + sentences(3, "fee") + "\n"
                + "Item   Qty   Price\nRow 1   10   $5.00\nRow 2   20   $6.00\nRow 3   30   $7.00\nRow 4   40   $8.00\n"
                + sentences(3, "closing");

        List<Chunk> chunks = chunk(new PageText(1, page));

        Chunk withTable = chunks.stream().filter(c -> c.text().contains("Row 1")).findFirst().orElseThrow();
        assertThat(withTable.text()).contains("Row 2 ", "Row 3", "Row 4");
        assertThat(withTable.text()).contains("Row 1 | 10 | $5.00");
    }

    @Test
    void oversizedTableIsSplitBetweenRowsOnly() {
        String rows = IntStream.rangeClosed(1, 120)
                .mapToObj(i -> "Row " + i + "   " + i * 10 + "   $" + i + ".00")
                .collect(Collectors.joining("\n"));

        List<Chunk> chunks = chunk(new PageText(1, "4. Big table\n" + rows));

        assertThat(chunks.size()).isGreaterThan(1);
        for (Chunk c : chunks) {
            assertThat(StructureAwareChunker.tokens(c.text())).isLessThanOrEqualTo(200);
            for (String line : c.text().split("\n")) {
                if (line.startsWith("Row")) {
                    assertThat(line).matches("Row \\d+ \\| \\d+ \\| \\$\\d+\\.00");
                }
            }
        }
    }

    @Test
    void garbledPageIsSkippedAndOthersAreKept() {
        String garbage = "\uFFFD\uFFFD\uFFFD\uE001\uE002 \uFFFD\uFFFD\uFFFD\uFFFD\uE003\uE004 \uFFFD\uFFFD\uFFFD\uFFFD\uFFFD";

        List<Chunk> chunks = chunk(new PageText(1, garbage), new PageText(2, "5. Clean\n" + sentences(3, "clean")));

        assertThat(chunks).isNotEmpty().extracting(Chunk::page).containsOnly(2);
    }

    @Test
    void emptyInputGivesNoChunks() {
        assertThat(chunker.chunk(DOC, 1, "x.pdf", CHECKSUM, List.of())).isEmpty();
        assertThat(chunk(new PageText(1, "   \n  "))).isEmpty();
    }

    @Test
    void sameInputGivesSameChunksAndIds() {
        PageText page = new PageText(1, "1. A\n" + sentences(30, "gamma"));
        List<Chunk> first = chunk(page);
        List<Chunk> second = chunk(page);

        assertThat(second).isEqualTo(first);
        assertThat(second.stream().map(Chunk::id)).containsExactlyElementsOf(first.stream().map(Chunk::id).toList());
        assertThat(first.stream().map(Chunk::id).distinct().count()).isEqualTo(first.size());
    }

    @Test
    void metadataHasTheFullSchema() {
        Chunk chunk = chunk(new PageText(1, "1. Intro\n" + sentences(2, "x"))).get(0);

        assertThat(chunk.metadata("text-embedding-3-small")).containsKeys(
                "doc_id", "version", "source", "page", "chunk_index", "checksum", "embedding_model", "section");
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThatThrownBy(() -> new StructureAwareChunker(50, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructureAwareChunker(200, 150)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructureAwareChunker(200, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
