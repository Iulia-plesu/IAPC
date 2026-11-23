package com.example.rag.ingest.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Builds small PDFs for tests (ASCII text only). */
public final class PdfTestFiles {

    private PdfTestFiles() {
    }

    /** One text block per page, one line per list entry. */
    public static Path singleColumn(Path target, List<List<String>> pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (List<String> lines : pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    writeBlock(cs, font, 50, 780, lines);
                }
            }
            doc.save(target.toFile());
        }
        return target;
    }

    /** One page with two columns: the left column is drawn first, then the right one. */
    public static Path twoColumns(Path target, List<String> left, List<String> right) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeBlock(cs, font, 50, 780, left);
                writeBlock(cs, font, 320, 780, right);
            }
            doc.save(target.toFile());
        }
        return target;
    }

    private static void writeBlock(PDPageContentStream cs, PDType1Font font, float x, float y,
                                   List<String> lines) throws IOException {
        cs.beginText();
        cs.setFont(font, 11);
        cs.setLeading(14f);
        cs.newLineAtOffset(x, y);
        for (String line : lines) {
            cs.showText(line);
            cs.newLine();
        }
        cs.endText();
    }
}
