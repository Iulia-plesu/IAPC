package com.example.rag.ingest.service;

import com.example.rag.ingest.config.IngestProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Scans the resources folder once when the service is ready (app.ingest.on-startup=true). */
@Component
public class StartupIngestion {

    private static final Logger log = LoggerFactory.getLogger(StartupIngestion.class);

    private final IngestionService service;
    private final IngestProperties properties;

    public StartupIngestion(IngestionService service, IngestProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!properties.ingest().onStartup()) {
            log.info("Startup ingestion disabled");
            return;
        }
        // Own thread: embedding calls can be slow and must not delay the health endpoint.
        Thread.ofVirtual().name("startup-ingest").start(() -> {
            try {
                service.ingestAll().forEach(r ->
                        log.info("Startup ingest: {} -> {} ({} chunks)", r.source(), r.status(), r.chunks()));
            } catch (RuntimeException e) {
                log.error("Startup ingestion failed: {}", e.getMessage());
            }
        });
    }
}
