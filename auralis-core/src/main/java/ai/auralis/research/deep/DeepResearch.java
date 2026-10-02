package ai.auralis.research.deep;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Multi-step web research for questions the knowledge base cannot answer.
 * Implemented by AgentHarness, run locally or on a Kaggle GPU.
 */
public interface DeepResearch {

    record Query(String id, String question) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Finding(String id, String question, String answer, List<String> sources,
                   @JsonProperty("duration_seconds") double durationSeconds, String error) {

        public boolean ok() {
            return error == null && answer != null && !answer.isBlank();
        }
    }

    /** Researches all queries as one batch; results keep query ids. */
    List<Finding> research(List<Query> queries);
}
