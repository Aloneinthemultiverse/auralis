package ai.auralis.mcp;

import java.nio.file.Path;
import java.util.List;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ai.auralis.knowledge.hippo.FactExtractor;
import ai.auralis.knowledge.hippo.HippoImporter;
import ai.auralis.knowledge.hippo.HippoIndex;
import ai.auralis.knowledge.ingest.Embedder;
import ai.auralis.knowledge.retrieval.Scope;
import ai.auralis.knowledge.store.KnowledgeStore;
import ai.auralis.knowledge.store.KnowledgeStore.ChunkHit;

/** TurboVec-HippoRAG over MCP: fact-graph search, status, fact extraction and import. */
@Component
public class HippoMcpTools {

    private final HippoIndex index;
    private final FactExtractor extractor;
    private final HippoImporter importer;
    private final Embedder embedder;
    private final KnowledgeStore store;
    private final String importDir;

    public HippoMcpTools(HippoIndex index, FactExtractor extractor, HippoImporter importer, Embedder embedder,
                         KnowledgeStore store, @Value("${auralis.hippo.import-dir:../../turbograph-research/data/auralis}") String importDir) {
        this.index = index;
        this.extractor = extractor;
        this.importer = importer;
        this.embedder = embedder;
        this.store = store;
        this.importDir = importDir;
    }

    @McpTool(name = "knowledge_hippo_search", description = """
            Multi-hop search with TurboVec-HippoRAG: facts (subject -relation-> object) extracted
            from the knowledge base, their entities and the chunks live in one 2-bit index with
            exact relationship edges. The query is matched to facts, their entities seed a
            Personalized PageRank over the graph, and chunks are ranked by it. Returns the facts
            used and the passages. knowledge_search already blends this in when the index is ready.""")
    public String search(
            @McpToolParam(description = "Question") String query,
            @McpToolParam(description = "Passages to return, 1-20 (default 5)", required = false) Integer topK) {
        if (!index.ready()) {
            return "TurboVec-HippoRAG index not ready: " + index.status();
        }
        int k = topK == null ? 5 : Math.max(1, Math.min(20, topK));
        float[] q = embedder.embedQuery(query);
        HippoIndex.Hits hits = index.search(q, k);
        List<ChunkHit> chunks = store.inOrder(q, hits.chunkIds(), Scope.ALL);
        StringBuilder sb = new StringBuilder("Facts that seeded the walk:\n");
        hits.facts().forEach(f -> sb.append("  • ").append(f).append('\n'));
        sb.append('\n');
        for (int i = 0; i < chunks.size(); i++) {
            ChunkHit c = chunks.get(i);
            String text = c.content();
            sb.append('[').append(i + 1).append("] ").append(c.documentTitle()).append(" (chunk ")
                    .append(c.ordinal()).append(String.format(", cos %.2f)%n", c.rawScore()))
                    .append(text.length() > 500 ? text.substring(0, 500) + "…" : text).append("\n\n");
        }
        return sb.toString();
    }

    @McpTool(name = "knowledge_hippo_status", description = """
            State of TurboVec-HippoRAG: graph size, facts stored, extraction queue. rebuild=true
            rebuilds the index now.""")
    public String status(@McpToolParam(description = "Rebuild now", required = false) Boolean rebuild) {
        if (Boolean.TRUE.equals(rebuild)) {
            index.rebuild();
        }
        return index.status() + "\nExtractor: " + extractor.status();
    }

    @McpTool(name = "knowledge_facts_extract", description = """
            Queue chunks without facts for local extraction by the Auralis model (slow on CPU,
            ~10-30 s per chunk). New documents are queued automatically.""")
    public String extract(@McpToolParam(description = "How many chunks to queue (default 20)", required = false) Integer limit) {
        int n = extractor.backfill(limit == null ? 20 : Math.max(1, Math.min(5000, limit)));
        return "queued " + n + " chunks; " + extractor.status();
    }

    @McpTool(name = "knowledge_facts_import", description = """
            Import facts extracted in bulk on Kaggle (turbograph-research data/auralis: auralis.corpus.tsv
            and turbohippo/auralis.{entities,facts}.*), then rebuild the index.""")
    public String importFacts(@McpToolParam(description = "Folder (default from auralis.hippo.import-dir)", required = false) String dir) {
        Path base = Path.of(dir == null || dir.isBlank() ? importDir : dir);
        try {
            String r = importer.importDir(base.resolve("auralis.corpus.tsv"), base.resolve("turbohippo"), "auralis");
            index.rebuild();
            return r + "\n" + index.status();
        } catch (Exception e) {
            return "Import failed: " + e.getMessage();
        }
    }
}
