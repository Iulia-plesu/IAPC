package com.example.rag.ingest.chunk;

import com.example.rag.ingest.model.Chunk;
import com.example.rag.ingest.model.PageText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits pages into chunks while respecting the structure of the text:
 * <ul>
 *   <li>a heading closes the current chunk and starts a new one (no overlap across headings);</li>
 *   <li>a table is kept together and only split between rows, if it is too big;</li>
 *   <li>paragraphs are split on sentence boundaries;</li>
 *   <li>consecutive chunks of the same section share an overlap taken from the end of the previous one;</li>
 *   <li>chunks never cross a page boundary, so the page metadata is always exact.</li>
 * </ul>
 * Sizes are approximate tokens (1 token ~ 4 characters). No chunk is larger than {@code maxTokens}.
 */
public class StructureAwareChunker {

    private static final Logger log = LoggerFactory.getLogger(StructureAwareChunker.class);

    /** Space kept free for a heading that leads a chunk (headings are at most 100 characters). */
    private static final int HEADING_RESERVE = 26;
    private static final int MIN_SIZE = 100;

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[\\p{Lu}\\d])");
    private static final Pattern WIDE_SEPARATOR = Pattern.compile("\\t+| {2,}|\\s*\\|\\s*");
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^(?:\\d+(?:\\.\\d+){0,3}\\.?|[IVX]+\\.|(?:Chapter|Section|Article|Appendix)\\s+\\w+[.:]?)\\s+\\p{Lu}.{0,90}$");
    private static final Pattern NUMERIC_CELL = Pattern.compile("^[-+$\u20AC\u00A3]?\\d[\\d.,%]*$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private enum Type { HEADING, PARAGRAPH, TABLE }

    private record Block(Type type, String text) {
    }

    private record Draft(String text, String section) {
    }

    private final int maxTokens;
    private final int overlapTokens;
    private final int pieceMaxTokens;

    public StructureAwareChunker(int maxTokens, int overlapTokens) {
        if (maxTokens < MIN_SIZE) {
            throw new IllegalArgumentException("Chunk size must be at least " + MIN_SIZE + " tokens, got " + maxTokens);
        }
        if (overlapTokens < 0 || overlapTokens > maxTokens / 2) {
            throw new IllegalArgumentException(
                    "Chunk overlap must be between 0 and " + (maxTokens / 2) + " tokens (half the chunk size), got " + overlapTokens);
        }
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
        this.pieceMaxTokens = maxTokens - overlapTokens - HEADING_RESERVE;
    }

    /** Approximate token count. */
    static int tokens(String text) {
        return (text.length() + 3) / 4;
    }

    public List<Chunk> chunk(String docId, int version, String source, String checksum, List<PageText> pages) {
        List<Chunk> result = new ArrayList<>();
        String section = null;
        int index = 0;
        for (PageText page : pages) {
            if (TextCleaner.isGarbled(page.text())) {
                log.warn("Skipping garbled page {} of {}", page.page(), source);
                continue;
            }
            String cleaned = TextCleaner.clean(page.text());
            if (cleaned.isBlank()) {
                log.debug("Page {} of {} has no text", page.page(), source);
                continue;
            }

            List<Draft> drafts = new ArrayList<>();
            StringBuilder buffer = new StringBuilder();
            boolean hasBody = false;

            for (Block block : parseBlocks(cleaned)) {
                if (block.type() == Type.HEADING) {
                    if (hasBody) {
                        drafts.add(new Draft(buffer.toString().strip(), section));
                    }
                    section = block.text();
                    buffer.setLength(0);
                    buffer.append(block.text());
                    hasBody = false;
                    continue;
                }
                for (String piece : pieces(block)) {
                    int needed = tokens(piece) + 1;
                    if (hasBody && tokens(buffer.toString()) + needed > maxTokens) {
                        String emitted = buffer.toString().strip();
                        drafts.add(new Draft(emitted, section));
                        buffer.setLength(0);
                        String tail = overlapTail(emitted);
                        if (!tail.isEmpty()) {
                            buffer.append(tail);
                        }
                        hasBody = false;
                    }
                    if (buffer.length() > 0) {
                        buffer.append('\n');
                    }
                    buffer.append(piece);
                    hasBody = true;
                }
            }
            if (hasBody) {
                drafts.add(new Draft(buffer.toString().strip(), section));
            }

            for (Draft draft : drafts) {
                if (!draft.text().isBlank()) {
                    result.add(new Chunk(docId, version, source, page.page(), index++, checksum,
                            draft.section(), draft.text()));
                }
            }
        }
        log.info("Chunked {} into {} chunks (size={}, overlap={})", source, result.size(), maxTokens, overlapTokens);
        return result;
    }

    // ---------- structure detection ----------

    private List<Block> parseBlocks(String cleaned) {
        List<Block> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        List<String> tableRows = new ArrayList<>();

        for (String rawLine : cleaned.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                flushParagraph(paragraph, blocks);
                flushTable(tableRows, blocks);
                continue;
            }
            if (isWideTableRow(line) || (!tableRows.isEmpty() && isNumericRow(line))) {
                flushParagraph(paragraph, blocks);
                tableRows.add(formatRow(line));
                continue;
            }
            flushTable(tableRows, blocks);
            if (isHeading(line)) {
                flushParagraph(paragraph, blocks);
                blocks.add(new Block(Type.HEADING, line));
            } else if (isNumericRow(line)) {
                flushParagraph(paragraph, blocks);
                tableRows.add(formatRow(line));
            } else {
                if (paragraph.length() > 0) {
                    paragraph.append(' ');
                }
                paragraph.append(line);
            }
        }
        flushParagraph(paragraph, blocks);
        flushTable(tableRows, blocks);
        return blocks;
    }

    private void flushParagraph(StringBuilder paragraph, List<Block> blocks) {
        if (paragraph.length() > 0) {
            blocks.add(new Block(Type.PARAGRAPH, WHITESPACE.matcher(paragraph).replaceAll(" ").strip()));
            paragraph.setLength(0);
        }
    }

    private void flushTable(List<String> rows, List<Block> blocks) {
        if (rows.size() >= 2) {
            blocks.add(new Block(Type.TABLE, String.join("\n", rows)));
        } else if (rows.size() == 1) {
            blocks.add(new Block(Type.PARAGRAPH, rows.get(0)));
        }
        rows.clear();
    }

    private boolean isWideTableRow(String line) {
        return line.length() <= 200 && WIDE_SEPARATOR.split(line).length >= 2;
    }

    /** A short row with at least 3 cells where at least one is a number, e.g. "Alice 30 5000". */
    private boolean isNumericRow(String line) {
        if (line.length() > 80 || NUMBERED_HEADING.matcher(line).matches() || line.matches(".*[.!?:]$")) {
            return false;
        }
        String[] cells = WHITESPACE.split(line);
        if (cells.length < 3 || cells.length > 8) {
            return false;
        }
        return Arrays.stream(cells).anyMatch(c -> NUMERIC_CELL.matcher(c).matches());
    }

    private String formatRow(String line) {
        return String.join(" | ", WIDE_SEPARATOR.split(line.strip()));
    }

    private boolean isHeading(String line) {
        if (line.length() > 100 || line.matches(".*[.,;:]$")) {
            return false;
        }
        if (NUMBERED_HEADING.matcher(line).matches()) {
            return true;
        }
        int letters = 0;
        boolean lower = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                lower |= Character.isLowerCase(c);
            }
        }
        return letters >= 4 && !lower && line.length() <= 80;
    }

    // ---------- splitting and overlap ----------

    /** A block that fits is one piece; a bigger block is split on rows (tables) or sentences. */
    private List<String> pieces(Block block) {
        if (tokens(block.text()) <= pieceMaxTokens) {
            return List.of(block.text());
        }
        boolean table = block.type() == Type.TABLE;
        List<String> units = Arrays.asList(table ? block.text().split("\n") : SENTENCE_END.split(block.text()));
        String separator = table ? "\n" : " ";
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            for (String part : hardSplit(unit)) {
                if (current.length() > 0 && tokens(current.toString()) + tokens(part) + 1 > pieceMaxTokens) {
                    pieces.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append(separator);
                }
                current.append(part);
            }
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    /** Splits a single unit (sentence or row) that is still too big, on word boundaries. */
    private List<String> hardSplit(String unit) {
        if (tokens(unit) <= pieceMaxTokens) {
            return List.of(unit);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : WHITESPACE.split(unit)) {
            if (current.length() > 0 && tokens(current.toString()) + tokens(word) + 1 > pieceMaxTokens) {
                parts.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    /** The last ~overlapTokens of a chunk, starting on a word boundary. */
    private String overlapTail(String text) {
        int maxChars = overlapTokens * 4;
        if (maxChars == 0 || text.length() <= maxChars) {
            return "";
        }
        String tail = text.substring(text.length() - maxChars);
        // Prefer starting on a line (keeps table rows whole) if that still leaves most of the overlap.
        int newline = tail.indexOf('\n');
        if (newline >= 0 && newline < tail.length() / 2) {
            return tail.substring(newline + 1).strip();
        }
        int firstBreak = indexOfWhitespace(tail);
        if (firstBreak >= 0 && firstBreak < tail.length() - 1) {
            tail = tail.substring(firstBreak + 1);
        }
        return tail.strip();
    }

    private int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
