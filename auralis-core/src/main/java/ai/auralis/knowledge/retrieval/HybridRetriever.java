package ai.auralis.knowledge.retrieval;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Service;

import ai.auralis.config.AuralisProperties;
import ai.auralis.knowledge.ingest.Embedder;
import ai.auralis.knowledge.store.KnowledgeStore;
import ai.auralis.knowledge.store.KnowledgeStore.ChunkHit;

/**
 * Hybrid retrieval: dense (pgvector) and lexical (Postgres full text) searches
 * run in parallel, are fused with RRF, and the head of the fused list is
 * reranked by Laya.
 */
@Service
public class HybridRetriever {

    private final Embedder embedder;
    private final KnowledgeStore store;
    private final LayaReranker reranker;
    private final AuralisProperties.Retrieval cfg;

    public HybridRetriever(Embedder embedder, KnowledgeStore store, LayaReranker reranker,
                           AuralisProperties props) {
        this.embedder = embedder;
        this.store = store;
        this.reranker = reranker;
        this.cfg = props.retrieval();
    }

    public List<Evidence> retrieve(String query, int topK, boolean rerank) {
        return retrieve(query, topK, rerank, Scope.ALL);
    }

    public List<Evidence> retrieve(String query, int topK, boolean rerank, Scope scope) {
        int n = cfg.candidatesPerRetriever();
        List<ChunkHit> vector;
        List<ChunkHit> keyword;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var v = CompletableFuture.supplyAsync(
                    () -> store.vectorSearch(embedder.embedQuery(query), n, scope), pool);
            var k = CompletableFuture.supplyAsync(() -> store.keywordSearch(query, n, scope), pool);
            vector = v.join();
            keyword = k.join();
        }
        List<Evidence> fused = ReciprocalRankFusion.fuse(vector, keyword, cfg.rrfK());
        if (rerank && !fused.isEmpty()) {
            List<Evidence> head = fused.subList(0, Math.min(cfg.rerankTopN(), fused.size()));
            fused = reranker.rerank(query, head);
        }
        return fused.subList(0, Math.min(topK, fused.size()));
    }
}
