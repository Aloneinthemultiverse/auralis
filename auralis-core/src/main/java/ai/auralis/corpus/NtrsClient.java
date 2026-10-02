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

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * NASA Technical Reports Server: decades of public-domain aerospace reports
 * (propulsion, combustion, structures, aerodynamics, avionics) with full-text
 * PDFs. Free and keyless. Only records with a downloadable PDF are returned.
 */
@Component
public class NtrsClient {

    private static final String BASE = "https://ntrs.nasa.gov";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final JsonMapper json;

    public NtrsClient(JsonMapper json) {
        this.json = json;
    }

    public List<Candidate> search(String query, int max, int page) throws Exception {
        String url = BASE + "/api/citations/search?page.size=" + max + "&page.from=" + (page * max)
                + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        var resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Auralis/0.1 (open research)").build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("NTRS HTTP " + resp.statusCode());
        }
        List<Candidate> out = new ArrayList<>();
        for (JsonNode r : json.readTree(resp.body()).path("results")) {
            String pdf = null;
            for (JsonNode d : r.path("downloads")) {
                String link = d.path("links").path("pdf").asString("");
                if (!link.isBlank() && !d.path("draft").asBoolean(false)) {
                    pdf = BASE + link;
                    break;
                }
            }
            if (pdf == null || !"PUBLIC".equals(r.path("distribution").asString("PUBLIC"))) {
                continue;
            }
            String id = r.path("id").asString();
            Integer year = null;
            String date = r.path("publications").path(0).path("publicationDate").asString(
                    r.path("submittedDate").asString(""));
            if (date.length() >= 4 && date.substring(0, 4).matches("\\d{4}")) {
                year = Integer.parseInt(date.substring(0, 4));
            }
            List<String> authors = new ArrayList<>();
            for (JsonNode a : r.path("authorAffiliations")) {
                if (authors.size() < 5) {
                    authors.add(a.path("meta").path("author").path("name").asString(""));
                }
            }
            out.add(new Candidate("ntrs:" + id, r.path("title").asString(id), r.path("abstract").asString(""),
                    String.join(", ", authors), year, BASE + "/citations/" + id, pdf,
                    "NASA (public domain)", "nasa-ntrs", query, 0, null, List.of(), List.of()));
        }
        return out;
    }
}
