package ai.auralis.research;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import ai.auralis.laya.LayaAnswer;
import ai.auralis.laya.LayaClient;
import ai.auralis.laya.LayaQuestion;

/**
 * Laya's control decisions over the research state: fast typed judgements, so
 * the main LLM is only used for reasoning. Zero-shot Laya judges topic well
 * but not whether a passage answers a specific question, so per-question
 * answerability is left to the analyst.
 */
@Component
public class ResearchGate {

    /** Laya reads a bounded window; keep the most informative head of the state. */
    private static final int MAX_STATE_CHARS = 2400;

    /** Laya's view of the whole research state at the end of a round. */
    public record Sufficiency(double sufficient, double contradiction, String next,
                              Map<String, Double> nextProbabilities) {
    }

    private final LayaClient laya;

    public ResearchGate(LayaClient laya) {
        this.laya = laya;
    }

    public Sufficiency sufficiency(String objective, String state) {
        Map<String, LayaQuestion> qs = new LinkedHashMap<>();
        qs.put("sufficient", LayaQuestion.yesNo(
                "Is this research enough to make an engineering decision for: " + objective));
        qs.put("contradiction", LayaQuestion.yesNo(
                "Do any of these findings contradict each other?"));
        qs.put("next", LayaQuestion.choice("What should happen next?", Map.of(
                "design", "the findings are enough to start designing",
                "research", "important facts are still missing and need more research",
                "ask_user", "requirements from the user are missing or ambiguous")));
        Map<String, LayaAnswer> a = laya.decide(bounded(state), qs);
        return new Sufficiency(a.get("sufficient").yesProbability(),
                a.get("contradiction").yesProbability(),
                a.get("next").choice(), a.get("next").probabilities());
    }

    private static String bounded(String s) {
        return s.length() <= MAX_STATE_CHARS ? s : s.substring(0, MAX_STATE_CHARS);
    }
}
