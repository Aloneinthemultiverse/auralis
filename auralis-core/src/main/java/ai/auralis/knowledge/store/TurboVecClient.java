package ai.auralis.knowledge.store;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Client for the TurboVec service (TurboQuant-compressed nearest-neighbour index). */
@Component
public class TurboVecClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hits(List<Long> ids, List<Double> scores) {
    }

    private final RestClient http;

    public TurboVecClient(RestClient.Builder builder, @Value("${auralis.vector.turbovec-url}") String url) {
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(5));
        f.setReadTimeout(Duration.ofSeconds(120));
        this.http = builder.clone().baseUrl(url).requestFactory(f).build();
    }

    public void add(List<Long> ids, List<float[]> vectors) {
        if (ids.isEmpty()) {
            return;
        }
        post("/add", Map.of("ids", ids, "vectors", lists(vectors)));
    }

    public void calibrate(List<float[]> sample) {
        post("/calibrate", Map.of("vectors", lists(sample)));
    }

    /** Nearest ids; {@code allowlist} null = everything, empty = nothing. */
    public List<Long> search(float[] query, int k, List<Long> allowlist) {
        var body = new java.util.HashMap<String, Object>();
        body.put("vector", lists(List.of(query)).getFirst());
        body.put("k", k);
        if (allowlist != null) {
            body.put("allowlist", allowlist);
        }
        Hits h = http.post().uri("/search").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).body(body).retrieve().body(Hits.class);
        return h == null || h.ids() == null ? List.of() : h.ids();
    }

    private void post(String path, Object body) {
        http.post().uri(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .body(body).retrieve().toBodilessEntity();
    }

    private static List<List<Float>> lists(List<float[]> vs) {
        List<List<Float>> out = new ArrayList<>(vs.size());
        for (float[] v : vs) {
            List<Float> row = new ArrayList<>(v.length);
            for (float x : v) {
                row.add(x);
            }
            out.add(row);
        }
        return out;
    }
}
