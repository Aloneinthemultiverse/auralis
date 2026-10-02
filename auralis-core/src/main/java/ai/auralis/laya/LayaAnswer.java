package ai.auralis.laya;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One answer from Laya. Which value field is set depends on {@link #type()}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LayaAnswer(
        String type,
        String choice,
        Double score,
        @JsonProperty("noul") Double yesProbability,
        Map<String, Double> probabilities,
        double confidence,
        @JsonProperty("answer_confidence") double answerConfidence) {
}
