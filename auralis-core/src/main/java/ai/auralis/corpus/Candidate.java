package ai.auralis.corpus;

import java.util.List;
import java.util.Locale;

/**
 * A discovered work that may join the corpus.
 *
 * @param key        stable dedup key: doi:…, arxiv:… or url:…
 * @param openAlexId OpenAlex work id when known (for citation edges)
 * @param references OpenAlex ids this work cites
 * @param altPdfUrls further open-access copies to try when {@code pdfUrl} refuses the download
 */
public record Candidate(String key, String title, String abstractText, String authors, Integer year,
                        String landingUrl, String pdfUrl, String license, String source,
                        String topic, int citedBy, String openAlexId, List<String> references,
                        List<String> altPdfUrls) {

    /** All PDF URLs to try, in order. */
    public List<String> pdfUrls() {
        List<String> all = new java.util.ArrayList<>();
        all.add(pdfUrl);
        altPdfUrls.stream().filter(u -> !u.equals(pdfUrl)).forEach(all::add);
        return all;
    }

    /** Text Laya judges relevance on. */
    public String brief() {
        String a = abstractText == null ? "" : abstractText;
        return title + ". " + (a.length() > 400 ? a.substring(0, 400) : a); // short: Laya runs on CPU
    }

    public static String normTitle(String t) {
        return t == null ? "" : t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }
}
