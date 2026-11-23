package com.example.rag.ingest.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Stable document id and content checksum. */
public final class DocumentIdentity {

    private DocumentIdentity() {
    }

    /**
     * doc_id depends only on the file's path inside the resources folder (case-insensitive,
     * '/' separators). It stays the same when the content changes, which Task 2 (update) needs.
     */
    public static String docId(Path resourcesRoot, Path file) {
        String relative = resourcesRoot.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return "doc-" + sha256(relative.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    /** SHA-256 of the file bytes, hex encoded. */
    public static String checksum(Path file) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static String sha256(byte[] data) {
        return HexFormat.of().formatHex(digest().digest(data));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
