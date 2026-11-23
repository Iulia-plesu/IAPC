package com.example.rag.ingest.chunk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextCleanerTest {

    @Test
    void joinsWordsSplitByLineEndHyphen() {
        assertThat(TextCleaner.clean("The retention pol-\nicy applies")).isEqualTo("The retention policy applies");
    }

    @Test
    void expandsLigaturesAndRemovesInvisibleAndControlCharacters() {
        String raw = "o\uFB03ce\u00AD hours\u0000 and \uFB01les\u200B";
        assertThat(TextCleaner.clean(raw)).isEqualTo("office hours  and files");
    }

    @Test
    void keepsLineBreaksAndCollapsesBlankLines() {
        assertThat(TextCleaner.clean("a\r\nb\n\n\n\nc  \n")).isEqualTo("a\nb\n\nc");
    }

    @Test
    void normalTextIsNotGarbled() {
        assertThat(TextCleaner.isGarbled("Employees must complete the training (see section 2.1) within 30 days."))
                .isFalse();
    }

    @Test
    void textFullOfReplacementCharactersIsGarbled() {
        assertThat(TextCleaner.isGarbled("\uFFFD\uFFFD\uFFFD\uE001\uE002 \uFFFD\uFFFD\uFFFD\uFFFD\uE003\uE004 \uFFFD\uFFFD\uFFFD ok"))
                .isTrue();
    }

    @Test
    void nullAndShortTextAreSafe() {
        assertThat(TextCleaner.clean(null)).isEmpty();
        assertThat(TextCleaner.isGarbled("\uFFFD")).isFalse();
    }
}
