package ai.auralis.design;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ai.auralis.decision.DecisionService;
import ai.auralis.decision.DecisionService.Decision;
import ai.auralis.decision.DecisionService.Policy;
import ai.auralis.decision.DecisionService.Spec;
import ai.auralis.design.DesignAnalyst.AlternativeDraft;
import ai.auralis.design.DesignAnalyst.Developed;
import ai.auralis.design.DesignAnalyst.Requirements;
import ai.auralis.design.DesignStore.Status;
import ai.auralis.design.DesignStore.Version;
import ai.auralis.research.ResearchEngine;
import ai.auralis.research.store.ResearchStore;
import ai.auralis.research.store.ResearchStore.DeepMode;

/**
 * The closed design loop, domain-neutral:
 * requirements → alternatives → (Laya) select → develop → validate with the
 * domain adapter's tools → (Laya) verdict → PASS / FIX / RESEARCH / ASK_USER.
 * RESEARCH runs a research session on the failure and feeds the findings back.
 * Every control decision goes through {@link DecisionService}.
 */
@Service
public class DesignEngine {

    private static final Logger log = LoggerFactory.getLogger(DesignEngine.class);
    private static final String SCOPE = "design";
    private static final int ALTERNATIVES = 3;
    private static final int MAX_CONTEXT = 4500;

    private final DesignStore store;
    private final DesignAnalyst analyst;
    private final AdapterRegistry adapters;
    private final DecisionService decisions;
    private final ai.auralis.tools.ToolRegistry tools;
    private final ResearchStore researchStore;
    private final ResearchEngine research;
    private final ai.auralis.graph.GraphStore graph;
    private final ai.auralis.knowledge.retrieval.HybridRetriever retriever;

    public DesignEngine(DesignStore store, DesignAnalyst analyst, AdapterRegistry adapters,
                        DecisionService decisions, ai.auralis.tools.ToolRegistry tools,
                        ResearchStore researchStore, ResearchEngine research,
                        ai.auralis.graph.GraphStore graph,
                        ai.auralis.knowledge.retrieval.HybridRetriever retriever) {
        this.store = store;
        this.analyst = analyst;
        this.adapters = adapters;
        this.decisions = decisions;
        this.tools = tools;
        this.researchStore = researchStore;
        this.research = research;
        this.graph = graph;
        this.retriever = retriever;
    }

    /** A full design run from the objective. */
    public void run(UUID designId) {
        var design = store.design(designId).orElseThrow();
        try {
            String context = researchContext(design.researchSessionId())
                    + notebookContext(designId, design.notebook());
            Requirements req = analyst.requirements(design.objective(), context);
            String reqText = req.render();
            store.event(designId, 0, "REQUIREMENTS", count(req.functional()) + " functional, "
                    + count(req.constraints()) + " constraints, " + count(req.missing())
                    + " missing, " + count(req.assumptions()) + " assumptions");

            Decision complete = decisions.decide(Spec.yesNo(SCOPE, designId, "spec_complete",
                    Policy.LAYA_THEN_LLM,
                    "Is this specification complete enough to start designing?", reqText));
            store.event(designId, 0, "SPEC", complete.yes()
                    ? "specification judged complete (" + complete.decidedBy() + ")"
                    : "specification incomplete (" + complete.decidedBy()
                            + ") — designing under the stated assumptions");

            DomainAdapter adapter = adapters.choose(designId, design.objective() + "\n\n" + reqText);
            store.event(designId, 0, "DOMAIN", "adapter: " + adapter.name());
            String guidance = adapter.guidance();

            List<AlternativeDraft> alts = analyst.alternatives(design.objective(), reqText, context,
                    guidance, ALTERNATIVES);
            if (alts.isEmpty()) {
                throw new IllegalStateException("the analyst proposed no alternatives");
            }
            AlternativeDraft chosen = select(designId, alts, design.objective(), reqText);

            iterate(designId, design.objective(), reqText, context, adapter, guidance, chosen,
                    null, null, design.maxIterations());
        } catch (Exception ex) {
            log.error("design {} failed", designId, ex);
            store.setStatus(designId, Status.FAILED, ex.toString());
            store.event(designId, 0, "ERROR", ex.toString());
        }
    }

    /** A what-if branch: develop from an existing version under changed constraints. */
    public void branch(UUID designId, UUID fromVersionId, String constraintChange, int iterations) {
        var design = store.design(designId).orElseThrow();
        try {
            Version from = store.version(designId, fromVersionId).orElseThrow(
                    () -> new IllegalArgumentException("no version " + fromVersionId));
            String context = researchContext(design.researchSessionId());
            String reqText = analyst.requirements(design.objective(), context).render();
            DomainAdapter adapter = adapters.choose(designId, design.objective());
            AlternativeDraft alt = store.alternatives(designId).stream()
                    .filter(a -> a.name().equals(from.alternative())).findFirst()
                    .map(a -> new AlternativeDraft(a.name(), a.summary(), a.pros(), a.cons(),
                            a.assumptions()))
                    .orElse(new AlternativeDraft(from.alternative(), from.alternative(), "", "", ""));
            store.event(designId, from.iteration(), "BRANCH",
                    "what-if from " + from.label() + ": " + constraintChange);
            iterate(designId, design.objective(), reqText, context, adapter, adapter.guidance(),
                    alt, from, constraintChange, iterations);
        } catch (Exception ex) {
            log.error("design branch {} failed", designId, ex);
            store.setStatus(designId, Status.FAILED, ex.toString());
            store.event(designId, 0, "ERROR", ex.toString());
        }
    }

    private AlternativeDraft select(UUID designId, List<AlternativeDraft> alts, String objective,
                                    String reqText) {
        Map<String, String> options = new LinkedHashMap<>();
        Map<String, UUID> ids = new LinkedHashMap<>();
        for (AlternativeDraft a : alts) {
            options.put(a.name(), a.summary() + " Pros: " + a.pros() + " Cons: " + a.cons());
            ids.put(a.name(), store.addAlternative(designId, a.name(), a.summary(), a.pros(),
                    a.cons(), a.assumptions()));
        }
        Decision d = decisions.decide(Spec.choice(SCOPE, designId, "select_alternative",
                Policy.LAYA_THEN_LLM, "Which alternative best meets the requirements?",
                options, objective + "\n\n" + reqText));
        for (var e : ids.entrySet()) {
            Double p = d.layaProbabilities() == null ? null : d.layaProbabilities().get(e.getKey());
            store.scoreAlternative(e.getValue(), p == null ? 0.0 : p, e.getKey().equals(d.answer()));
        }
        store.event(designId, 0, "SELECT", "alternative '" + d.answer() + "' chosen by "
                + d.decidedBy() + " from " + alts.size());
        return alts.stream().filter(a -> a.name().equals(d.answer())).findFirst()
                .orElse(alts.getFirst());
    }

    private void iterate(UUID designId, String objective, String reqText, String context,
                         DomainAdapter adapter, String guidance, AlternativeDraft alt,
                         Version parent, String constraints, int maxIterations) {
        String feedback = null;
        int start = parent == null ? 1 : parent.iteration() + 1;
        for (int it = start; it < start + maxIterations; it++) {
            var stepTools = tools.selectFor(SCOPE, designId,
                    "develop and check the design: " + alt.name() + " — " + objective);
            store.event(designId, it, "TOOLS", "Laya selected: " + stepTools.stream()
                    .map(t -> t.getToolDefinition().name()).toList());

            Developed dev = analyst.develop(objective, reqText, context, alt, constraints, guidance,
                    parent == null ? null : parent.spec(), feedback, stepTools);
            String label = store.nextLabel(designId, parent);
            UUID versionId = store.addVersion(designId, parent == null ? null : parent.id(), label,
                    it, alt.name(), dev.spec(), dev.artifacts(), null, constraints);
            store.event(designId, it, "DEVELOP", label + " with " + dev.artifacts().size()
                    + " file(s): " + dev.artifacts().keySet());

            String toolReport = adapter.validate(dev.artifacts());
            String review = analyst.review(objective, reqText, dev.spec(), toolReport);
            String verification = "== automated (" + adapter.name() + ") ==\n" + toolReport
                    + "\n\n== review ==\n" + review;

            Map<String, String> verdicts = new LinkedHashMap<>();
            verdicts.put("PASS", "the design meets the requirements and all checks pass");
            verdicts.put("FIX", "there are failures the design itself can fix");
            verdicts.put("RESEARCH", "the failures need knowledge the design lacks");
            verdicts.put("ASK_USER", "requirements are ambiguous or conflicting; the engineer must decide");
            Decision verdict = decisions.decide(Spec.choice(SCOPE, designId, "verification_verdict",
                    Policy.LAYA_THEN_LLM, "What is the verdict on this design version?",
                    verdicts, verification));
            store.verifyVersion(versionId, verification, verdict.answer());
            store.event(designId, it, "VERDICT", label + ": " + verdict.answer() + " (by "
                    + verdict.decidedBy() + ")");

            switch (verdict.answer()) {
                case "PASS" -> {
                    store.setStatus(designId, Status.VERIFIED, null);
                    return;
                }
                case "ASK_USER" -> {
                    store.setStatus(designId, Status.NEEDS_INPUT, null);
                    return;
                }
                case "RESEARCH" -> feedback = review + "\n\nResearch findings:\n"
                        + researchFailure(designId, it, objective, review);
                default -> feedback = review + "\n\nTool output:\n" + toolReport;
            }
            parent = store.version(designId, versionId).orElseThrow();
        }
        store.setStatus(designId, Status.EXHAUSTED, null);
        store.event(designId, start + maxIterations - 1, "STOP", "iteration budget spent");
    }

    /** Closes the loop back into research: one focused round on what the failure needs. */
    private String researchFailure(UUID designId, int it, String objective, String review) {
        String question = analyst.researchQuestion(objective, review);
        UUID session = researchStore.createSession(question, DeepMode.NONE, 1);
        store.event(designId, it, "RESEARCH", "research session " + session + ": " + question);
        research.run(session);
        return researchStore.session(session).map(s -> s.report() == null ? "(no report)" : s.report())
                .orElse("(research failed)");
    }

    /**
     * Evidence from the design's notebook, gathered part by part: for each PART of
     * a problem notebook, its best passages; otherwise the notebook as a whole.
     */
    private String notebookContext(UUID designId, String notebook) {
        if (notebook == null) {
            return "";
        }
        var scope = ai.auralis.knowledge.retrieval.Scope.of(notebook, null);
        List<String> queries = graph.byKind(notebook, "PART").stream().map(n -> n.name()).toList();
        if (queries.isEmpty()) {
            queries = List.of(store.design(designId).map(d -> d.objective()).orElse(""));
        }
        StringBuilder sb = new StringBuilder("\n\nEvidence from the notebook's sources, by part:\n");
        int perPart = Math.max(2, 8 / Math.max(1, queries.size()));
        for (String q : queries) {
            sb.append("\n## ").append(q).append('\n');
            for (var e : retriever.retrieve(q, perPart, false, scope)) {
                String text = e.content().length() > 400 ? e.content().substring(0, 400) + " …" : e.content();
                sb.append("- (").append(e.documentTitle()).append(") ").append(text.replace('\n', ' ')).append('\n');
            }
            if (sb.length() > MAX_CONTEXT) {
                break;
            }
        }
        store.event(designId, 0, "EVIDENCE", "grounded in notebook " + notebook + " across "
                + queries.size() + " part(s)");
        return sb.length() <= MAX_CONTEXT ? sb.toString() : sb.substring(0, MAX_CONTEXT);
    }

    private String researchContext(UUID sessionId) {
        if (sessionId == null) {
            return "";
        }
        String report = researchStore.session(sessionId).map(s -> s.report()).orElse(null);
        if (report == null) {
            return "";
        }
        return report.length() <= MAX_CONTEXT ? report : report.substring(0, MAX_CONTEXT);
    }

    private static int count(List<String> l) {
        return l == null ? 0 : l.size();
    }
}
