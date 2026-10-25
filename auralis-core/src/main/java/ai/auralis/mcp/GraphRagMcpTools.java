package ai.auralis.mcp;

import java.util.List;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import ai.auralis.knowledge.graphvec.GraphEvaluator;
import ai.auralis.knowledge.graphvec.GraphRetriever;
import ai.auralis.knowledge.graphvec.GraphRetriever.PathHit;
import ai.auralis.knowledge.graphvec.GraphVecIndex;
import ai.auralis.knowledge.ingest.Embedder;
import ai.auralis.knowledge.retrieval.Scope;

/** 2-bit graph RAG over MCP: relationship search with the paths that explain each passage. */
@Component
public class GraphRagMcpTools {

    private final GraphRetriever retriever;
    private final GraphVecIndex index;
    private final Embedder embedder;
    private final GraphEvaluator evaluator;

    public GraphRagMcpTools(GraphRetriever retriever, GraphVecIndex index, Embedder embedder,
                            GraphEvaluator evaluator) {
        this.retriever = retriever;
        this.index = index;
        this.embedder = embedder;
        this.evaluator = evaluator;
    }

    @McpTool(name = "knowledge_graph_search", description = """
            Search the TurboVec knowledge graph: one index where every chunk, document and
            technical term (AXI, CVA6, LPDDR4, ...) is a 2-bit TurboQuant code and MENTIONS /
            PART_OF / NEXT / SIMILAR edges connect them. Retrieval walks the graph scoring nodes
            with their 2-bit codes, then walks again with the residual query to fetch evidence for
            the rest of a multi-part question (BRIDGE). Returns each passage with its path.
            knowledge_search uses this same walk; use this tool to see the relationships.""")
    public String search(
            @McpToolParam(description = "Question or terms") String query,
            @McpToolParam(description = "How many of the accepted passages to print (default 8); retrieval itself has no k",
                    required = false) Integer show) {
        if (!retriever.ready()) {
            return "Graph index not ready: " + index.status();
        }
        int k = show == null ? 8 : Math.max(1, Math.min(50, show));
        List<PathHit> all = retriever.search(query, embedder.embedQuery(query), Scope.ALL);
        var w = index.lastWalk();
        List<PathHit> hits = all.subList(0, Math.min(k, all.size()));
        if (hits.isEmpty()) {
            return "No connected passages found.";
        }
        StringBuilder sb = new StringBuilder("Expansion accepted %d passages (%d bridge) after scoring %d nodes; showing %d.%n%n"
                .formatted(all.size(), all.stream().filter(PathHit::bridge).count(), w.scored(), hits.size()));
        for (int i = 0; i < hits.size(); i++) {
            PathHit p = hits.get(i);
            String text = p.chunk().content();
            sb.append('[').append(i + 1).append("] ").append(p.chunk().documentTitle())
                    .append(" (chunk ").append(p.chunk().ordinal())
                    .append(String.format(", cos %.2f)%s%n", p.chunk().rawScore(), p.bridge() ? " BRIDGE" : ""))
                    .append("   path: ").append(p.path()).append('\n')
                    .append(text.length() > 500 ? text.substring(0, 500) + "…" : text).append("\n\n");
        }
        return sb.toString();
    }

    @McpTool(name = "knowledge_graph_eval", description = """
            Measure the TurboVec knowledge graph expansion (no top-k) against exact search:
            coverage of the exact top-10, accepted-set size and nodes scored, per acceptance margin.""")
    public String eval(
            @McpToolParam(description = "Number of queries (default 50)", required = false) Integer queries,
            @McpToolParam(description = "Comma-separated margins (default 0.04,0.08,0.12,0.16,0.20)",
                    required = false) String deltas) {
        String[] parts = (deltas == null || deltas.isBlank() ? "0.04,0.08,0.12,0.16,0.20" : deltas).split(",");
        float[] d = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            d[i] = Float.parseFloat(parts[i].trim());
        }
        return evaluator.evaluate(queries == null ? 50 : Math.max(5, Math.min(500, queries)), d);
    }

    @McpTool(name = "knowledge_graph_status", description = "Size and state of the 2-bit graph index; set rebuild=true to rebuild it now.")
    public String status(@McpToolParam(description = "Rebuild now", required = false) Boolean rebuild) {
        if (Boolean.TRUE.equals(rebuild)) {
            index.rebuild();
        }
        var w = index.lastWalk();
        return index.status() + "; last walk scored %d nodes, expanded %d".formatted(w.scored(), w.expanded());
    }
}
