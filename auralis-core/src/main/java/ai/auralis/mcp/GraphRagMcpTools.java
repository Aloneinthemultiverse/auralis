package ai.auralis.mcp;

import java.util.List;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

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

    public GraphRagMcpTools(GraphRetriever retriever, GraphVecIndex index, Embedder embedder) {
        this.retriever = retriever;
        this.index = index;
        this.embedder = embedder;
    }

    @McpTool(name = "knowledge_graph_search", description = """
            Relationship search over the knowledge base (2-bit graph RAG). Every chunk, document
            and technical term (AXI, CVA6, LPDDR4, ...) is a 2-bit TurboQuant-style code linked by
            MENTIONS / PART_OF / NEXT edges. Finds passages connected to the query through shared
            terms and neighbouring chunks, not only similar text, and returns each passage with the
            relation path that reached it. knowledge_search already blends this in; use this tool
            when you need the relationships themselves.""")
    public String search(
            @McpToolParam(description = "Question or terms") String query,
            @McpToolParam(description = "Number of passages, 1-20 (default 5)", required = false) Integer topK,
            @McpToolParam(description = "Graph hops, 0-3 (default 2)", required = false) Integer hops) {
        if (!retriever.ready()) {
            return "Graph index not ready: " + index.status();
        }
        int k = topK == null ? 5 : Math.max(1, Math.min(20, topK));
        int h = hops == null ? 2 : Math.max(0, Math.min(3, hops));
        List<PathHit> hits = retriever.search(embedder.embedQuery(query), k, h, Scope.ALL);
        if (hits.isEmpty()) {
            return "No connected passages found.";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            PathHit p = hits.get(i);
            String text = p.chunk().content();
            sb.append('[').append(i + 1).append("] ").append(p.chunk().documentTitle())
                    .append(" (chunk ").append(p.chunk().ordinal())
                    .append(String.format(", cos %.2f)%n", p.chunk().rawScore()))
                    .append("   path: ").append(p.path()).append('\n')
                    .append(text.length() > 500 ? text.substring(0, 500) + "…" : text).append("\n\n");
        }
        return sb.toString();
    }

    @McpTool(name = "knowledge_graph_status", description = "Size and state of the 2-bit graph index; set rebuild=true to rebuild it now.")
    public String status(@McpToolParam(description = "Rebuild now", required = false) Boolean rebuild) {
        if (Boolean.TRUE.equals(rebuild)) {
            index.rebuild();
        }
        return index.status();
    }
}
