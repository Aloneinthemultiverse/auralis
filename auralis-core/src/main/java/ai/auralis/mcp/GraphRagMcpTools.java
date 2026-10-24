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
            @McpToolParam(description = "Number of passages, 1-20 (default 5)", required = false) Integer topK) {
        if (!retriever.ready()) {
            return "Graph index not ready: " + index.status();
        }
        int k = topK == null ? 5 : Math.max(1, Math.min(20, topK));
        List<PathHit> hits = retriever.search(query, embedder.embedQuery(query), k, Scope.ALL);
        if (hits.isEmpty()) {
            return "No connected passages found.";
        }
        StringBuilder sb = new StringBuilder();
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
            Measure the TurboVec knowledge graph: recall and latency of the 2-bit graph walk and
            of the flat TurboVec scan against exact search, on queries cut from random chunks.""")
    public String eval(
            @McpToolParam(description = "Number of queries (default 50)", required = false) Integer queries,
            @McpToolParam(description = "k (default 10)", required = false) Integer k,
            @McpToolParam(description = "Sweep beam widths instead of one run", required = false) Boolean sweep) {
        if (Boolean.TRUE.equals(sweep)) {
            return evaluator.sweep(queries == null ? 50 : Math.max(5, Math.min(500, queries)), k == null ? 10 : k);
        }
        return evaluator.evaluate(queries == null ? 50 : Math.max(5, Math.min(500, queries)),
                k == null ? 10 : Math.max(1, Math.min(50, k)));
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
