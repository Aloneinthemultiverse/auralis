package ai.auralis.decision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import ai.auralis.laya.LayaAnswer;
import ai.auralis.laya.LayaClient;
import ai.auralis.laya.LayaQuestion;

/**
 * The one place Auralis makes control decisions. Laya always votes first; the
 * decision's {@link Policy} says whether the LLM is consulted. Every decision —
 * both votes, the final answer and who made it — goes to the ledger.
 */
@Service
public class DecisionService {

    private static final Logger log = LoggerFactory.getLogger(DecisionService.class);
    private static final String YES = "yes";
    private static final String NO = "no";
    private static final int MAX_LAYA_STATE = 2400;
    private static final int LAYA_BATCH = 16;

    /** Items the last subset selection could not get a Laya verdict for (thread-confined use). */
    private volatile int lastUnavailable;

    public enum Policy {
        /** Laya decides alone. For decisions Laya is reliable at, or bulk/latency-bound ones. */
        LAYA,
        /** Laya decides; the LLM decides instead when Laya's confidence is below the spec's minimum. */
        LAYA_THEN_LLM,
        /** Laya votes and is recorded; the LLM's answer is final. For decisions Laya is known to be weak at. */
        LAYA_ADVISORY
    }

    /**
     * What to decide.
     *
     * @param options       label → description for a choice; null for a yes/no decision
     * @param minConfidence for {@link Policy#LAYA_THEN_LLM}: Laya's answer probability needed
     */
    public record Spec(String scope, UUID scopeId, String kind, Policy policy, String question,
                       Map<String, String> options, String state, double minConfidence) {

        public static Spec yesNo(String scope, UUID scopeId, String kind, Policy policy,
                                 String question, String state) {
            return new Spec(scope, scopeId, kind, policy, question, null, state, 0.7);
        }

        public static Spec choice(String scope, UUID scopeId, String kind, Policy policy,
                                  String question, Map<String, String> options, String state) {
            return new Spec(scope, scopeId, kind, policy, question, options, state, 0.6);
        }

        public Spec withMinConfidence(double c) {
            return new Spec(scope, scopeId, kind, policy, question, options, state, c);
        }

        boolean isYesNo() {
            return options == null;
        }
    }

    /** The outcome. {@code confidence} is the deciding model's probability for its answer when known. */
    public record Decision(UUID id, String answer, String decidedBy, Double layaP,
                           Map<String, Double> layaProbabilities, String layaAnswer,
                           String rationale) {

        public boolean yes() {
            return YES.equals(answer);
        }
    }

    record LlmVerdict(String answer, String rationale) {
    }

    private final LayaClient laya;
    private final ChatClient chat;
    private final DecisionLedger ledger;

    public DecisionService(LayaClient laya, ChatClient.Builder chat, DecisionLedger ledger) {
        this.laya = laya;
        this.chat = chat.defaultSystem("""
                You make one control decision inside an engineering research and design
                system. Read the state, answer with exactly one of the allowed answers, and
                give a one-sentence rationale grounded in the state.""").build();
        this.ledger = ledger;
    }

    public Decision decide(Spec spec) {
        long t0 = System.nanoTime();
        LayaVote vote = layaVote(spec);
        boolean needLlm = switch (spec.policy()) {
            case LAYA -> vote == null;
            case LAYA_THEN_LLM -> vote == null || vote.p() < spec.minConfidence();
            case LAYA_ADVISORY -> true;
        };
        String finalAnswer;
        String decidedBy;
        LlmVerdict llm = null;
        if (needLlm) {
            llm = llmVote(spec);
            finalAnswer = llm != null ? llm.answer() : vote != null ? vote.answer() : fallback(spec);
            decidedBy = llm != null ? "LLM" : "LAYA";
        } else {
            finalAnswer = vote.answer();
            decidedBy = "LAYA";
        }
        return ledger.record(spec, vote, llm, finalAnswer, decidedBy, ms(t0));
    }

    /**
     * Records a decision whose authoritative answer the LLM already produced as part
     * of its own work (e.g. answerability judged while synthesizing). Laya still
     * votes, so its agreement is measured — no extra LLM call.
     */
    public Decision recordWithLlmVerdict(Spec spec, boolean llmYes, String rationale) {
        long t0 = System.nanoTime();
        LayaVote vote = layaVote(spec);
        LlmVerdict llm = new LlmVerdict(llmYes ? YES : NO, rationale);
        return ledger.record(spec, vote, llm, llm.answer(), "LLM", ms(t0));
    }

    /**
     * Picks which items are relevant to a task — e.g. which tools to hand the LLM for
     * a design step — with one batched Laya pass over the item descriptions. Items at
     * or above {@code threshold} are kept; if none clear it, the best {@code minKeep}
     * are. Recorded as one ledger decision with every item's probability.
     */
    public List<String> selectSubset(String scope, UUID scopeId, String kind, String question,
                                     Map<String, String> items, double threshold, int minKeep) {
        return selectScored(scope, scopeId, kind, question, items, threshold, minKeep).keySet()
                .stream().toList();
    }

    /** Like {@link #selectSubset}, returning each kept item with Laya's probability. */
    public Map<String, Double> selectScored(String scope, UUID scopeId, String kind, String question,
                                            Map<String, String> items, double threshold, int minKeep) {
        long t0 = System.nanoTime();
        if (items.isEmpty()) {
            return Map.of();
        }
        List<String> names = List.copyOf(items.keySet());
        Map<String, Double> p = new LinkedHashMap<>();
        // Laya on CPU needs ~1-2 s per item: decide in small batches so no request times
        // out, and degrade per batch (neutral 0.5, flagged) instead of for the whole set.
        int unavailable = 0;
        for (int from = 0; from < names.size(); from += LAYA_BATCH) {
            List<String> batch = names.subList(from, Math.min(names.size(), from + LAYA_BATCH));
            try {
                var states = batch.stream().map(n -> n + ": " + items.get(n)).toList();
                var answers = laya.decideBatch(states, Map.of("d", LayaQuestion.yesNo(question)));
                for (int i = 0; i < batch.size(); i++) {
                    p.put(batch.get(i), answers.get(i).get("d").yesProbability());
                }
            } catch (Exception ex) {
                unavailable += batch.size();
                batch.forEach(n -> p.put(n, 0.5));
                log.warn("Laya unavailable for part of {} ({} items, neutral 0.5): {}",
                        kind, batch.size(), ex.toString());
            }
        }
        lastUnavailable = unavailable;
        List<String> kept = p.entrySet().stream().filter(e -> e.getValue() >= threshold)
                .map(Map.Entry::getKey).toList();
        if (kept.size() < minKeep) {
            kept = p.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(minKeep).map(Map.Entry::getKey).toList();
        }
        // Large candidate sets are summarised in the ledger rather than stored item by item.
        Spec spec = new Spec(scope, scopeId, kind, Policy.LAYA, question,
                items.size() <= 50 ? items : null, String.join("\n", items.keySet()), 0);
        String answer = kept.size() <= 20 ? String.join(", ", kept)
                : kept.size() + " of " + items.size() + " kept";
        ledger.record(spec, new LayaVote(answer, 1.0, p), null, answer, "LAYA", ms(t0));
        Map<String, Double> out = new LinkedHashMap<>();
        kept.forEach(k -> out.put(k, p.get(k)));
        return out;
    }

    /** How many items of the most recent subset selection Laya could not judge. */
    public int lastUnavailable() {
        return lastUnavailable;
    }

    /** Laya's vote, or null when Laya is unavailable (the decision then falls to the LLM). */
    record LayaVote(String answer, double p, Map<String, Double> probabilities) {
    }

    private LayaVote layaVote(Spec spec) {
        String state = spec.state().length() <= MAX_LAYA_STATE
                ? spec.state() : spec.state().substring(0, MAX_LAYA_STATE);
        try {
            LayaQuestion q = spec.isYesNo()
                    ? LayaQuestion.yesNo(spec.question())
                    : LayaQuestion.choice(spec.question(), spec.options());
            LayaAnswer a = laya.decide(state, "d", q);
            if (spec.isYesNo()) {
                double pYes = a.yesProbability();
                return pYes >= 0.5
                        ? new LayaVote(YES, pYes, Map.of(YES, pYes, NO, 1 - pYes))
                        : new LayaVote(NO, 1 - pYes, Map.of(YES, pYes, NO, 1 - pYes));
            }
            return new LayaVote(a.choice(), a.answerConfidence(), a.probabilities());
        } catch (Exception ex) {
            log.warn("Laya unavailable for decision {}: {}", spec.kind(), ex.toString());
            return null;
        }
    }

    private LlmVerdict llmVote(Spec spec) {
        Map<String, String> allowed = spec.isYesNo()
                ? Map.of(YES, "yes", NO, "no") : spec.options();
        StringBuilder opts = new StringBuilder();
        allowed.forEach((k, v) -> opts.append("- ").append(k).append(": ").append(v).append('\n'));
        try {
            LlmVerdict v = chat.prompt()
                    .user(u -> u.text("""
                            Decision: {question}

                            Allowed answers (answer with the label only):
                            {options}
                            State:
                            {state}
                            /no_think""")
                            .param("question", spec.question())
                            .param("options", opts.toString())
                            .param("state", spec.state()))
                    .call()
                    .entity(LlmVerdict.class);
            if (v == null || v.answer() == null) {
                return null;
            }
            String norm = v.answer().strip().toLowerCase(Locale.ROOT);
            for (String label : allowed.keySet()) {
                if (label.toLowerCase(Locale.ROOT).equals(norm)) {
                    return new LlmVerdict(label, v.rationale());
                }
            }
            log.warn("LLM answered '{}' outside the allowed labels for {}", v.answer(), spec.kind());
            return null;
        } catch (Exception ex) {
            log.warn("LLM decision failed for {}: {}", spec.kind(), ex.toString());
            return null;
        }
    }

    private static String fallback(Spec spec) {
        // Neither model answered: take the conservative branch.
        return spec.isYesNo() ? NO : List.copyOf(new LinkedHashMap<>(spec.options()).keySet()).getFirst();
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
