package ai.auralis.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import ai.auralis.notebook.DiscoveryService;
import ai.auralis.notebook.DiscoveryService.Found;
import ai.auralis.notebook.NotebookService;

/** NotebookLM-style notebooks over MCP: discover sources, import them, ask grounded questions, generate docs. */
@Component
public class NotebookMcpTools {

    private final NotebookService notebooks;
    private final DiscoveryService discovery;

    public NotebookMcpTools(NotebookService notebooks, DiscoveryService discovery) {
        this.notebooks = notebooks;
        this.discovery = discovery;
    }

    @McpTool(name = "notebook_list", description = "List notebooks (source collections) with their source and passage counts.")
    public String list() {
        StringBuilder sb = new StringBuilder();
        notebooks.notebooks().forEach(n -> sb.append("- ").append(n.id()).append(" — ").append(n.title())
                .append(" [").append(n.kind()).append("] ").append(n.sources()).append(" sources, ")
                .append(n.chunks()).append(" passages\n"));
        return sb.toString();
    }

    @McpTool(name = "notebook_create", description = "Create an empty notebook for a topic; returns its id.")
    public String create(@McpToolParam(description = "Notebook title / topic") String title) {
        return "Notebook created: " + discovery.createNotebook(title);
    }

    @McpTool(name = "notebook_discover", description = """
            NotebookLM-style source discovery for a notebook: searches arXiv, OpenAlex, the web
            (SearXNG) and GitHub open-source designs; Laya scores every source's relevance.
            mode: fast (about a minute), deep (adds a local deep-research report), deep-kaggle.
            Returns a job id; read results with notebook_discover_results.""")
    public String discover(@McpToolParam(description = "Notebook id") String notebook,
                           @McpToolParam(description = "What to research") String query,
                           @McpToolParam(description = "fast | deep | deep-kaggle (default fast)", required = false)
                           String mode) {
        return "Discovery started. job: " + discovery.discover(notebook, query, mode).id
                + "\nCall notebook_discover_results with this job id in about a minute.";
    }

    @McpTool(name = "notebook_discover_results", description = """
            Results of a discovery job: numbered sources ranked by Laya relevance, with kind,
            origin, year and summary, plus the deep-research report if any. Import chosen ones
            with notebook_import (by their numbers).""")
    public String results(@McpToolParam(description = "Discovery job id") String jobId,
                          @McpToolParam(description = "How many to show (default 25)", required = false) Integer limit) {
        return discovery.discoverJob(UUID.fromString(jobId.strip())).map(j -> {
            StringBuilder sb = new StringBuilder("Status: ").append(j.status).append(" — ").append(j.phase).append('\n');
            if (j.report != null) {
                sb.append("\nDeep research report:\n").append(j.report).append("\n");
            }
            List<Found> s = j.sources;
            int n = Math.min(s.size(), limit == null ? 25 : Math.max(1, limit));
            for (int i = 0; i < n; i++) {
                Found f = s.get(i);
                sb.append(String.format(Locale.ROOT, "\n%d. [%s, %s%s] %s%s\n   %s\n   %s\n", i + 1, f.kind(), f.origin(),
                        f.year() == null ? "" : ", " + f.year(), f.title(),
                        f.relevance() == null ? "" : String.format(Locale.ROOT, "  (Laya %.0f%%)", f.relevance() * 100),
                        f.url(), f.summary() == null ? "" : f.summary().length() > 220 ? f.summary().substring(0, 220) + "…" : f.summary()));
            }
            if (s.size() > n) {
                sb.append("\n… ").append(s.size() - n).append(" more");
            }
            return sb.toString();
        }).orElse("No discovery job " + jobId);
    }

    @McpTool(name = "notebook_import", description = """
            Import sources from a discovery job into its notebook (download, ingest, link). Pass the
            result numbers (e.g. "1,2,5-9") or "relevant" for every source Laya scored at least 50%.""")
    public String importSources(@McpToolParam(description = "Discovery job id") String jobId,
                                @McpToolParam(description = "Numbers like 1,2,5-9, or 'relevant'") String which) {
        var job = discovery.discoverJob(UUID.fromString(jobId.strip())).orElse(null);
        if (job == null) {
            return "No discovery job " + jobId;
        }
        List<Found> all = job.sources;
        List<Found> chosen = new ArrayList<>();
        if ("relevant".equalsIgnoreCase(which.strip())) {
            all.stream().filter(f -> f.relevance() != null && f.relevance() >= 0.5).forEach(chosen::add);
        } else {
            for (String part : which.split(",")) {
                String p = part.strip();
                if (p.isEmpty()) {
                    continue;
                }
                int a = Integer.parseInt(p.contains("-") ? p.substring(0, p.indexOf('-')) : p);
                int b = p.contains("-") ? Integer.parseInt(p.substring(p.indexOf('-') + 1)) : a;
                for (int i = a; i <= b && i <= all.size(); i++) {
                    chosen.add(all.get(i - 1));
                }
            }
        }
        var imp = discovery.importSources(job.notebook, chosen);
        return "Importing " + chosen.size() + " sources into " + job.notebook + ". import job: " + imp.id
                + "\nThey become searchable as each finishes (check notebook_list counts).";
    }

    @McpTool(name = "notebook_ask", description = """
            Ask a question answered only from a notebook's sources, with [n] citations and the cited
            passages. Says whether the sources actually answer it (grounded) and what is missing;
            Laya routes the question (ask / research / design).""")
    public String ask(@McpToolParam(description = "Notebook id ('all' for everything)") String notebook,
                      @McpToolParam(description = "The question") String question) {
        var a = notebooks.ask(notebook, question, List.of());
        StringBuilder sb = new StringBuilder(a.answer()).append("\n\n")
                .append(a.grounded() ? "Grounded in the sources." : "Sources only partly cover this. Missing: " + a.missing())
                .append("\nLaya route: ").append(a.route()).append("\n\nSources:\n");
        a.citations().forEach(c -> sb.append('[').append(c.n()).append("] ").append(c.title())
                .append(c.uri() == null ? "" : " — " + c.uri()).append('\n'));
        return sb.toString();
    }

    @McpTool(name = "notebook_studio", description = """
            Generate a cited document from a notebook: briefing, faq, study_guide or design_brief,
            optionally focused on a sub-topic.""")
    public String studio(@McpToolParam(description = "Notebook id") String notebook,
                         @McpToolParam(description = "briefing | faq | study_guide | design_brief") String type,
                         @McpToolParam(description = "Focus sub-topic", required = false) String focus) {
        var d = notebooks.studio(notebook, type, focus, List.of());
        StringBuilder sb = new StringBuilder("# ").append(d.title()).append("\n\n").append(d.markdown()).append("\n\nSources:\n");
        d.citations().forEach(c -> sb.append('[').append(c.n()).append("] ").append(c.title())
                .append(c.uri() == null ? "" : " — " + c.uri()).append('\n'));
        return sb.toString();
    }
}
