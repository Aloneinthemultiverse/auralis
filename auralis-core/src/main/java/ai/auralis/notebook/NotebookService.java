package ai.auralis.notebook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import ai.auralis.decision.DecisionService;
import ai.auralis.decision.DecisionService.Policy;
import ai.auralis.decision.DecisionService.Spec;
import ai.auralis.knowledge.retrieval.Evidence;
import ai.auralis.knowledge.retrieval.HybridRetriever;
import ai.auralis.knowledge.retrieval.Scope;

/**
 * NotebookLM-style use of the knowledge base: notebooks are source collections
 * (graph namespaces), chat answers only from the selected sources with
 * citations, and the studio writes briefings, FAQs and study guides. Laya routes
 * each message and votes on whether the evidence suffices.
 */
@Service
public class NotebookService {

    private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>");
    private static final int MAX_PASSAGE = 1500;

    public record Notebook(String id, String title, String kind, long sources, long chunks) {
    }

    public record Source(UUID documentId, String title, String sourceType, String uri, String kind,
                         long chunks) {
    }

    public record Citation(int n, UUID documentId, String title, String uri, String excerpt,
                           Double relevance) {
    }

    /** route: ask | research | design — Laya's reading of what the user wants. */
    public record Answer(String answer, boolean grounded, String missing, String route,
                         List<Citation> citations, UUID decisionId) {
    }

    public record StudioDoc(String type, String title, String markdown, List<Citation> citations) {
    }

    record LlmAnswer(String answer, boolean grounded, String missing) {
    }

    private final JdbcClient jdbc;
    private final HybridRetriever retriever;
    private final DecisionService decisions;
    private final ChatClient chat;

    public NotebookService(JdbcClient jdbc, HybridRetriever retriever, DecisionService decisions,
                           ChatClient.Builder chat) {
        this.jdbc = jdbc;
        this.retriever = retriever;
        this.decisions = decisions;
        this.chat = chat.defaultSystem("""
                You are Auralis, an engineering research assistant grounded in the user's
                sources. You answer only from the numbered sources provided, cite every claim
                as [n], keep exact values and units, and say plainly when the sources do not
                cover something. Never invent sources, numbers or citations.""").build();
    }

    // ── notebooks and sources ───────────────────────────────────────

    public List<Notebook> notebooks() {
        List<Notebook> out = new ArrayList<>(jdbc.sql("""
                SELECT r.namespace, r.name, r.kind,
                       (SELECT count(DISTINCT n.document_id) FROM kg_node n
                         WHERE n.namespace = r.namespace AND n.document_id IS NOT NULL),
                       (SELECT count(*) FROM chunk c WHERE c.document_id IN
                         (SELECT document_id FROM kg_node n WHERE n.namespace = r.namespace
                            AND n.document_id IS NOT NULL))
                FROM kg_node r
                WHERE r.kind IN ('NOTEBOOK', 'LIBRARY', 'CORPUS', 'CATALOG')
                ORDER BY (r.kind = 'NOTEBOOK') DESC, 4 DESC""")
                .query((rs, i) -> new Notebook(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5)))
                .list());
        out.addFirst(new Notebook("all", "Everything", "ALL",
                jdbc.sql("SELECT count(*) FROM source_document").query(Long.class).single(),
                jdbc.sql("SELECT count(*) FROM chunk").query(Long.class).single()));
        return out;
    }

    public List<Source> sources(String notebook, String q, int limit, int offset) {
        String like = q == null || q.isBlank() ? null : "%" + q.strip() + "%";
        if (notebook == null || notebook.equals("all")) {
            return jdbc.sql("""
                    SELECT d.id, d.title, d.source_type, d.uri, d.source_type,
                           (SELECT count(*) FROM chunk c WHERE c.document_id = d.id)
                    FROM source_document d
                    WHERE (?::text IS NULL OR d.title ILIKE ?)
                    ORDER BY d.created_at DESC LIMIT ? OFFSET ?""")
                    .params(like, like, limit, offset)
                    .query((rs, i) -> source(rs)).list();
        }
        return jdbc.sql("""
                SELECT DISTINCT ON (d.id) d.id, d.title, d.source_type, d.uri, n.kind,
                       (SELECT count(*) FROM chunk c WHERE c.document_id = d.id)
                FROM kg_node n JOIN source_document d ON d.id = n.document_id
                WHERE n.namespace = ? AND (?::text IS NULL OR d.title ILIKE ?)
                ORDER BY d.id LIMIT ? OFFSET ?""")
                .params(notebook, like, like, limit, offset)
                .query((rs, i) -> source(rs)).list();
    }

    private static Source source(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Source(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getLong(6));
    }

    // ── grounded chat ───────────────────────────────────────────────

    public Answer ask(String notebook, String question, List<UUID> documentIds) {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("ask", "a question to answer from the existing sources");
        routes.put("research", "asks to investigate a topic beyond the sources or run deep research");
        routes.put("design", "asks to design, generate or verify hardware, RTL or a system");
        var route = decisions.decide(Spec.choice("chat", null, "chat_route", Policy.LAYA,
                "What does this message ask for?", routes, question));

        Scope scope = Scope.of(notebook, documentIds);
        List<Evidence> hits = retriever.retrieve(question, 8, true, scope);
        List<Citation> cites = citations(hits);
        if (hits.isEmpty()) {
            return new Answer("None of the selected sources cover this. Add sources, widen the "
                    + "selection, or start a research run.", false, "any relevant source",
                    route.answer(), List.of(), route.id());
        }
        LlmAnswer a = chat.prompt()
                .user(u -> u.text("""
                        Question: {question}

                        Sources:
                        {sources}
                        Answer using only these sources, citing each claim as [n]. Use short
                        paragraphs or bullets; keep exact values and units. Then set grounded
                        to true only if the sources actually answer the question, and put in
                        missing what the sources lack (empty if nothing).
                        /no_think""")
                        .param("question", question)
                        .param("sources", render(cites)))
                .call()
                .entity(LlmAnswer.class);
        String text = a == null || a.answer() == null ? "No answer was produced." : strip(a.answer());
        boolean grounded = a != null && a.grounded();
        var vote = decisions.recordWithLlmVerdict(Spec.yesNo("chat", null, "chat_grounded",
                Policy.LAYA_ADVISORY, "Do these sources answer the question: " + question,
                render(cites)), grounded, a == null ? "" : a.missing());
        return new Answer(text, grounded, a == null ? null : a.missing(), route.answer(), cites, vote.id());
    }

    // ── studio ──────────────────────────────────────────────────────

    public StudioDoc studio(String notebook, String type, String focus, List<UUID> documentIds) {
        String topic = focus == null || focus.isBlank() ? notebookTitle(notebook) : focus;
        Scope scope = Scope.of(notebook, documentIds);
        Map<UUID, Evidence> pool = new LinkedHashMap<>();
        for (String q : studioQueries(type, topic)) {
            for (Evidence e : retriever.retrieve(q, 5, false, scope)) {
                pool.putIfAbsent(e.chunkId(), e);
            }
        }
        List<Citation> cites = citations(List.copyOf(pool.values()).subList(0, Math.min(14, pool.size())));
        String instructions = switch (type) {
            case "faq" -> "Write an FAQ: 8-12 questions an engineer would ask about the topic, each with a concise cited answer.";
            case "study_guide" -> "Write a study guide: key concepts with definitions, how they relate, worked examples or numbers from the sources, and 6 self-test questions with answers.";
            case "design_brief" -> "Write a design brief: requirements and constraints implied by the sources, candidate architectures with trade-offs, key parameters with values, risks and open questions.";
            default -> "Write a briefing document: executive summary, key findings with numbers, important trade-offs, and open questions.";
        };
        String md = strip(chat.prompt()
                .user(u -> u.text("""
                        Topic: {topic}

                        Sources:
                        {sources}
                        {instructions}
                        Use Markdown headings. Cite every claim as [n] from the sources above
                        only; if the sources are thin on a point, say so.
                        /no_think""")
                        .param("topic", topic)
                        .param("sources", render(cites))
                        .param("instructions", instructions))
                .call()
                .content());
        String title = switch (type) {
            case "faq" -> "FAQ";
            case "study_guide" -> "Study guide";
            case "design_brief" -> "Design brief";
            default -> "Briefing";
        } + ": " + topic;
        return new StudioDoc(type, title, md, cites);
    }

    private static List<String> studioQueries(String type, String topic) {
        return switch (type) {
            case "faq" -> List.of(topic, topic + " how does it work", topic + " trade-offs limitations");
            case "study_guide" -> List.of(topic + " fundamentals definitions", topic + " examples", topic);
            case "design_brief" -> List.of(topic + " requirements constraints", topic + " architecture alternatives",
                    topic + " area power performance");
            default -> List.of(topic, topic + " key results", topic + " challenges open problems");
        };
    }

    private String notebookTitle(String notebook) {
        if (notebook == null || notebook.equals("all")) {
            return "semiconductor and digital hardware design";
        }
        return jdbc.sql("SELECT name FROM kg_node WHERE namespace = ? AND kind IN ('NOTEBOOK','LIBRARY','CORPUS','CATALOG') LIMIT 1")
                .param(notebook).query(String.class).optional().orElse(notebook);
    }

    // ── helpers ─────────────────────────────────────────────────────

    private List<Citation> citations(List<Evidence> hits) {
        List<Citation> out = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            Evidence e = hits.get(i);
            String uri = jdbc.sql("SELECT uri FROM source_document WHERE id = ?").param(e.documentId())
                    .query(String.class).optional().orElse(null);
            String ex = e.content().length() <= MAX_PASSAGE ? e.content() : e.content().substring(0, MAX_PASSAGE) + " …";
            out.add(new Citation(i + 1, e.documentId(), e.documentTitle(), uri, ex, e.relevance()));
        }
        return out;
    }

    private static String render(List<Citation> cites) {
        StringBuilder sb = new StringBuilder();
        cites.forEach(c -> sb.append('[').append(c.n()).append("] ").append(c.title()).append('\n')
                .append(c.excerpt()).append("\n\n"));
        return sb.toString();
    }

    private static String strip(String s) {
        return s == null ? "" : THINK.matcher(s).replaceAll("").strip();
    }
}
