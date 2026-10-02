package ai.auralis.knowledge.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.auralis.laya.LayaAnswer;
import ai.auralis.laya.LayaClient;
import ai.auralis.laya.LayaQuestion;

/**
 * Reranks fused candidates by asking Laya, per passage, whether it helps answer
 * the query — all passages in one batch request. If Laya is unavailable, fusion
 * order is kept and relevance stays null.
 */
@Component
public class LayaReranker {

    private static final Logger log = LoggerFactory.getLogger(LayaReranker.class);
    private static final int MAX_PASSAGE_CHARS = 800;
    private static final String ID = "relevant";
    private static final double RELEVANCE_WEIGHT = 0.5;

    private final LayaClient laya;

    public LayaReranker(LayaClient laya) {
        this.laya = laya;
    }

    public List<Evidence> rerank(String query, List<Evidence> candidates) {
        LayaQuestion question = LayaQuestion.yesNo(
                "Does this passage help answer: " + query);
        try {
            List<String> states = candidates.stream().map(e -> truncate(e.content())).toList();
            List<Map<String, LayaAnswer>> answers = laya.decideBatch(states, Map.of(ID, question));
            List<Evidence> out = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                out.add(candidates.get(i).withRelevance(answers.get(i).get(ID).yesProbability()));
            }
            // Zero-shot relevance is noisy on its own, so blend it with the fused
            // retrieval score (normalised to the best candidate) instead of
            // replacing it: a passage has to look good to both. Stable on ties.
            double maxFused = out.stream().mapToDouble(Evidence::fusedScore).max().orElse(1.0);
            out.sort(Comparator.comparingDouble(
                    (Evidence e) -> blended(e, maxFused)).reversed());
            return out;
        } catch (Exception ex) {
            log.warn("Laya rerank unavailable, keeping fusion order", ex);
            return candidates;
        }
    }

    static double blended(Evidence e, double maxFused) {
        return RELEVANCE_WEIGHT * e.relevance()
                + (1 - RELEVANCE_WEIGHT) * (e.fusedScore() / maxFused);
    }

    private static String truncate(String s) {
        return s.length() <= MAX_PASSAGE_CHARS ? s : s.substring(0, MAX_PASSAGE_CHARS);
    }
}
