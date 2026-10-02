package ai.auralis.laya;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import ai.auralis.config.AuralisProperties;

/**
 * Client for Laya, the fast decision layer: typed decisions over a text state
 * in one forward pass, no generation. Used for control questions (sufficient?
 * relevant? retry?) so the main LLM is not spent on them.
 */
@Component
public class LayaClient {

    private final RestClient http;

    public LayaClient(RestClient.Builder builder, AuralisProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.laya().timeout());
        factory.setReadTimeout(props.laya().timeout());
        this.http = builder.clone()
                .baseUrl(props.laya().baseUrl())
                .requestFactory(factory)
                .build();
    }

    /** Answers every question about {@code state} in a single call. */
    public Map<String, LayaAnswer> decide(String state, Map<String, LayaQuestion> questions) {
        Response response = http.post()
                .uri("/v1/systemone")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of("state", state, "questions", questions))
                .retrieve()
                .body(Response.class);
        if (response == null || response.answers() == null) {
            throw new IllegalStateException("Laya returned no answers");
        }
        return response.answers();
    }

    public LayaAnswer decide(String state, String id, LayaQuestion question) {
        return decide(state, Map.of(id, question)).get(id);
    }

    /**
     * Answers the same questions over many states in one request (shared forward
     * passes, and a single admission slot on the server). Results keep input order.
     */
    public List<Map<String, LayaAnswer>> decideBatch(List<String> states,
                                                     Map<String, LayaQuestion> questions) {
        BatchResponse response = http.post()
                .uri("/v1/systemone/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of("states", states, "questions", questions))
                .retrieve()
                .body(BatchResponse.class);
        if (response == null || response.results() == null
                || response.results().size() != states.size()) {
            throw new IllegalStateException("Laya batch returned a mismatched result set");
        }
        return response.results().stream().map(Response::answers).toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(Map<String, LayaAnswer> answers) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BatchResponse(List<Response> results) {
    }
}
