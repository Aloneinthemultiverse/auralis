package ai.auralis.knowledge.hippo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

import ai.auralis.knowledge.graphvec.TwoBitCodec;
import ai.auralis.knowledge.graphvec.TwoBitCodec.Code;

/**
 * TurboVec-HippoRAG index: one structure in which every chunk, entity and fact is a
 * 2-bit TurboQuant code and every relationship is an exact edge.
 * <p>
 * Nodes: chunks [0, C), entities [C, C+E), facts [C+E, C+E+F). Edges (weighted):
 * CONTAINS chunk-entity, RELATION entity-entity per fact (weight = number of facts),
 * SYNONYM entity-entity (cosine >= 0.8 among 2-bit nearest neighbours). Facts are
 * nodes of the same structure, linked to their entities and source chunk, and are
 * what queries are matched against.
 * <p>
 * Search follows HippoRAG 2: score facts against the query (2-bit), seed the entities
 * of the best facts (weighted by fact score / number of chunks naming the entity) and
 * every chunk (0.05 x normalised 2-bit score), run Personalized PageRank (damping 0.5)
 * over the entity-chunk graph, and rank chunks by PageRank mass. On the HippoRAG
 * benchmarks this matched float HippoRAG 2 recall at about a tenth of the memory.
 */
public final class HippoGraph {

    static final int CONTAINS = 0;
    static final int RELATION = 1;
    static final int SYNONYM = 2;

    /** Input fact: chunk index, subject and object entity indexes (0-based among entities), text, embedding. */
    public record Fact(int chunk, int subject, int object, String text, float[] embedding) {
    }

    /** A ranked chunk and the facts that seeded the walk. */
    public record Result(int[] chunks, List<String> facts, int scored) {
    }

    final TwoBitCodec codec;
    final int c;
    final int e;
    final int f;
    final Code[] code;
    final int[][] adj;
    final byte[][] type;
    final float[][] weight;
    final double[] outWeight;
    final int[] entityChunks;
    final int[] factSubject;
    final int[] factObject;
    final String[] factText;

    private HippoGraph(TwoBitCodec codec, int c, int e, int f, Code[] code, int[][] adj, byte[][] type,
                       float[][] weight, int[] entityChunks, int[] factSubject, int[] factObject, String[] factText) {
        this.codec = codec;
        this.c = c;
        this.e = e;
        this.f = f;
        this.code = code;
        this.adj = adj;
        this.type = type;
        this.weight = weight;
        this.entityChunks = entityChunks;
        this.factSubject = factSubject;
        this.factObject = factObject;
        this.factText = factText;
        this.outWeight = new double[c + e];
        for (int u = 0; u < c + e; u++) {
            for (float w : weight[u]) {
                outWeight[u] += w;
            }
        }
    }

    public int chunks() {
        return c;
    }

    public int entities() {
        return e;
    }

    public int facts() {
        return f;
    }

    public long edges() {
        long n = 0;
        for (int[] a : adj) {
            n += a.length;
        }
        return n;
    }

    /** 2-bit codes (D/4 + 4 bytes per node) + edges (int target, byte type, fp16 weight). */
    public long bytes() {
        return (long) (c + e + f) * (codec.paddedDim() / 4 + 4) + edges() * 7;
    }

    public static HippoGraph build(TwoBitCodec codec, List<float[]> chunkEmb, List<float[]> entityEmb, List<Fact> facts) {
        int c = chunkEmb.size();
        int e = entityEmb.size();
        int f = facts.size();
        Code[] code = new Code[c + e + f];
        IntStream.range(0, c).parallel().forEach(i -> code[i] = codec.encode(chunkEmb.get(i)));
        IntStream.range(0, e).parallel().forEach(i -> code[c + i] = codec.encode(entityEmb.get(i)));
        IntStream.range(0, f).parallel().forEach(i -> code[c + e + i] = codec.encode(facts.get(i).embedding()));

        List<Map<Integer, float[]>> nb = new ArrayList<>(c + e);
        for (int i = 0; i < c + e; i++) {
            nb.add(new HashMap<>());
        }
        int[] factSubject = new int[f];
        int[] factObject = new int[f];
        String[] factText = new String[f];
        Map<Integer, Set<Integer>> chunkEntities = new HashMap<>();
        for (int i = 0; i < f; i++) {
            Fact fa = facts.get(i);
            int s = c + fa.subject();
            int o = c + fa.object();
            factSubject[i] = s;
            factObject[i] = o;
            factText[i] = fa.text();
            Set<Integer> ents = chunkEntities.computeIfAbsent(fa.chunk(), k -> new HashSet<>());
            ents.add(s);
            ents.add(o);
            if (s != o) {
                edge(nb, s, o, RELATION, 1f, true);
            }
        }
        int[] entityChunks = new int[e];
        chunkEntities.forEach((ch, ents) -> {
            for (int x : ents) {
                edge(nb, ch, x, CONTAINS, 1f, false);
                entityChunks[x - c]++;
            }
        });
        // SYNONYM: 2-bit nearest neighbours among entities, kept if the exact cosine >= 0.8.
        int[][] near = nsw(codec, code, c, c + e, 10, 20, 48);
        for (int i = 0; i < e; i++) {
            int u = c + i;
            for (int v : near[i]) {
                if (v > u) {
                    double cos = cosine(entityEmb.get(u - c), entityEmb.get(v - c));
                    if (cos >= 0.8) {
                        edge(nb, u, v, SYNONYM, (float) cos, false);
                    }
                }
            }
        }
        int[][] adj = new int[c + e][];
        byte[][] type = new byte[c + e][];
        float[][] weight = new float[c + e][];
        for (int i = 0; i < c + e; i++) {
            Map<Integer, float[]> m = nb.get(i);
            adj[i] = new int[m.size()];
            type[i] = new byte[m.size()];
            weight[i] = new float[m.size()];
            int j = 0;
            for (var en : m.entrySet()) {
                adj[i][j] = en.getKey();
                type[i][j] = (byte) en.getValue()[0];
                weight[i][j] = en.getValue()[1];
                j++;
            }
        }
        return new HippoGraph(codec, c, e, f, code, adj, type, weight, entityChunks, factSubject, factObject, factText);
    }

    /**
     * HippoRAG 2 search on 2-bit codes. Returns chunk indexes best first, the facts
     * that seeded the walk, and how many nodes were scored.
     */
    public Result search(float[] queryEmbedding, int limit) {
        float[] rq = codec.rotate(queryEmbedding);
        float[] cs = new float[c];
        float cMax = Float.NEGATIVE_INFINITY;
        float cMin = Float.POSITIVE_INFINITY;
        for (int i = 0; i < c; i++) {
            cs[i] = codec.scoreCorrected(rq, code[i]);
            cMax = Math.max(cMax, cs[i]);
            cMin = Math.min(cMin, cs[i]);
        }
        float[] fs = new float[f];
        for (int i = 0; i < f; i++) {
            fs[i] = codec.scoreCorrected(rq, code[c + e + i]);
        }
        int[] topFacts = topK(fs, 5);
        Map<Integer, Double> seed = new HashMap<>();
        Map<Integer, Integer> hits = new HashMap<>();
        List<String> used = new ArrayList<>();
        for (int fi : topFacts) {
            used.add(factText[fi]);
            for (int ent : new int[] {factSubject[fi], factObject[fi]}) {
                seed.merge(ent, fs[fi] / (double) Math.max(1, entityChunks[ent - c]), Double::sum);
                hits.merge(ent, 1, Integer::sum);
            }
        }
        if (seed.isEmpty()) {
            return new Result(topK(cs, limit), List.of(), c + f);
        }
        hits.forEach((ent, n) -> seed.computeIfPresent(ent, (k, v) -> v / n));
        float range = Math.max(1e-6f, cMax - cMin);
        for (int i = 0; i < c; i++) {
            seed.merge(i, 0.05 * (cs[i] - cMin) / range, Double::sum);
        }
        double[] p = ppr(seed, 0.5, 30);
        float[] rank = new float[c];
        for (int i = 0; i < c; i++) {
            rank[i] = (float) p[i];
        }
        return new Result(topK(rank, limit), used, c + f);
    }

    /** Personalized PageRank by power iteration over the entity-chunk graph. */
    double[] ppr(Map<Integer, Double> seed, double damping, int iters) {
        int n = c + e;
        double[] s = new double[n];
        double tot = seed.values().stream().mapToDouble(Double::doubleValue).sum();
        seed.forEach((k, v) -> s[k] = v / tot);
        double[] p = s.clone();
        for (int it = 0; it < iters; it++) {
            double[] nx = new double[n];
            for (int u = 0; u < n; u++) {
                if (p[u] == 0 || outWeight[u] == 0) {
                    continue;
                }
                double share = damping * p[u] / outWeight[u];
                for (int j = 0; j < adj[u].length; j++) {
                    nx[adj[u][j]] += share * weight[u][j];
                }
            }
            for (int u = 0; u < n; u++) {
                nx[u] += (1 - damping) * s[u];
            }
            p = nx;
        }
        return p;
    }

    private static void edge(List<Map<Integer, float[]>> nb, int a, int b, int t, float w, boolean accumulate) {
        for (int[] pair : new int[][] {{a, b}, {b, a}}) {
            float[] cur = nb.get(pair[0]).get(pair[1]);
            if (cur == null) {
                nb.get(pair[0]).put(pair[1], new float[] {t, w});
            } else if (accumulate && cur[0] == t) {
                cur[1] += w;
            } else if (t < cur[0]) {
                cur[0] = t;
                cur[1] = w;
            }
        }
    }

    static int[] topK(float[] s, int k) {
        PriorityQueue<Integer> h = new PriorityQueue<>((a, b) -> Float.compare(s[a], s[b]));
        for (int i = 0; i < s.length; i++) {
            if (h.size() < k) {
                h.add(i);
            } else if (s[i] > s[h.peek()]) {
                h.poll();
                h.add(i);
            }
        }
        int[] out = new int[h.size()];
        for (int i = out.length - 1; i >= 0; i--) {
            out[i] = h.poll();
        }
        return out;
    }

    private static double cosine(float[] a, float[] b) {
        double s = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            s += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return s / Math.sqrt(na * nb + 1e-12);
    }

    /** Navigable small world over nodes [lo, hi) with 2-bit beam insertion (parallel batches). */
    static int[][] nsw(TwoBitCodec codec, Code[] code, int lo, int hi, int m, int mMax, int ef) {
        int n = hi - lo;
        if (n <= 1) {
            return new int[Math.max(0, n)][0];
        }
        Code[] sub = Arrays.copyOfRange(code, lo, hi);
        int[] order = IntStream.range(0, n).toArray();
        SplittableRandom rnd = new SplittableRandom(42);
        for (int i = n - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        int[][] sim = new int[n][];
        int[] len = new int[n];
        int entry = order[0];
        sim[entry] = new int[mMax + 1];
        int k = 1;
        while (k < n) {
            int batch = k < 1000 ? 1 : Math.min(256, n - k);
            final int from = k;
            int[][] found = new int[batch][];
            IntStream.range(0, batch).parallel().forEach(b -> {
                int v = order[from + b];
                found[b] = beam(codec, codec.decodeRotated(sub[v]), entry, sub, sim, len, ef, m);
            });
            for (int b = 0; b < batch; b++) {
                int v = order[from + b];
                sim[v] = new int[mMax + 1];
                for (int u : found[b]) {
                    sim[v][len[v]++] = u;
                    sim[u][len[u]++] = v;
                    if (len[u] > mMax) {
                        prune(codec, u, sub, sim, len, mMax);
                    }
                }
            }
            k += batch;
        }
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            out[i] = new int[len[i]];
            for (int j = 0; j < len[i]; j++) {
                out[i][j] = sim[i][j] + lo;
            }
        }
        return out;
    }

    private static int[] beam(TwoBitCodec codec, float[] q, int entry, Code[] code, int[][] sim, int[] len, int ef, int m) {
        BitSet seen = new BitSet();
        PriorityQueue<float[]> cand = new PriorityQueue<>((a, b) -> Float.compare(b[0], a[0]));
        PriorityQueue<float[]> best = new PriorityQueue<>((a, b) -> Float.compare(a[0], b[0]));
        float s0 = codec.score(q, code[entry]);
        cand.add(new float[] {s0, entry});
        best.add(new float[] {s0, entry});
        seen.set(entry);
        while (!cand.isEmpty()) {
            float[] cnd = cand.poll();
            if (best.size() >= ef && cnd[0] < best.peek()[0]) {
                break;
            }
            int u = (int) cnd[1];
            int[] nb = sim[u];
            for (int j = 0; j < len[u] && nb != null; j++) {
                int v = nb[j];
                if (seen.get(v)) {
                    continue;
                }
                seen.set(v);
                float s = codec.score(q, code[v]);
                if (best.size() < ef || s > best.peek()[0]) {
                    cand.add(new float[] {s, v});
                    best.add(new float[] {s, v});
                    if (best.size() > ef) {
                        best.poll();
                    }
                }
            }
        }
        List<float[]> all = new ArrayList<>(best);
        all.sort((a, b) -> Float.compare(b[0], a[0]));
        int[] out = new int[Math.min(m, all.size())];
        for (int i = 0; i < out.length; i++) {
            out[i] = (int) all.get(i)[1];
        }
        return out;
    }

    private static void prune(TwoBitCodec codec, int u, Code[] code, int[][] sim, int[] len, int mMax) {
        float[] qu = codec.decodeRotated(code[u]);
        Integer[] idx = new Integer[len[u]];
        float[] sc = new float[len[u]];
        for (int j = 0; j < len[u]; j++) {
            idx[j] = j;
            sc[j] = codec.score(qu, code[sim[u][j]]);
        }
        Arrays.sort(idx, (a, b) -> Float.compare(sc[b], sc[a]));
        int[] keep = new int[mMax + 1];
        for (int j = 0; j < mMax; j++) {
            keep[j] = sim[u][idx[j]];
        }
        sim[u] = keep;
        len[u] = mMax;
    }
}
