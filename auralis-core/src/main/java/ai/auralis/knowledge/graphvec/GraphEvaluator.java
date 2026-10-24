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
 * Measures the TurboVec knowledge graph against exact search and the flat
 * TurboVec index. Queries are paraphrase-free spans of random chunks (a sentence
 * from the middle), so exact search over full embeddings is the ground truth.
 */
@Service
public class GraphEvaluator {

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

    public String sweep(int n, int k) {
        StringBuilder sb = new StringBuilder();
        int saved = GraphRetriever.EF;
        try {
            for (int ef : new int[] {64, 128, 200, 320, 512}) {
                GraphRetriever.EF = ef;
                String r = evaluate(n, k);
                sb.append("ef=").append(ef).append(": ")
                        .append(r.lines().filter(l -> l.contains("knowledge graph")).findFirst().orElse(r).trim())
                        .append('\n');
            }
        } finally {
            GraphRetriever.EF = saved;
        }
        return sb.toString();
    }

    public String evaluate(int n, int k) {
        if (!graph.ready()) {
            return "Graph not ready: " + index.status();
        }
        List<String> texts = store.sampleChunkTexts(n, 0.42);
        double recTv = 0;
        double recGr = 0;
        double top1Tv = 0;
        double top1Gr = 0;
        long msExact = 0;
        long msTv = 0;
        long msGr = 0;
        long scored = 0;
        int done = 0;
        for (String t : texts) {
            String query = span(t);
            float[] q = embedder.embedQuery(query);
            long t0 = System.nanoTime();
            List<UUID> truth = ids(store.exactSearch(q, k));
            long t1 = System.nanoTime();
            List<UUID> tv;
            try {
                tv = ids(store.turbovecSearch(q, k));
            } catch (RuntimeException e) {
                tv = List.of();
            }
            long t2 = System.nanoTime();
            List<UUID> gr = graph.search(query, q, k, Scope.ALL).stream()
                    .filter(p -> !p.bridge()).map(p -> p.chunk().chunkId()).toList();
            long t3 = System.nanoTime();
            scored += index.lastWalk().scored();
            if (truth.isEmpty()) {
                continue;
            }
            done++;
            recTv += overlap(truth, tv) / (double) truth.size();
            recGr += overlap(truth, gr) / (double) truth.size();
            top1Tv += !tv.isEmpty() && tv.getFirst().equals(truth.getFirst()) ? 1 : 0;
            top1Gr += !gr.isEmpty() && gr.getFirst().equals(truth.getFirst()) ? 1 : 0;
            msExact += t1 - t0;
            msTv += t2 - t1;
            msGr += t3 - t2;
        }
        if (done == 0) {
            return "No evaluation queries.";
        }
        return """
                %d queries, k=%d, ground truth = exact cosine over full embeddings
                                       recall@%d   top-1    avg ms
                  exact (seq scan)        1.000    1.000   %7.1f
                  TurboVec flat scan      %.3f    %.3f   %7.1f
                  TurboVec knowledge graph %.3f    %.3f   %7.1f   (walk scores %d of %d nodes)
                %s""".formatted(done, k, k, msExact / 1e6 / done,
                recTv / done, top1Tv / done, msTv / 1e6 / done,
                recGr / done, top1Gr / done, msGr / 1e6 / done, scored / Math.max(1, texts.size()),
                index.snapshotSize(), index.status());
    }

    /** A sentence-sized span from the middle of a chunk. */
    private static String span(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        int mid = flat.length() / 3;
        int start = Math.max(0, flat.lastIndexOf(' ', mid));
        return flat.substring(start, Math.min(flat.length(), start + 160)).trim();
    }

    private static List<UUID> ids(List<ChunkHit> hits) {
        return hits.stream().map(ChunkHit::chunkId).toList();
    }

    private static int overlap(List<UUID> a, List<UUID> b) {
        Set<UUID> s = new HashSet<>(a);
        int c = 0;
        for (UUID u : b) {
            if (s.contains(u)) {
                c++;
            }
        }
        return c;
    }
}
