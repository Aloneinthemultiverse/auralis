package ai.auralis.corpus;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * arXiv API (export.arxiv.org): free, keyless. Searches given categories for a
 * topic; every arXiv paper has a free PDF. Calls are spaced 3 s apart as arXiv asks.
 */
@Component
public class ArxivClient {

    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final long MIN_GAP_MS = 3100;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private long lastCall;

    public synchronized List<Candidate> search(String topic, List<String> categories, int max)
            throws Exception {
        long wait = lastCall + MIN_GAP_MS - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        String cats = categories.stream().map(c -> "cat:" + c).collect(Collectors.joining(" OR "));
        String terms = java.util.Arrays.stream(topic.split("\\s+")).limit(6)
                .map(w -> "all:" + w).collect(Collectors.joining(" AND "));
        String q = "(" + cats + ") AND " + terms;
        String url = "https://export.arxiv.org/api/query?search_query="
                + URLEncoder.encode(q, StandardCharsets.UTF_8)
                + "&sortBy=relevance&max_results=" + max;
        var resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Auralis-Corpus/0.1").build(), HttpResponse.BodyHandlers.ofByteArray());
        lastCall = System.currentTimeMillis();
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("arXiv HTTP " + resp.statusCode());
        }
        var dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(resp.body()));
        NodeList entries = doc.getElementsByTagNameNS(ATOM, "entry");
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < entries.getLength(); i++) {
            Element e = (Element) entries.item(i);
            String id = text(e, "id");                      // http://arxiv.org/abs/2503.04426v2
            String arxivId = id.substring(id.lastIndexOf("/abs/") + 5).replaceAll("v\\d+$", "");
            String pdf = null;
            NodeList links = e.getElementsByTagNameNS(ATOM, "link");
            for (int j = 0; j < links.getLength(); j++) {
                Element l = (Element) links.item(j);
                if ("pdf".equals(l.getAttribute("title"))) {
                    pdf = l.getAttribute("href");
                }
            }
            NodeList names = e.getElementsByTagNameNS(ATOM, "name");
            List<String> authors = new ArrayList<>();
            for (int j = 0; j < names.getLength() && j < 6; j++) {
                authors.add(names.item(j).getTextContent().strip());
            }
            String published = text(e, "published");
            out.add(new Candidate("arxiv:" + arxivId, clean(text(e, "title")), clean(text(e, "summary")),
                    String.join(", ", authors),
                    published.length() >= 4 ? Integer.parseInt(published.substring(0, 4)) : null,
                    "https://arxiv.org/abs/" + arxivId,
                    pdf != null ? pdf : "https://arxiv.org/pdf/" + arxivId,
                    "arXiv (free to read)", "arxiv", topic, 0, null, List.of()));
        }
        return out;
    }

    private static String text(Element e, String tag) {
        NodeList n = e.getElementsByTagNameNS(ATOM, tag);
        return n.getLength() == 0 ? "" : n.item(0).getTextContent();
    }

    private static String clean(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }
}
