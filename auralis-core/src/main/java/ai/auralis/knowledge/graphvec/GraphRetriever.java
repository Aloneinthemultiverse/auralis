package ai.auralis.knowledge.graphvec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ai.auralis.knowledge.retrieval.Scope;
import ai.auralis.knowledge.store.KnowledgeStore;
import ai.auralis.knowledge.store.KnowledgeStore.ChunkHit;

/**
 * Retrieval from the TurboVec knowledge graph: the 2-bit walk proposes chunks
 * with the path that reached them; Postgres re-scores the direct hits exactly.
 * Bridge hits (from the residual walk) answer the rest of the question, so they
 * are kept after the direct hits rather than ranked by similarity to the whole query.
 */
@Service
public class GraphRetriever {

    /** A chunk, the graph path that reached it, and whether it is bridge evidence. */
    public record PathHit(ChunkHit chunk, String path, boolean bridge) {
    }

    /** Walk beam width; overridable for evaluation. */
    static volatile int EF = 200;

    private final GraphVecIndex index;
    private final KnowledgeStore store;

    public GraphRetriever(GraphVecIndex index, KnowledgeStore store) {
        this.index = index;
        this.store = store;
    }

    public boolean ready() {
        return index.ready();
    }

    public List<PathHit> search(String query, float[] queryEmbedding, int limit, Scope scope) {
        List<GraphVecIndex.Hit> hits = index.search(query, queryEmbedding, limit * 10, Math.max(EF, limit * 16));
        if (hits.isEmpty()) {
            return List.of();
        }
        Map<UUID, GraphVecIndex.Hit> byId = new HashMap<>();
        hits.forEach(h -> byId.putIfAbsent(h.chunkId(), h));
        List<UUID> direct = hits.stream().filter(h -> !h.bridge()).map(GraphVecIndex.Hit::chunkId).toList();
        List<UUID> bridge = hits.stream().filter(GraphVecIndex.Hit::bridge).map(GraphVecIndex.Hit::chunkId).toList();
        List<PathHit> out = new ArrayList<>();
        for (ChunkHit c : store.exactRescore(queryEmbedding, direct, limit, scope)) {
            out.add(new PathHit(c, byId.get(c.chunkId()).path(), false));
        }
        int rank = out.size();
        for (ChunkHit c : store.exactRescore(queryEmbedding, bridge, Math.max(1, limit / 3), scope)) {
            out.add(new PathHit(new ChunkHit(c.chunkId(), c.documentId(), c.documentTitle(), c.ordinal(),
                    c.content(), ++rank, c.rawScore()), byId.get(c.chunkId()).path(), true));
        }
        return out;
    }
}
