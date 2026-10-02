package ai.auralis.corpus;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAlex (the open successor to Microsoft Academic): searches open-access
 * works that have a PDF, with citation counts and reference lists. Free daily
 * allowance; an optional free API key ({@code OPENALEX_API_KEY}) raises it.
 */
@Component
public class OpenAlexClient {

    private static final String FIELDS = "id,doi,display_name,publication_year,cited_by_count,"
            + "best_oa_location,locations,referenced_works,abstract_inverted_index,authorships,type";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final JsonMapper json;
    private final String apiKey;

    public OpenAlexClient(JsonMapper json, @Value("${OPENALEX_API_KEY:}") String apiKey) {
        this.json = json;
        this.apiKey = apiKey;
    }

    public List<Candidate> search(String topic, int max) throws Exception {
        String url = "https://api.openalex.org/works?search=" + URLEncoder.encode(topic, StandardCharsets.UTF_8)
                + "&filter=is_oa:true,has_content.pdf:true&per-page=" + max + "&select=" + FIELDS
                + (apiKey.isBlank() ? "" : "&api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
        var resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Auralis-Corpus/0.1").build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("OpenAlex HTTP " + resp.statusCode()
                    + (resp.statusCode() == 429 ? " (daily free allowance used up)" : ""));
        }
        JsonNode root = json.readTree(resp.body());
        List<Candidate> out = new ArrayList<>();
        for (JsonNode w : root.path("results")) {
            JsonNode oa = w.path("best_oa_location");
            // Repository copies (arXiv, institutional) download reliably; publisher copies
            // often block automated access, so they go last.
            List<String> repo = new ArrayList<>();
            List<String> other = new ArrayList<>();
            for (JsonNode loc : w.path("locations")) {
                String u = loc.path("pdf_url").asString("");
                if (!loc.path("is_oa").asBoolean(false) || u.isBlank()) {
                    continue;
                }
                ("repository".equals(loc.path("source").path("type").asString("")) ? repo : other).add(u);
            }
            String best = oa.path("pdf_url").asString("");
            if (!best.isBlank() && !repo.contains(best) && !other.contains(best)) {
                other.add(best);
            }
            List<String> pdfs = new ArrayList<>(repo);
            pdfs.addAll(other);
            if (pdfs.isEmpty()) {
                continue;
            }
            String pdf = pdfs.getFirst();
            String doi = w.path("doi").asString("");
            String id = w.path("id").asString();
            List<String> authors = new ArrayList<>();
            for (JsonNode a : w.path("authorships")) {
                if (authors.size() < 6) {
                    authors.add(a.path("author").path("display_name").asString(""));
                }
            }
            List<String> refs = new ArrayList<>();
            w.path("referenced_works").forEach(r -> refs.add(r.asString()));
            out.add(new Candidate(doi.isBlank() ? "openalex:" + id : "doi:" + doi.replace("https://doi.org/", "").toLowerCase(),
                    w.path("display_name").asString(""), abstractText(w.path("abstract_inverted_index")),
                    String.join(", ", authors),
                    w.path("publication_year").isNumber() ? w.path("publication_year").asInt() : null,
                    oa.path("landing_page_url").asString(doi), pdf,
                    oa.path("license").asString("open access"), "openalex", topic,
                    w.path("cited_by_count").asInt(0), id, refs, pdfs.subList(1, pdfs.size())));
        }
        return out;
    }

    /** OpenAlex stores abstracts as word → positions; rebuild the text. */
    static String abstractText(JsonNode inverted) {
        if (inverted == null || inverted.isNull() || inverted.isMissingNode()) {
            return "";
        }
        Map<Integer, String> words = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : inverted.properties()) {
            for (JsonNode pos : e.getValue()) {
                words.put(pos.asInt(), e.getKey());
            }
        }
        return String.join(" ", words.values());
    }
}
