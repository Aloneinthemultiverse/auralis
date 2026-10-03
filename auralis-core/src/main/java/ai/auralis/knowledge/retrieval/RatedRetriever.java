package ai.auralis.knowledge.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import ai.auralis.decision.DecisionService;
import ai.auralis.decision.DecisionService.Decision;
import ai.auralis.decision.DecisionService.Policy;
import ai.auralis.decision.DecisionService.Spec;

/**
 * Retrieval with a Laya-rated refinement loop: retrieve, let Laya rate whether the
 * evidence answers the question (SUFFICIENT / REFINE), and on REFINE expand the query
 * with distinctive terms from the evidence, retrieve again and merge. Laya makes every
 * stop/continue decision (recorded in the decision ledger).
 * Stops on SUFFICIENT or after {@code maxRounds}, returning the merged best evidence.
 */
@Service
public class RatedRetriever {


    /** One round: the query used, Laya's verdict on the evidence it returned, and its probability. */
    public record Round(int n, String query, String verdict, Double layaP) {
    }

    public record Result(List<Evidence> evidence, List<Round> rounds, boolean sufficient) {
    }

    private final HybridRetriever retriever;
    private final DecisionService decisions;

    public RatedRetriever(HybridRetriever retriever, DecisionService decisions) {
        this.retriever = retriever;
        this.decisions = decisions;
    }

    public Result retrieve(String question, int topK, int maxRounds, Scope scope) {
        Map<UUID, Evidence> merged = new LinkedHashMap<>();
        List<Round> rounds = new ArrayList<>();
        String query = question;
        boolean sufficient = false;
        for (int n = 1; n <= Math.max(1, maxRounds); n++) {
            List<Evidence> hits = retriever.retrieve(query, topK, true, scope);
            for (Evidence e : hits) {
                merged.merge(e.chunkId(), e, (a, b) -> score(a) >= score(b) ? a : b);
            }
            List<Evidence> best = best(merged, topK);
            if (best.isEmpty()) {
                rounds.add(new Round(n, query, "EMPTY", null));
                break;
            }
            Decision d = decisions.decide(Spec.yesNo("retrieval", null, "evidence_sufficiency", Policy.LAYA,
                    "Do the sources cover the need?", state(question, best)));
            rounds.add(new Round(n, query, d.yes() ? "SUFFICIENT" : "REFINE", d.layaP()));
            if (d.yes()) {
                sufficient = true;
                break;
            }
            if (n < maxRounds) {
                query = expand(question, rounds, best);
            }
        }
        return new Result(best(merged, topK), rounds, sufficient);
    }

    private static double score(Evidence e) {
        return e.relevance() != null ? e.relevance() : e.fusedScore();
    }

    private static List<Evidence> best(Map<UUID, Evidence> merged, int topK) {
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(RatedRetriever::score).reversed())
                .limit(topK).toList();
    }

    /**
     * Short state for Laya. Calibration lesson (as for routing): the need plus source titles only;
     * passage text makes Laya answer REFINE even for excellent evidence.
     */
    static String state(String question, List<Evidence> evidence) {
        return "Need: " + question + ". Sources: " + evidence.stream().map(Evidence::documentTitle)
                .distinct().limit(5).collect(Collectors.joining("; "));
    }

    /**
     * Query refinement without an LLM (keeps Ollama free for embeddings): add the most frequent
     * distinctive terms of the retrieved passages that were not queried yet.
     */
    static String expand(String question, List<Round> rounds, List<Evidence> best) {
        Set<String> used = rounds.stream().flatMap(r -> words(r.query()).stream()).collect(Collectors.toSet());
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (Evidence e : best) {
            for (String w : words(e.content())) {
                if (w.length() > 4 && !used.contains(w)) {
                    freq.merge(w, 1, Integer::sum);
                }
            }
        }
        String extra = freq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(6).map(Map.Entry::getKey).collect(Collectors.joining(" "));
        return extra.isBlank() ? question : question + " " + extra;
    }

    private static List<String> words(String text) {
        return java.util.Arrays.stream(text.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9-]+"))
                .filter(w -> !w.isBlank()).toList();
    }
}
