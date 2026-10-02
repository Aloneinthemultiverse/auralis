package ai.auralis.knowledge.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ai.auralis.config.AuralisProperties;

/**
 * Splits text into retrieval chunks of roughly {@code targetChars}, breaking on
 * paragraphs first, then sentences, and carrying {@code overlapChars} of trailing
 * context into the next chunk so a fact split across a boundary stays findable.
 */
@Component
public class TextChunker {

    private static final Pattern PARAGRAPH = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?])\\s+");

    private final int target;
    private final int overlap;

    @Autowired
    public TextChunker(AuralisProperties props) {
        this(props.chunking().targetChars(), props.chunking().overlapChars());
    }

    TextChunker(int targetChars, int overlapChars) {
        if (overlapChars >= targetChars) {
            throw new IllegalArgumentException("overlap must be smaller than target");
        }
        this.target = targetChars;
        this.overlap = overlapChars;
    }

    public List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units(text)) {
            if (current.length() > 0 && current.length() + unit.length() + 1 > target) {
                chunks.add(current.toString().strip());
                current = new StringBuilder(tail(current));
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(unit);
        }
        if (!current.toString().isBlank()) {
            chunks.add(current.toString().strip());
        }
        return chunks;
    }

    /** Paragraphs, with oversized ones split into sentences, then hard-split. */
    private List<String> units(String text) {
        List<String> units = new ArrayList<>();
        for (String para : PARAGRAPH.split(text.replace("\r\n", "\n"))) {
            String p = para.strip().replaceAll("[ \\t]+", " ");
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() <= target) {
                units.add(p);
                continue;
            }
            for (String sentence : SENTENCE.split(p)) {
                for (int i = 0; i < sentence.length(); i += target) {
                    units.add(sentence.substring(i, Math.min(sentence.length(), i + target)));
                }
            }
        }
        return units;
    }

    /** Last {@code overlap} chars, starting at a word boundary when possible. */
    private String tail(CharSequence s) {
        if (overlap == 0 || s.length() <= overlap) {
            return overlap == 0 ? "" : s.toString();
        }
        String t = s.subSequence(s.length() - overlap, s.length()).toString();
        int space = t.indexOf(' ');
        return space >= 0 ? t.substring(space + 1) : t;
    }
}
