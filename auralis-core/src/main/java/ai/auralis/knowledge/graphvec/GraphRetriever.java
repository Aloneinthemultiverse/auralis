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
 * Turns 2-bit graph hits into ranked chunks: the graph proposes candidates
 * (with the relation path that found them), Postgres re-scores them exactly.
 */
@Service
public class GraphRetriever {

    /** A re-scored chunk and the graph path that reached it. */
    public record PathHit(ChunkHit chunk, String path) {
    }

    private final GraphVecIndex index;
    private final KnowledgeStore store;

    public GraphRetriever(GraphVecIndex index, KnowledgeStore store) {
        this.index = index;
        this.store = store;
    }

    public boolean ready() {
        return index.ready();
    }

    public List<PathHit> search(float[] queryEmbedding, int limit, int hops, Scope scope) {
        List<GraphVecIndex.Hit> hits = index.search(queryEmbedding, limit * 4, hops);
        if (hits.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> paths = new HashMap<>();
        hits.forEach(h -> paths.putIfAbsent(h.chunkId(), h.path()));
        List<ChunkHit> exact = store.exactRescore(queryEmbedding,
                hits.stream().map(GraphVecIndex.Hit::chunkId).toList(), limit, scope);
        List<PathHit> out = new ArrayList<>(exact.size());
        for (ChunkHit c : exact) {
            out.add(new PathHit(c, paths.get(c.chunkId())));
        }
        return out;
    }
}
