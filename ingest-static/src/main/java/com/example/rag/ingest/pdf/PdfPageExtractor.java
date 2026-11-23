package com.example.rag.ingest.pdf;

import com.example.rag.ingest.IngestException;
import com.example.rag.ingest.model.PageText;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a PDF page by page so every chunk can carry its page number.
 *
 * <p>Text is read in content-stream order (sortByPosition = false). For multi-column
 * documents this normally keeps each column together instead of mixing lines of
 * neighbouring columns.
 */
@Component
public class PdfPageExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfPageExtractor.class);

    public List<PageText> extract(Path file) {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(false);
            int pageCount = document.getNumberOfPages();
            List<PageText> pages = new ArrayList<>(pageCount);
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(new PageText(page, stripper.getText(document)));
            }
            log.info("Extracted {} pages from {}", pageCount, file.getFileName());
            return pages;
        } catch (IOException e) {
            log.error("Cannot read PDF {}: {}", file.getFileName(), e.getMessage());
            throw new IngestException("Cannot read PDF " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }
}
