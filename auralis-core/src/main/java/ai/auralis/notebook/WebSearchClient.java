package ai.auralis.notebook;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Web search through the self-hosted SearXNG instance (free, keyless). */
@Component
public class WebSearchClient {

    public record Hit(String title, String url, String snippet, String engine) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper json;
    private final String base;

    public WebSearchClient(JsonMapper json, @Value("${auralis.searxng-url:http://localhost:8888}") String base) {
        this.json = json;
        this.base = base;
    }

    public List<Hit> search(String query, int max) {
        List<Hit> out = new ArrayList<>();
        for (int page = 1; page <= 2 && out.size() < max; page++) {
            try {
                String url = base + "/search?format=json&language=en&pageno=" + page + "&q="
                        + URLEncoder.encode(query, StandardCharsets.UTF_8);
                var resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    break;
                }
                for (JsonNode r : json.readTree(resp.body()).path("results")) {
                    if (out.size() >= max) {
                        break;
                    }
                    out.add(new Hit(r.path("title").asString(""), r.path("url").asString(""),
                            r.path("content").asString(""), r.path("engine").asString("")));
                }
            } catch (Exception e) {
                break;
            }
        }
        return out;
    }
}
