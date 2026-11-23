package com.example.rag.ingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds the {@code app.*} block of application.yml.
 * Chunk size and overlap are approximate tokens (1 token ~ 4 characters).
 */
@ConfigurationProperties(prefix = "app")
public record IngestProperties(
        @DefaultValue Resources resources,
        @DefaultValue Chunk chunk,
        @DefaultValue("unknown") String embeddingModel,
        @DefaultValue Ingest ingest) {

    public record Resources(@DefaultValue("./resources") String path) {
    }

    public record Chunk(@DefaultValue("800") int size, @DefaultValue("100") int overlap) {
    }

    public record Ingest(@DefaultValue("true") boolean onStartup) {
    }
}
