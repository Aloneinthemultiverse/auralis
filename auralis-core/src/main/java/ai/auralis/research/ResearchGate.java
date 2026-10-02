package ai.auralis.research;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import ai.auralis.config.AuralisProperties;
import ai.auralis.decision.DecisionService;
import ai.auralis.decision.DecisionService.Decision;
import ai.auralis.decision.DecisionService.Policy;
import ai.auralis.decision.DecisionService.Spec;

/**
 * The research loop's control decisions, all through {@link DecisionService}
 * (Laya first, recorded in the ledger). Zero-shot Laya judges topic well but not
 * whether evidence answers a specific question, so answerability is advisory:
 * Laya votes, the analyst's verdict stands.
 */
@Component
public class ResearchGate {

    private static final String SCOPE = "research";

    /** The state of the whole research at the end of a round. */
    public record Sufficiency(boolean sufficient, Double pSufficient, String sufficientBy,
                              boolean contradiction, String next) {
    }

    private final DecisionService decisions;
    private final double minConfidence;

    public ResearchGate(DecisionService decisions, AuralisProperties props) {
        this.decisions = decisions;
        this.minConfidence = props.research().sufficientThreshold();
    }

    public Sufficiency sufficiency(UUID sessionId, String objective, String state) {
        Decision suff = decisions.decide(Spec.yesNo(SCOPE, sessionId, "sufficiency",
                Policy.LAYA_THEN_LLM,
                "Is this research enough to make an engineering decision for: " + objective,
                state).withMinConfidence(minConfidence));
        Decision contra = decisions.decide(Spec.yesNo(SCOPE, sessionId, "contradiction",
                Policy.LAYA, "Do any of these findings contradict each other?", state));
        Map<String, String> next = new LinkedHashMap<>();
        next.put("research", "important facts are still missing and need more research");
        next.put("design", "the findings are enough to start designing");
        next.put("ask_user", "requirements from the user are missing or ambiguous");
        Decision step = decisions.decide(Spec.choice(SCOPE, sessionId, "next_step",
                Policy.LAYA, "What should happen next?", next, state));
        return new Sufficiency(suff.yes(),
                suff.layaProbabilities() == null ? null : suff.layaProbabilities().get("yes"),
                suff.decidedBy(), contra.yes(), step.answer());
    }

    /** Records the analyst's answerability verdict with Laya's vote alongside it. */
    public void answerability(UUID sessionId, String question, String evidence,
                              boolean answered, String missing) {
        decisions.recordWithLlmVerdict(Spec.yesNo(SCOPE, sessionId, "answerability",
                Policy.LAYA_ADVISORY, "Does this evidence answer the question: " + question,
                evidence), answered, answered ? "evidence states the answer" : "missing: " + missing);
    }
}
