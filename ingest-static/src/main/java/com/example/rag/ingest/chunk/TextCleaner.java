package com.example.rag.ingest.chunk;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Cleans text extracted from PDFs so chunks contain no broken or garbled characters. */
public final class TextCleaner {

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");
    private static final Pattern INVISIBLE = Pattern.compile("[\\u00AD\\u200B\\u200C\\u200D\\uFEFF\\uFFFD]");
    private static final Pattern HYPHEN_BREAK = Pattern.compile("(\\p{L})-\\n(\\p{Ll})");
    private static final Pattern TRAILING_SPACE = Pattern.compile("[ \\t\\u00A0]+(?=\\n)");
    private static final Pattern MANY_BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final String ALLOWED_PUNCTUATION = ".,;:!?()[]{}'\"-\u2013\u2014/%&@#$\u20AC\u00A3+=*_<>|\u2018\u2019\u201C\u201D\u2022\u00B0";

    private TextCleaner() {
    }

    /**
     * Normalises unicode (ligatures such as "fi" become two letters), removes control and
     * invisible characters, joins words split by a line-end hyphen and trims whitespace.
     * Line breaks are kept: the chunker uses them to find headings and table rows.
     */
    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String text = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        text = text.replace("\r\n", "\n").replace('\r', '\n').replace('\f', '\n');
        text = INVISIBLE.matcher(text).replaceAll("");
        text = CONTROL.matcher(text).replaceAll(" ");
        text = TRAILING_SPACE.matcher(text).replaceAll("");
        text = HYPHEN_BREAK.matcher(text).replaceAll("$1$2");
        text = MANY_BLANK_LINES.matcher(text).replaceAll("\n\n");
        return text.strip();
    }

    /**
     * True when a large share of the characters are not letters, digits, spaces or normal
     * punctuation (typical for PDFs with broken font encodings). Call it on the RAW text,
     * because {@link #clean} removes the replacement characters.
     */
    public static boolean isGarbled(String raw) {
        if (raw == null || raw.length() < 20) {
            return false;
        }
        int bad = 0;
        int total = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            total++;
            boolean fine = Character.isLetterOrDigit(c) || ALLOWED_PUNCTUATION.indexOf(c) >= 0;
            if (!fine) {
                bad++;
            }
        }
        return total > 0 && (double) bad / total > 0.30;
    }
}
