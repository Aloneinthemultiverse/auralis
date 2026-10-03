package ai.auralis.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import ai.auralis.knowledge.ingest.IngestionService;
import ai.auralis.knowledge.ingest.IngestionService.IngestResult;
import ai.auralis.knowledge.retrieval.Evidence;
import ai.auralis.knowledge.retrieval.HybridRetriever;
import ai.auralis.knowledge.retrieval.RatedRetriever;
import ai.auralis.knowledge.retrieval.Scope;
import ai.auralis.research.ResearchService;
import ai.auralis.research.ResearchService.QuestionView;
import ai.auralis.research.ResearchService.SessionView;
import ai.auralis.research.store.ResearchStore.DeepMode;
import ai.auralis.research.store.ResearchStore.Event;

/**
 * Auralis exposed over MCP, so Claude (or any MCP client) can use the
 * knowledge base and the research loop as tools. Results are plain text
 * written for a model to read, with source titles kept for citation.
 */
@Component
public class AuralisMcpTools {

    private static final int MAX_PASSAGE_CHARS = 500;   // token budget: the best-matching window of each passage

    private final HybridRetriever retriever;
    private final RatedRetriever rated;
    private final IngestionService ingestion;
    private final ResearchService research;

    public AuralisMcpTools(HybridRetriever retriever, RatedRetriever rated, IngestionService ingestion,
                           ResearchService research) {
        this.retriever = retriever;
        this.rated = rated;
        this.ingestion = ingestion;
        this.research = research;
    }

    @McpTool(name = "knowledge_search", description = """
            Search the Auralis engineering knowledge base (ingested papers, datasheets,
            standards, manuals, past research) with hybrid vector + keyword retrieval and
            relevance reranking. Laya rates whether the evidence answers the query; on REFINE
            the query is rewritten and retrieval repeats (up to `rounds`, default 3), merging
            the best passages. Returns numbered passages with source titles and the rating trail.""")
    public String knowledgeSearch(
            @McpToolParam(description = "What to look for; a question or exact terms") String query,
            @McpToolParam(description = "Number of passages, 1-20 (default 4)", required = false)
            Integer topK,
            @McpToolParam(description = "Max Laya-rated retrieval rounds, 1-5 (default 1 = single pass, Laya still rates it; more rounds cost tokens)",
                    required = false) Integer rounds) {
        int k = topK == null ? 4 : Math.max(1, Math.min(20, topK));
        int r = rounds == null ? 1 : Math.max(1, Math.min(5, rounds));
        RatedRetriever.Result res = rated.retrieve(query, k, r, Scope.ALL);
        List<Evidence> hits = res.evidence();
        if (hits.isEmpty()) {
            return "No passages found. The knowledge base may not cover this yet; "
                    + "consider knowledge_add_file / knowledge_add_text or research_start.";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            Evidence e = hits.get(i);
            sb.append('[').append(i + 1).append("] ").append(e.documentTitle())
                    .append(" (chunk ").append(e.ordinal())
                    .append(e.relevance() != null
                            ? String.format(Locale.ROOT, ", relevance %.2f", e.relevance()) : "")
                    .append(")\n").append(clip(e.content(), query)).append("\n\n");
        }
        sb.append("Laya rating trail: ");
        for (RatedRetriever.Round rd : res.rounds()) {
            sb.append("round ").append(rd.n()).append(" [").append(rd.query()).append("] -> ").append(rd.verdict())
                    .append(rd.layaP() != null ? String.format(Locale.ROOT, " (%.2f)", rd.layaP()) : "")
                    .append(String.format(Locale.ROOT, ", term coverage %.0f%%", 100 * rd.coverage())).append("; ");
        }
        sb.append(res.sufficient() ? "evidence rated SUFFICIENT." : "round cap reached; best evidence returned.");
        return sb.toString().strip();
    }

    @McpTool(name = "knowledge_add_text", description = """
            Add a piece of engineering text (notes, an excerpt, test results, a design
            decision) to the Auralis knowledge base so it can be retrieved later.""")
    public String knowledgeAddText(
            @McpToolParam(description = "Short title naming the source") String title,
            @McpToolParam(description = "The full text to store") String text,
            @McpToolParam(description = "Kind of source, e.g. paper, datasheet, standard, notes",
                    required = false) String sourceType,
            @McpToolParam(description = "Where it came from (URL or path)", required = false)
            String uri) {
        return describe(ingestion.ingestText(text, title,
                sourceType == null ? "notes" : sourceType, uri), title);
    }

    @McpTool(name = "knowledge_add_file", description = """
            Ingest a local document (PDF, DOCX, HTML, Markdown, text) into the Auralis
            knowledge base. The path must be readable by the Auralis server process.""")
    public String knowledgeAddFile(
            @McpToolParam(description = "Absolute path of the file") String path,
            @McpToolParam(description = "Kind of source, e.g. paper, datasheet, standard",
                    required = false) String sourceType) {
        Path p = Path.of(path);
        if (!Files.isRegularFile(p)) {
            return "Not a readable file: " + path;
        }
        String title = p.getFileName().toString();
        return describe(ingestion.ingest(new FileSystemResource(p), title,
                sourceType == null ? "document" : sourceType, p.toUri().toString()), title);
    }

    @McpTool(name = "research_start", description = """
            Start an Auralis research session on an engineering objective. Auralis plans
            research questions, retrieves evidence, uses the Laya decision model to judge
            whether evidence answers each question, optionally escalates gaps to deep web
            research, and iterates until the evidence is sufficient. Runs in the background
            (minutes); poll research_status with the returned id.""")
    public String researchStart(
            @McpToolParam(description = "The engineering objective or problem statement")
            String objective,
            @McpToolParam(description = "Maximum research rounds, 1-10 (default 3)", required = false)
            Integer maxRounds,
            @McpToolParam(description = """
                    Deep web research for questions the knowledge base cannot answer:
                    NONE (default, knowledge base only), LOCAL (this machine), or
                    KAGGLE (free Kaggle GPU, slower to start, stronger model)""", required = false)
            String deepResearch) {
        DeepMode mode;
        try {
            mode = deepResearch == null ? DeepMode.NONE
                    : DeepMode.valueOf(deepResearch.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return "deepResearch must be NONE, LOCAL or KAGGLE";
        }
        int rounds = maxRounds == null ? 3 : Math.max(1, Math.min(10, maxRounds));
        UUID id = research.start(objective, rounds, mode);
        return "Research session started.\nid: " + id + "\ndeep research: " + mode
                + "\nmax rounds: " + rounds
                + "\nCall research_status with this id to follow progress and read the report.";
    }

    @McpTool(name = "research_status", description = """
            Get the state of an Auralis research session: status, the decision trace,
            each research question with its evidence-based answer, and the final cited
            report once finished.""")
    public String researchStatus(
            @McpToolParam(description = "Session id returned by research_start") String sessionId,
            @McpToolParam(description = "Include every evidence passage (default false)",
                    required = false) Boolean includeEvidence) {
        UUID id;
        try {
            id = UUID.fromString(sessionId.strip());
        } catch (IllegalArgumentException ex) {
            return "Not a session id: " + sessionId;
        }
        return research.view(id)
                .map(v -> render(v, Boolean.TRUE.equals(includeEvidence)))
                .orElse("No research session " + sessionId);
    }

    private static String render(SessionView v, boolean withEvidence) {
        var s = v.session();
        StringBuilder sb = new StringBuilder()
                .append("Objective: ").append(s.objective()).append('\n')
                .append("Status: ").append(s.status())
                .append(" (round ").append(s.roundsRun()).append('/').append(s.maxRounds())
                .append(", deep research ").append(s.deepMode()).append(")\n");
        if (s.error() != null) {
            sb.append("Error: ").append(s.error()).append('\n');
        }
        sb.append("\nTrace:\n");
        for (Event e : v.trace()) {
            sb.append("  ").append(e.at()).append(" r").append(e.round()).append(' ')
                    .append(e.type()).append(": ").append(e.message()).append('\n');
        }
        sb.append("\nQuestions:\n");
        for (QuestionView q : v.questions()) {
            var qq = q.question();
            sb.append("- [").append(qq.status()).append(", round ").append(qq.round())
                    .append(", ").append(qq.origin()).append("] ").append(qq.text()).append('\n');
            if (qq.answer() != null) {
                sb.append("  Answer: ").append(qq.answer().replace("\n", "\n  ")).append('\n');
            }
            if (withEvidence) {
                for (var e : q.evidence()) {
                    sb.append("    · ").append(e.kind()).append(' ').append(e.sourceTitle())
                            .append(": ").append(clip(e.content()).replace("\n", " ")).append('\n');
                }
            }
        }
        if (s.report() != null) {
            sb.append("\nReport:\n").append(s.report()).append('\n');
        }
        return sb.toString();
    }

    private static String describe(IngestResult r, String title) {
        return r.alreadyPresent()
                ? "Already in the knowledge base: '" + title + "' (document " + r.documentId() + ")"
                : "Added '" + title + "' as " + r.chunks() + " chunk(s), document " + r.documentId();
    }

    private static String clip(String s) {
        return s.length() <= MAX_PASSAGE_CHARS ? s : s.substring(0, MAX_PASSAGE_CHARS) + " …";
    }

    /** The MAX_PASSAGE_CHARS window of the passage containing the most query terms (saves tokens). */
    static String clip(String s, String query) {
        String t = s.replaceAll("\s+", " ").strip();
        if (t.length() <= MAX_PASSAGE_CHARS) {
            return t;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        List<String> terms = java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^a-z0-9/.-]+"))
                .filter(w -> w.length() >= 3).toList();
        int best = 0, bestHits = -1;
        for (int start = 0; start + MAX_PASSAGE_CHARS <= t.length() + 100; start += 100) {
            int end = Math.min(t.length(), start + MAX_PASSAGE_CHARS);
            String win = lower.substring(Math.min(start, end), end);
            int hits = 0;
            for (String w : terms) {
                if (win.contains(w)) {
                    hits++;
                }
            }
            if (hits > bestHits) {
                bestHits = hits;
                best = Math.min(start, Math.max(0, t.length() - MAX_PASSAGE_CHARS));
            }
        }
        return (best > 0 ? "… " : "") + t.substring(best, Math.min(t.length(), best + MAX_PASSAGE_CHARS))
                + (best + MAX_PASSAGE_CHARS < t.length() ? " …" : "");
    }
}
