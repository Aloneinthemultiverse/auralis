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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bulk sources for building a large legal corpus: whole arXiv categories,
 * Berkeley EECS technical reports and theses, open-licensed / public-domain
 * Internet Archive texts, and Wikibooks print editions. Each returns
 * candidates; Laya decides which are kept.
 */
@Component
public class HarvestSources {

    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final Pattern BERKELEY_ITEM = Pattern.compile(
            "href=\"/Pubs/TechRpts/(\\d{4})/(EECS-\\d{4}-\\d+)\\.html\">([^<]+)");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final JsonMapper json;
    private long lastArxiv;

    public HarvestSources(JsonMapper json) {
        this.json = json;
    }

    /** One page of a whole arXiv category, newest first (call with increasing start). */
    public synchronized List<Candidate> arxivCategory(String category, int start, int pageSize) throws Exception {
        long wait = lastArxiv + 3100 - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        String url = "https://export.arxiv.org/api/query?search_query=" + URLEncoder.encode("cat:" + category, StandardCharsets.UTF_8)
                + "&sortBy=submittedDate&sortOrder=descending&start=" + start + "&max_results=" + pageSize;
        byte[] body = get(url).getBytes(StandardCharsets.UTF_8);
        lastArxiv = System.currentTimeMillis();
        var dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList entries = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(body)).getElementsByTagNameNS(ATOM, "entry");
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < entries.getLength(); i++) {
            Element e = (Element) entries.item(i);
            String id = text(e, "id");
            String arxivId = id.substring(id.lastIndexOf("/abs/") + 5).replaceAll("v\\d+$", "");
            String published = text(e, "published");
            NodeList names = e.getElementsByTagNameNS(ATOM, "name");
            List<String> authors = new ArrayList<>();
            for (int j = 0; j < names.getLength() && j < 6; j++) {
                authors.add(names.item(j).getTextContent().strip());
            }
            out.add(new Candidate("arxiv:" + arxivId, clean(text(e, "title")), clean(text(e, "summary")),
                    String.join(", ", authors), published.length() >= 4 ? Integer.parseInt(published.substring(0, 4)) : null,
                    "https://arxiv.org/abs/" + arxivId, "https://arxiv.org/pdf/" + arxivId,
                    "arXiv (free to read)", "arxiv:" + category, category, 0, null, List.of(), List.of()));
        }
        return out;
    }

    /** All Berkeley EECS technical reports (incl. PhD theses) of one year. */
    public List<Candidate> berkeleyYear(int year) throws Exception {
        String html = get("https://www2.eecs.berkeley.edu/Pubs/TechRpts/" + year + "/");
        List<Candidate> out = new ArrayList<>();
        Matcher m = BERKELEY_ITEM.matcher(html);
        while (m.find()) {
            String report = m.group(2);
            String title = m.group(3).replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").strip();
            out.add(new Candidate("berkeley:" + report, title, "UC Berkeley EECS technical report " + report,
                    "UC Berkeley EECS", year, "https://www2.eecs.berkeley.edu/Pubs/TechRpts/" + year + "/" + report + ".html",
                    "https://www2.eecs.berkeley.edu/Pubs/TechRpts/" + year + "/Archive/" + report + ".pdf",
                    "UC Berkeley technical report (free)", "berkeley-eecs", "theses and technical reports", 0, null,
                    List.of(), List.of()));
        }
        return out;
    }

    /** Internet Archive texts that are open-licensed or marked not in copyright. */
    public List<Candidate> internetArchive(String subjectQuery, int rows, int page) throws Exception {
        String q = "(" + subjectQuery + ") AND mediatype:texts AND (licenseurl:* OR possible-copyright-status:NOT_IN_COPYRIGHT)";
        String url = "https://archive.org/advancedsearch.php?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                + "&fl[]=identifier&fl[]=title&fl[]=licenseurl&fl[]=year&fl[]=description&fl[]=creator"
                + "&rows=" + rows + "&page=" + page + "&output=json";
        JsonNode docs = json.readTree(get(url)).path("response").path("docs");
        List<Candidate> out = new ArrayList<>();
        for (JsonNode d : docs) {
            String id = d.path("identifier").asString();
            String desc = d.path("description").isArray() ? d.path("description").path(0).asString("")
                    : d.path("description").asString("");
            Integer year = null;
            try {
                year = Integer.parseInt(d.path("year").asString("").replaceAll("\\D", "").substring(0, 4));
            } catch (Exception ignored) {
                // no usable year
            }
            out.add(new Candidate("ia:" + id, d.path("title").asString(id), desc.replaceAll("<[^>]+>", " "),
                    d.path("creator").isArray() ? d.path("creator").path(0).asString("") : d.path("creator").asString(""),
                    year, "https://archive.org/details/" + id, "ia-pdf:" + id,
                    d.path("licenseurl").asString("public domain"), "internet-archive", "books", 0, null, List.of(), List.of()));
        }
        return out;
    }

    /** Resolves an Internet Archive item to its PDF URL (items name their files differently). */
    public String resolveInternetArchivePdf(String identifier) throws Exception {
        JsonNode files = json.readTree(get("https://archive.org/metadata/" + identifier)).path("files");
        String best = null;
        long bestSize = -1;
        for (JsonNode f : files) {
            String name = f.path("name").asString();
            if (name.toLowerCase().endsWith(".pdf")) {
                long size = f.path("size").asLong(0);
                if (size > bestSize) {
                    best = name;
                    bestSize = size;
                }
            }
        }
        if (best == null) {
            throw new IllegalStateException("no PDF in this item");
        }
        return "https://archive.org/download/" + identifier + "/" + URLEncoder.encode(best, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String get(String url) throws Exception {
        var resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90))
                .header("User-Agent", "Auralis-Corpus/0.1 (open research corpus)").build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    private static String text(Element e, String tag) {
        NodeList n = e.getElementsByTagNameNS(ATOM, tag);
        return n.getLength() == 0 ? "" : n.item(0).getTextContent();
    }

    private static String clean(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }
}
