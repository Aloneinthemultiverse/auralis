package ai.auralis.research;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import ai.auralis.config.AuralisProperties;
import ai.auralis.knowledge.ingest.IngestionService;
import ai.auralis.knowledge.retrieval.Evidence;
import ai.auralis.knowledge.retrieval.HybridRetriever;
import ai.auralis.research.ResearchAnalyst.Answer;
import ai.auralis.research.ResearchAnalyst.Finding;
import ai.auralis.research.ResearchAnalyst.Source;
import ai.auralis.research.ResearchGate.Sufficiency;
import ai.auralis.research.deep.DeepResearch;
import ai.auralis.research.deep.KaggleDeepResearch;
import ai.auralis.research.deep.LocalDeepResearch;
import ai.auralis.research.store.ResearchStore;
import ai.auralis.research.store.ResearchStore.DeepMode;
import ai.auralis.research.store.ResearchStore.EvidenceRow;
import ai.auralis.research.store.ResearchStore.Question;
import ai.auralis.research.store.ResearchStore.QuestionStatus;
import ai.auralis.research.store.ResearchStore.Status;

/**
 * The dynamic research loop. Each round: retrieve evidence for every open
 * question, synthesize a cited answer while the analyst judges whether the
 * evidence really answers it, escalate the rest to deep web research, then let
 * Laya judge whether the whole state is sufficient. If not, the analyst proposes gap questions for
 * the next round. Research ends on sufficiency, when no gaps remain, or when
 * the round budget is spent — "research until there is enough evidence to
 * decide", not "retrieve as much as possible".
 */
@Service
public class ResearchEngine {

    private static final Logger log = LoggerFactory.getLogger(ResearchEngine.class);

    private final ResearchStore store;
    private final HybridRetriever retriever;
    private final ResearchGate gate;
    private final ResearchAnalyst analyst;
    private final IngestionService ingestion;
    private final Map<DeepMode, DeepResearch> deep;
    private final AuralisProperties.Research cfg;
    private final JsonMapper json;

    public ResearchEngine(ResearchStore store, HybridRetriever retriever, ResearchGate gate,
                          ResearchAnalyst analyst, IngestionService ingestion,
                          LocalDeepResearch local, KaggleDeepResearch kaggle,
                          AuralisProperties props, JsonMapper json) {
        this.store = store;
        this.retriever = retriever;
        this.gate = gate;
        this.analyst = analyst;
        this.ingestion = ingestion;
        this.deep = Map.of(DeepMode.LOCAL, local, DeepMode.KAGGLE, kaggle);
        this.cfg = props.research();
        this.json = json;
    }

    public void run(UUID sessionId) {
        var session = store.session(sessionId).orElseThrow();
        String objective = session.objective();
        try {
            List<String> planned = analyst.plan(objective, cfg.maxQuestionsPerRound());
            planned.forEach(q -> store.addQuestion(sessionId, null, 1, "PLAN", q));
            store.event(sessionId, 1, "PLAN", planned.size() + " research questions planned");

            Status outcome = Status.EXHAUSTED;
            for (int round = 1; round <= session.maxRounds(); round++) {
                List<Question> open = store.openQuestions(sessionId);
                if (open.isEmpty()) {
                    store.event(sessionId, round, "STOP", "no open questions");
                    break;
                }
                researchRound(sessionId, round, open, session.deepMode());

                List<Finding> findings = findings(sessionId);
                Sufficiency s = gate.sufficiency(objective, ResearchAnalyst.render(findings));
                store.roundCompleted(sessionId, round, json.writeValueAsString(s));
                store.event(sessionId, round, "SUFFICIENCY", String.format(Locale.ROOT,
                        "sufficient=%.2f contradiction=%.2f next=%s",
                        s.sufficient(), s.contradiction(), s.next()));

                long unresolved = findings.stream().filter(f -> !f.resolved()).count();
                if (unresolved == 0 && s.sufficient() >= cfg.sufficientThreshold()
                        && s.contradiction() < 0.5) {
                    outcome = Status.SUFFICIENT;
                    store.event(sessionId, round, "STOP", "evidence judged sufficient");
                    break;
                }
                if (round == session.maxRounds()) {
                    store.event(sessionId, round, "STOP", "round budget spent");
                    break;
                }
                int added = addGapQuestions(sessionId, round + 1, objective, findings);
                if (added == 0) {
                    store.event(sessionId, round, "STOP", "no further gaps identified");
                    break;
                }
            }
            String report = analyst.report(objective, findings(sessionId));
            store.finish(sessionId, outcome, report, null);
            store.event(sessionId, session.maxRounds(), "DONE", "status " + outcome);
        } catch (Exception ex) {
            log.error("research session {} failed", sessionId, ex);
            store.finish(sessionId, Status.FAILED, null, ex.toString());
            store.event(sessionId, 0, "ERROR", ex.toString());
        }
    }

    private void researchRound(UUID sessionId, int round, List<Question> open, DeepMode mode) {
        // 1. Local knowledge first. Laya's topical relevance is kept as a signal;
        //    whether the evidence actually answers the question is the analyst's call.
        Map<UUID, Double> topical = new HashMap<>();
        for (Question q : open) {
            List<Evidence> hits = retriever.retrieve(q.text(), cfg.evidencePerQuestion(), true);
            for (Evidence e : hits) {
                store.addEvidence(q.id(), "CHUNK", e.chunkId(), e.documentTitle(), null,
                        e.content(), e.relevance(), round);
            }
            double p = hits.stream().map(Evidence::relevance).filter(r -> r != null)
                    .mapToDouble(Double::doubleValue).max().orElse(0.0);
            topical.put(q.id(), p);
            store.event(sessionId, round, "RETRIEVE", String.format(Locale.ROOT,
                    "%d passages, top relevance %.2f — %s", hits.size(), p, q.text()));
        }

        // 2. Cited synthesis, with the analyst judging answerability.
        Map<UUID, Answer> answers = new HashMap<>();
        List<Question> escalate = new ArrayList<>();
        for (Question q : open) {
            Answer a = synthesize(q);
            answers.put(q.id(), a);
            if (!a.fullyAnswered()) {
                escalate.add(q);
                store.event(sessionId, round, "GAP_IN_EVIDENCE",
                        q.text() + " — missing: " + a.missing());
            }
        }

        // 3. Deep web research for what the knowledge base could not answer,
        //    then re-synthesize those questions with the new evidence.
        if (!escalate.isEmpty() && mode != DeepMode.NONE) {
            store.event(sessionId, round, "ESCALATE",
                    escalate.size() + " question(s) to " + mode + " deep research");
            var queries = escalate.stream()
                    .map(q -> new DeepResearch.Query(q.id().toString(), q.text())).toList();
            Map<UUID, Question> byId = escalate.stream()
                    .collect(Collectors.toMap(Question::id, q -> q));
            for (DeepResearch.Finding f : deep.get(mode).research(queries)) {
                UUID qid = UUID.fromString(f.id());
                if (!f.ok()) {
                    store.event(sessionId, round, "DEEP_FAIL", f.error());
                    continue;
                }
                String uri = f.sources().isEmpty() ? null : f.sources().getFirst();
                String content = f.answer() + sourcesBlock(f.sources());
                store.addEvidence(qid, "WEB", null, "Deep research", uri, content, null, round);
                // Engineering memory: what was learned on the web becomes local knowledge.
                var ingested = ingestion.ingestText(content, "Web research: " + f.question(),
                        "web-research", uri);
                store.event(sessionId, round, "DEEP", String.format(Locale.ROOT,
                        "%d sources in %.0fs, stored as document %s",
                        f.sources().size(), f.durationSeconds(), ingested.documentId()));
                answers.put(qid, synthesize(byId.get(qid)));
            }
        } else if (!escalate.isEmpty()) {
            store.event(sessionId, round, "ESCALATE",
                    escalate.size() + " question(s) under-evidenced; deep research disabled");
        }

        for (Question q : open) {
            Answer a = answers.get(q.id());
            store.resolveQuestion(q.id(),
                    a.fullyAnswered() ? QuestionStatus.ANSWERED : QuestionStatus.UNRESOLVED,
                    a.fullyAnswered() ? a.answer()
                            : a.answer() + "\n\nStill missing: " + a.missing(),
                    topical.get(q.id()));
        }
    }

    private Answer synthesize(Question q) {
        List<Source> sources = store.evidence(q.id()).stream()
                .map(e -> new Source(sourceTitle(e), e.content())).toList();
        return analyst.answer(q.text(), sources);
    }

    private int addGapQuestions(UUID sessionId, int nextRound, String objective,
                                List<Finding> findings) {
        Set<String> asked = store.questions(sessionId).stream()
                .map(q -> normalize(q.text())).collect(Collectors.toSet());
        int added = 0;
        for (String g : analyst.gaps(objective, findings, cfg.maxQuestionsPerRound())) {
            if (asked.add(normalize(g))) {
                store.addQuestion(sessionId, null, nextRound, "GAP", g);
                added++;
            }
        }
        store.event(sessionId, nextRound - 1, "GAPS", added + " follow-up question(s)");
        return added;
    }

    private List<Finding> findings(UUID sessionId) {
        return store.questions(sessionId).stream()
                .filter(q -> q.status() != QuestionStatus.OPEN)
                .map(q -> new Finding(q.text(), q.answer(), q.status() == QuestionStatus.ANSWERED))
                .toList();
    }

    private static String sourceTitle(EvidenceRow e) {
        return e.sourceUri() != null ? e.sourceTitle() + " (" + e.sourceUri() + ")" : e.sourceTitle();
    }

    private static String sourcesBlock(List<String> sources) {
        if (sources.isEmpty()) {
            return "";
        }
        return "\n\nSources:\n" + sources.stream().limit(10)
                .map(s -> "- " + s).collect(Collectors.joining("\n"));
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").strip();
    }
}
