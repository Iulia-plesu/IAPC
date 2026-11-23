package com.example.rag.ingest.model;

/** Raw text of one PDF page (page numbers start at 1). */
public record PageText(int page, String text) {
}
