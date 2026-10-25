package ai.auralis.knowledge.retrieval;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Service;

import ai.auralis.config.AuralisProperties;
import ai.auralis.knowledge.graphvec.GraphRetriever;
import ai.auralis.knowledge.ingest.Embedder;
import ai.auralis.knowledge.store.KnowledgeStore;
import ai.auralis.knowledge.store.KnowledgeStore.ChunkHit;

/**
 * Hybrid retrieval: the flat TurboVec index, the TurboVec knowledge graph (2-bit
 * codes + relationship edges in one index, searched by a graph walk) and lexical
 * full text run in parallel, are fused with RRF, and the head is reranked by Laya.
 * The flat index stays until the graph walk matches its recall (knowledge_graph_eval).
 */
@Service
public class HybridRetriever {

    private final Embedder embedder;
    private final KnowledgeStore store;
    private final LayaReranker reranker;
    private final GraphRetriever graph;
    private final AuralisProperties.Retrieval cfg;

    public HybridRetriever(Embedder embedder, KnowledgeStore store, LayaReranker reranker,
                           GraphRetriever graph, AuralisProperties props) {
        this.embedder = embedder;
        this.store = store;
        this.reranker = reranker;
        this.graph = graph;
        this.cfg = props.retrieval();
    }

    public List<Evidence> retrieve(String query, int topK, boolean rerank) {
        return retrieve(query, topK, rerank, Scope.ALL);
    }

    public List<Evidence> retrieve(String query, int topK, boolean rerank, Scope scope) {
        int n = cfg.candidatesPerRetriever();
        float[] q = embedder.embedQuery(query);
        List<ChunkHit> vector;
        List<ChunkHit> keyword;
        List<ChunkHit> related;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var v = CompletableFuture.supplyAsync(() -> store.vectorSearch(q, n, scope), pool);
            var k = CompletableFuture.supplyAsync(() -> store.keywordSearch(query, n, scope), pool);
            var g = CompletableFuture.supplyAsync(() -> graph.ready()
                    ? graph.search(query, q, scope).stream().map(GraphRetriever.PathHit::chunk).toList()
                    : List.<ChunkHit>of(), pool);
            vector = v.join();
            keyword = k.join();
            related = g.exceptionally(e -> List.of()).join();
        }
        List<Evidence> fused = ReciprocalRankFusion.fuse(vector, keyword, related, cfg.rrfK());
        if (rerank && !fused.isEmpty()) {
            List<Evidence> head = fused.subList(0, Math.min(cfg.rerankTopN(), fused.size()));
            fused = reranker.rerank(query, head);
        }
        return fused.subList(0, Math.min(topK, fused.size()));
    }
}
