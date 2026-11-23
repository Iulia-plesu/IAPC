package com.example.rag.ingest.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentIdentityTest {

    @TempDir
    Path root;

    @Test
    void docIdIsStableAndIgnoresContentAndCase() throws IOException {
        Path file = Files.writeString(root.resolve("Policy.pdf"), "v1");
        String first = DocumentIdentity.docId(root, file);
        Files.writeString(file, "v2 with different content");

        assertThat(DocumentIdentity.docId(root, file)).isEqualTo(first);
        assertThat(DocumentIdentity.docId(root, root.resolve("policy.PDF"))).isEqualTo(first);
        assertThat(first).startsWith("doc-").hasSize(20);
    }

    @Test
    void differentPathsGiveDifferentIds() {
        assertThat(DocumentIdentity.docId(root, root.resolve("a.pdf")))
                .isNotEqualTo(DocumentIdentity.docId(root, root.resolve("b.pdf")))
                .isNotEqualTo(DocumentIdentity.docId(root, root.resolve("sub/a.pdf")));
    }

    @Test
    void checksumChangesWithContent() throws IOException {
        Path file = Files.writeString(root.resolve("a.pdf"), "same");
        String c1 = DocumentIdentity.checksum(file);
        assertThat(DocumentIdentity.checksum(file)).isEqualTo(c1);
        Files.writeString(file, "changed");
        assertThat(DocumentIdentity.checksum(file)).isNotEqualTo(c1).hasSize(64);
    }

    @Test
    void checksumOfMissingFileFails() {
        assertThatThrownBy(() -> DocumentIdentity.checksum(root.resolve("nope.pdf")))
                .isInstanceOf(NoSuchFileException.class);
    }
}
