package ai.auralis.knowledge.graphvec;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ai.auralis.knowledge.ingest.Embedder;
import ai.auralis.knowledge.retrieval.Scope;
import ai.auralis.knowledge.store.KnowledgeStore;
import ai.auralis.knowledge.store.KnowledgeStore.ChunkHit;

/**
 * Measures the TurboVec knowledge graph expansion against exact search. Queries
 * are sentence spans cut from random chunks; ground truth is the exact top-10 by
 * cosine over full embeddings. The expansion has no k, so it is judged by how much
 * of the truth its accepted set covers, how large that set is, and how many nodes
 * it scored to get there.
 */
@Service
public class GraphEvaluator {

    private static final int TRUTH_K = 10;

    private final KnowledgeStore store;
    private final GraphRetriever graph;
    private final GraphVecIndex index;
    private final Embedder embedder;

    public GraphEvaluator(KnowledgeStore store, GraphRetriever graph, GraphVecIndex index, Embedder embedder) {
        this.store = store;
        this.graph = graph;
        this.index = index;
        this.embedder = embedder;
    }

    /** Runs the same queries at several acceptance margins. */
    public String evaluate(int n, float[] deltas) {
        if (!graph.ready()) {
            return "Graph not ready: " + index.status();
        }
        record Query(String text, float[] q, List<UUID> truth) {
        }
        List<Query> queries = new java.util.ArrayList<>();
        long msExact = 0;
        for (String t : store.sampleChunkTexts(n, 0.42)) {
            String text = span(t);
            float[] q = embedder.embedQuery(text);
            long t0 = System.nanoTime();
            List<UUID> truth = store.exactSearch(q, TRUTH_K).stream().map(ChunkHit::chunkId).toList();
            msExact += System.nanoTime() - t0;
            if (!truth.isEmpty()) {
                queries.add(new Query(text, q, truth));
            }
        }
        if (queries.isEmpty()) {
            return "No evaluation queries.";
        }
        StringBuilder sb = new StringBuilder("""
                %d queries; truth = exact top-%d by cosine (seq scan %.0f ms/query); %s
                 delta  covers-truth  top-1-in-set  accepted/query  bridge/query  nodes-scored/query  ms/query
                """.formatted(queries.size(), TRUTH_K, msExact / 1e6 / queries.size(), index.status()));
        float saved = GraphRetriever.DELTA;
        try {
            for (float d : deltas) {
                GraphRetriever.DELTA = d;
                double cover = 0;
                double top1 = 0;
                double size = 0;
                double bridge = 0;
                double scored = 0;
                long ns = 0;
                for (Query qu : queries) {
                    long t0 = System.nanoTime();
                    List<GraphRetriever.PathHit> got = graph.search(qu.text(), qu.q(), Scope.ALL);
                    ns += System.nanoTime() - t0;
                    scored += index.lastWalk().scored();
                    Set<UUID> ids = new HashSet<>();
                    got.forEach(p -> ids.add(p.chunk().chunkId()));
                    cover += qu.truth().stream().filter(ids::contains).count() / (double) qu.truth().size();
                    top1 += ids.contains(qu.truth().getFirst()) ? 1 : 0;
                    size += got.size();
                    bridge += got.stream().filter(GraphRetriever.PathHit::bridge).count();
                }
                int m = queries.size();
                sb.append(" %.2f      %.3f         %.3f          %7.1f        %5.1f          %8.0f         %6.1f%n"
                        .formatted(d, cover / m, top1 / m, size / m, bridge / m, scored / m, ns / 1e6 / m));
            }
        } finally {
            GraphRetriever.DELTA = saved;
        }
        return sb.toString();
    }

    /** A sentence-sized span from the middle of a chunk. */
    private static String span(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        int mid = flat.length() / 3;
        int start = Math.max(0, flat.lastIndexOf(' ', mid));
        return flat.substring(start, Math.min(flat.length(), start + 160)).trim();
    }
}
