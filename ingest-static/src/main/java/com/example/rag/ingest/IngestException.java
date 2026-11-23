package com.example.rag.ingest;

/** A problem with one document (unreadable, empty, outside the resources folder...). */
public class IngestException extends RuntimeException {
    public IngestException(String message) {
        super(message);
    }

    public IngestException(String message, Throwable cause) {
        super(message, cause);
    }
}
