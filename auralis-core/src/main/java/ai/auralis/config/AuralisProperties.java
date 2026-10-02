package ai.auralis.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auralis")
public record AuralisProperties(
        Embedding embedding,
        Chunking chunking,
        Retrieval retrieval,
        Laya laya,
        Research research,
        DeepResearch deepResearch) {

    public record Embedding(int dimensions, int batchSize) {
    }

    public record Chunking(int targetChars, int overlapChars) {
    }

    public record Retrieval(int candidatesPerRetriever, int rrfK, int rerankTopN) {
    }

    public record Laya(String baseUrl, Duration timeout) {
    }

    /** @param sufficientThreshold Laya P(enough evidence to decide) needed to stop the loop */
    public record Research(int maxRounds, int maxQuestionsPerRound, int evidencePerQuestion,
                           double sufficientThreshold) {
    }

    public record DeepResearch(String agentHarnessDir, String python, Duration localTimeout,
                               String kaggleDir, String bash, Duration kaggleTimeout,
                               int maxTurns) {
    }
}
