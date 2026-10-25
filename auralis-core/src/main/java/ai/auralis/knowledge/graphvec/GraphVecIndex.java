package ai.auralis.knowledge.graphvec;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import ai.auralis.knowledge.graphvec.TwoBitCodec.Code;

/**
 * TurboVec knowledge graph: one index in which every node is a TurboQuant-style
 * 2-bit code and the knowledge-graph edges hang off those codes.
 * <p>
 * Nodes are chunks, documents and technical terms (AXI, CVA6, LPDDR4 ...). Edges,
 * stored losslessly as int adjacency, are of two families:
 * <ul>
 * <li>knowledge edges: MENTIONS (chunk-term), PART_OF (chunk-document), NEXT (chunk-chunk);</li>
 * <li>SIMILAR edges between chunks, built by inserting each chunk with a 2-bit beam
 * search over the graph so far (navigable small world), so the same graph also
 * serves as the vector index.</li>
 * </ul>
 * Retrieval is neighbour-local expansion scored only with 2-bit codes, with no
 * top-k: greedy descent to local peaks, then threshold expansion along both edge
 * families (knowledge edges with a TransE relation shift), then the same with the
 * residual query (the query minus what the best passages already answer) to reach
 * the evidence for the rest of a multi-part question. Full precision is used only to re-score the final short list.
 */
@Service
public class GraphVecIndex {

    private static final Logger log = LoggerFactory.getLogger(GraphVecIndex.class);

    public enum Kind { CHUNK, DOCUMENT, TERM }

    static final String[] RELS = {"MENTIONS", "PART_OF", "NEXT", "SIMILAR"};
    private static final int MENTIONS = 0;
    private static final int PART_OF = 1;
    private static final int NEXT = 2;
    private static final int SIMILAR = 3;

    /** Similarity edges per chunk at insertion, and the cap after back-links. */
    private static final int M = 12;
    private static final int M_MAX = 24;
    private static final int EF_BUILD = 48;
    /** Neighbours of a hub (document, common term) scored per expansion. */
    private static final int HUB_CAP = 256;
    /** Safety stop for a degenerate threshold; not a k. */
    private static final int MAX_ACCEPTED = 20_000;

    private static final Pattern TERM = Pattern.compile(
            "\\b(?:[A-Z][A-Z0-9]{1,11}(?:[-_][A-Z0-9]{1,8})?|[A-Za-z]{2,10}\\d{1,5}[A-Za-z0-9]{0,6})\\b");
    private static final Set<String> STOP = Set.of(
            "THE", "AND", "FOR", "WITH", "THIS", "THAT", "FROM", "ARE", "NOT", "BUT", "ALL", "ANY",
            "PDF", "FIG", "TABLE", "NOTE", "SEE", "PAGE", "ET", "AL", "II", "III", "IV", "VI", "OF",
            "IN", "ON", "TO", "IS", "IT", "BY", "OR", "AS", "AN", "AT", "BE", "WE", "IF", "NO", "DO");

    /**
     * A chunk reached by the walk.
     *
     * @param bridge true if found by the residual walk (evidence for the rest of the question)
     */
    public record Hit(UUID chunkId, float score, String path, boolean bridge) {
    }

    /** Walk statistics, for evaluation and the status tool. */
    public record WalkStats(int scored, int expanded) {
    }

    /** Immutable snapshot; swapped atomically on rebuild. */
    record Snapshot(Kind[] kind, String[] label, UUID[] ref, Code[] code, int[][] adj, byte[][] rel,
                    float[][] relVec, Map<String, Integer> termNode, int entry,
                    int chunks, int terms, int docs, long edges) {
        int size() {
            return kind.length;
        }
    }

    private final JdbcClient jdbc;
    private final TwoBitCodec codec = new TwoBitCodec(768, 0x5EEDL);
    private volatile Snapshot snap;
    private volatile String status = "not built";
    private volatile WalkStats lastWalk = new WalkStats(0, 0);

    public GraphVecIndex(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Builds on startup, then rebuilds when the chunk count changes (checked every 10 min). */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void startMaintenance() {
        Thread.ofVirtual().name("turbograph").start(() -> {
            long seen = -1;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    long now = jdbc.sql("SELECT count(*) FROM chunk").query(Long.class).single();
                    if (now != seen) {
                        rebuild();
                        seen = now;
                    }
                    Thread.sleep(java.time.Duration.ofMinutes(10));
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    status = "build failed: " + e.getMessage();
                    log.warn("TurboVec knowledge graph build failed", e);
                    try {
                        Thread.sleep(java.time.Duration.ofMinutes(2));
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        });
    }

    public boolean ready() {
        return snap != null;
    }

    public int snapshotSize() {
        Snapshot s = snap;
        return s == null ? 0 : s.size();
    }

    public WalkStats lastWalk() {
        return lastWalk;
    }

    public String status() {
        Snapshot s = snap;
        if (s == null) {
            return status;
        }
        long codeBytes = (long) s.size() * (codec.paddedDim() / 4 + 4);
        long edgeBytes = s.edges() * 5;
        return ("%s: %d nodes (%d chunks, %d terms, %d documents), %d edges; "
                + "2-bit codes %.1f MB + edges %.1f MB").formatted(status, s.size(), s.chunks(), s.terms(),
                s.docs(), s.edges(), codeBytes / 1e6, edgeBytes / 1e6);
    }

    /** Rebuilds the whole graph from the knowledge store. Safe to call while serving. */
    public synchronized void rebuild() {
        long t0 = System.currentTimeMillis();
        status = "building";
        record Row(UUID id, UUID doc, String title, int ordinal, String content, float[] emb) {
        }
        List<Row> rows = new ArrayList<>();
        long after = 0;
        while (true) {
            final long from = after;
            List<Map.Entry<Long, Row>> page = jdbc.sql("""
                    SELECT c.vec_id, c.id, c.document_id, d.title, c.ordinal, c.content, c.embedding::text
                    FROM chunk c JOIN source_document d ON d.id = c.document_id
                    WHERE c.vec_id > ? ORDER BY c.vec_id LIMIT 1000""")
                    .param(from)
                    .query((rs, i) -> Map.entry(rs.getLong(1), new Row(rs.getObject(2, UUID.class),
                            rs.getObject(3, UUID.class), rs.getString(4), rs.getInt(5), rs.getString(6),
                            parse(rs.getString(7)))))
                    .list();
            if (page.isEmpty()) {
                break;
            }
            page.forEach(e -> rows.add(e.getValue()));
            after = page.getLast().getKey();
        }
        rows.sort((a, b) -> a.doc().equals(b.doc()) ? Integer.compare(a.ordinal(), b.ordinal())
                : a.doc().compareTo(b.doc()));
        int nChunks = rows.size();

        // Terms: identifiers in at least 2 chunks that are not ubiquitous.
        List<Set<String>> chunkTerms = new ArrayList<>(nChunks);
        Map<String, Integer> df = new HashMap<>();
        for (Row r : rows) {
            Set<String> ts = terms(r.content());
            chunkTerms.add(ts);
            ts.forEach(t -> df.merge(t, 1, Integer::sum));
        }
        int maxDf = Math.max(3, nChunks / 50);
        List<String> kept = df.entrySet().stream()
                .filter(e -> e.getValue() >= 2 && e.getValue() <= maxDf)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(20_000).map(Map.Entry::getKey).sorted().toList();
        Map<String, Integer> termIdx = new HashMap<>();
        for (String t : kept) {
            termIdx.put(t, termIdx.size());
        }
        Map<UUID, Integer> docIdx = new LinkedHashMap<>();
        Map<UUID, String> docTitle = new HashMap<>();
        for (Row r : rows) {
            docIdx.putIfAbsent(r.doc(), docIdx.size());
            docTitle.putIfAbsent(r.doc(), r.title());
        }
        int nTerms = kept.size();
        int nDocs = docIdx.size();
        int n = nChunks + nTerms + nDocs;
        int D = codec.paddedDim();

        Kind[] kind = new Kind[n];
        String[] label = new String[n];
        UUID[] ref = new UUID[n];
        Code[] code = new Code[n];
        List<List<int[]>> edges = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            edges.add(new ArrayList<>());
        }

        // Knowledge edges; term and document vectors are means of their chunks (rotated space).
        float[][] termSum = new float[nTerms][];
        float[][] docSum = new float[nDocs][];
        for (int i = 0; i < nChunks; i++) {
            Row r = rows.get(i);
            float[] x = codec.rotate(r.emb());
            kind[i] = Kind.CHUNK;
            label[i] = r.title() + " #" + r.ordinal();
            ref[i] = r.id();
            code[i] = codec.encodeRotated(x);
            int d = nChunks + nTerms + docIdx.get(r.doc());
            link(edges, i, d, PART_OF);
            docSum[d - nChunks - nTerms] = add(docSum[d - nChunks - nTerms], x, D);
            if (i > 0 && rows.get(i - 1).doc().equals(r.doc())) {
                link(edges, i - 1, i, NEXT);
            }
            for (String t : chunkTerms.get(i)) {
                Integer ti = termIdx.get(t);
                if (ti != null) {
                    link(edges, i, nChunks + ti, MENTIONS);
                    termSum[ti] = add(termSum[ti], x, D);
                }
            }
        }
        rows.clear();
        Map<String, Integer> termNode = new HashMap<>();
        for (int t = 0; t < nTerms; t++) {
            int id = nChunks + t;
            kind[id] = Kind.TERM;
            label[id] = kept.get(t);
            code[id] = codec.encodeRotated(termSum[t]);
            termNode.put(kept.get(t), id);
        }
        for (var e : docIdx.entrySet()) {
            int id = nChunks + nTerms + e.getValue();
            kind[id] = Kind.DOCUMENT;
            label[id] = docTitle.get(e.getKey());
            ref[id] = e.getKey();
            code[id] = codec.encodeRotated(docSum[e.getValue()]);
        }

        // SIMILAR edges: insert chunks in random order, each found by a 2-bit beam search.
        int[] order = new int[nChunks];
        for (int i = 0; i < nChunks; i++) {
            order[i] = i;
        }
        SplittableRandom rnd = new SplittableRandom(42);
        for (int i = nChunks - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        int[][] sim = new int[nChunks][];
        int[] simLen = new int[nChunks];
        int entry = nChunks == 0 ? -1 : order[0];
        if (nChunks > 0) {
            sim[entry] = new int[M_MAX + 1];
        }
        for (int k = 1; k < nChunks; k++) {
            int v = order[k];
            float[] qv = codec.decodeRotated(code[v]);
            int[] near = beamOverSimilar(qv, entry, code, sim, simLen, EF_BUILD, M);
            sim[v] = new int[M_MAX + 1];
            for (int u : near) {
                sim[v][simLen[v]++] = u;
                sim[u][simLen[u]++] = v;
                if (simLen[u] > M_MAX) {
                    prune(u, code, sim, simLen);
                }
            }
        }
        long edgeCount = 0;
        for (int i = 0; i < nChunks; i++) {
            for (int j = 0; j < simLen[i]; j++) {
                edges.get(i).add(new int[] {sim[i][j], SIMILAR});
            }
        }

        int[][] adj = new int[n][];
        byte[][] rel = new byte[n][];
        for (int i = 0; i < n; i++) {
            List<int[]> es = edges.get(i);
            adj[i] = new int[es.size()];
            rel[i] = new byte[es.size()];
            for (int j = 0; j < es.size(); j++) {
                adj[i][j] = es.get(j)[0];
                rel[i][j] = (byte) es.get(j)[1];
            }
            edgeCount += es.size();
        }

        // TransE relation vectors r = mean(dst - src) over knowledge edges (sampled).
        float[][] relVec = new float[RELS.length][D];
        int[] relCount = new int[RELS.length];
        for (int i = 0; i < nChunks; i += 4) {
            float[] xi = codec.decodeRotated(code[i]);
            for (int j = 0; j < adj[i].length; j++) {
                int r = rel[i][j];
                if (r == SIMILAR || adj[i][j] < i) {
                    continue;
                }
                float[] y = codec.decodeRotated(code[adj[i][j]]);
                for (int k = 0; k < D; k++) {
                    relVec[r][k] += y[k] - xi[k];
                }
                relCount[r]++;
            }
        }
        for (int r = 0; r < RELS.length; r++) {
            if (relCount[r] > 0) {
                for (int k = 0; k < D; k++) {
                    relVec[r][k] /= relCount[r];
                }
            }
        }
        snap = new Snapshot(kind, label, ref, code, adj, rel, relVec, termNode, entry,
                nChunks, nTerms, nDocs, edgeCount);
        status = "built in %d ms".formatted(System.currentTimeMillis() - t0);
        log.info("TurboVec knowledge graph {}", status());
    }

    /** Beam search over SIMILAR edges only (used while building); returns up to {@code m} nearest. */
    private int[] beamOverSimilar(float[] q, int entry, Code[] code, int[][] sim, int[] simLen, int ef, int m) {
        BitSet seen = new BitSet();
        PriorityQueue<float[]> cand = new PriorityQueue<>((a, b) -> Float.compare(b[0], a[0]));
        PriorityQueue<float[]> best = new PriorityQueue<>((a, b) -> Float.compare(a[0], b[0]));
        float s0 = codec.score(q, code[entry]);
        cand.add(new float[] {s0, entry});
        best.add(new float[] {s0, entry});
        seen.set(entry);
        while (!cand.isEmpty()) {
            float[] c = cand.poll();
            if (best.size() >= ef && c[0] < best.peek()[0]) {
                break;
            }
            int u = (int) c[1];
            for (int j = 0; j < simLen[u]; j++) {
                int v = sim[u][j];
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

    /** Keeps the {@code M_MAX} most similar of a node's SIMILAR links. */
    private void prune(int u, Code[] code, int[][] sim, int[] simLen) {
        float[] qu = codec.decodeRotated(code[u]);
        Integer[] idx = new Integer[simLen[u]];
        float[] sc = new float[simLen[u]];
        for (int j = 0; j < simLen[u]; j++) {
            idx[j] = j;
            sc[j] = codec.score(qu, code[sim[u][j]]);
        }
        java.util.Arrays.sort(idx, (a, b) -> Float.compare(sc[b], sc[a]));
        int[] keep = new int[M_MAX + 1];
        for (int j = 0; j < M_MAX; j++) {
            keep[j] = sim[u][idx[j]];
        }
        sim[u] = keep;
        simLen[u] = M_MAX;
    }

    /**
     * Retrieval as neighbour-local expansion over the TurboVec knowledge graph. There
     * is no top-k and no beam: every step scores only the current node's neighbours.
     * <ol>
     * <li>Greedy descent: from each entry (query term nodes, the entry chunk) move to
     * the best neighbour until no neighbour scores higher: a local peak.</li>
     * <li>Threshold expansion: from the peaks, accept every neighbour whose 2-bit score
     * is within {@code delta} of the best score seen, and expand the accepted nodes.</li>
     * <li>Residual expansion: the same from the two best passages with the residual
     * query, accepting the evidence for the rest of the question (bridge hits).</li>
     * </ol>
     * Cost is O(total degree of the accepted nodes); the result size is decided by
     * relevance, not by k.
     */
    public List<Hit> search(String queryText, float[] queryEmbedding, float delta) {
        Snapshot s = snap;
        if (s == null || s.chunks() == 0) {
            return List.of();
        }
        float[] q = codec.rotate(queryEmbedding);
        int[] scored = {0};
        int[] expanded = {0};

        List<Integer> entries = new ArrayList<>();
        for (String tok : queryText.toUpperCase().split("[^A-Z0-9_-]+")) {
            Integer t = s.termNode().get(tok);
            if (t != null) {
                entries.add(t);
            }
        }
        entries.add(s.entry());
        Map<Integer, Float> accepted = new HashMap<>();
        Map<Integer, Integer> parent = new HashMap<>();
        Map<Integer, Integer> parentRel = new HashMap<>();
        expand(s, q, descend(s, q, entries, parent, parentRel, scored, expanded), delta,
                accepted, parent, parentRel, scored, expanded);

        // Residual query: remove what the two best passages already answer.
        List<Integer> top = accepted.entrySet().stream()
                .filter(e -> s.kind()[e.getKey()] == Kind.CHUNK)
                .sorted((a, b) -> Float.compare(b.getValue(), a.getValue()))
                .limit(2).map(Map.Entry::getKey).toList();
        Map<Integer, Float> bridge = new HashMap<>();
        Map<Integer, Integer> bParent = new HashMap<>(parent);
        Map<Integer, Integer> bRel = new HashMap<>(parentRel);
        if (!top.isEmpty()) {
            float[] res = q.clone();
            for (int t : top) {
                float[] nvec = codec.decodeRotated(s.code()[t]);
                double nn = 0;
                double qn = 0;
                for (int k = 0; k < nvec.length; k++) {
                    nn += (double) nvec[k] * nvec[k];
                    qn += (double) res[k] * nvec[k];
                }
                float f = nn > 0 ? (float) (qn / nn) : 0f;
                for (int k = 0; k < res.length; k++) {
                    res[k] -= f * nvec[k];
                }
            }
            expand(s, res, descend(s, res, top, bParent, bRel, scored, expanded), delta,
                    bridge, bParent, bRel, scored, expanded);
        }
        lastWalk = new WalkStats(scored[0], expanded[0]);

        List<Hit> hits = new ArrayList<>();
        accepted.forEach((v, sc) -> {
            if (s.kind()[v] == Kind.CHUNK) {
                hits.add(new Hit(s.ref()[v], sc, path(s, v, parent, parentRel), false));
            }
        });
        bridge.forEach((v, sc) -> {
            if (s.kind()[v] == Kind.CHUNK && !accepted.containsKey(v)) {
                hits.add(new Hit(s.ref()[v], sc, path(s, v, bParent, bRel), true));
            }
        });
        hits.sort((a, b) -> Float.compare(b.score(), a.score()));
        return hits;
    }

    /** Greedy descent from each start to a local peak; returns the distinct peaks. */
    private List<Integer> descend(Snapshot s, float[] q, List<Integer> starts, Map<Integer, Integer> parent,
                                  Map<Integer, Integer> parentRel, int[] scored, int[] expanded) {
        float[][][] shifted = shifted(s, q);
        Set<Integer> peaks = new LinkedHashSet<>();
        for (int start : starts) {
            int u = start;
            float su = codec.score(q, s.code()[u]);
            scored[0]++;
            for (int guard = 0; guard < 64; guard++) {
                expanded[0]++;
                int bestV = -1;
                int bestR = 0;
                float bestS = su;
                int[] nb = s.adj()[u];
                int step = Math.max(1, nb.length / HUB_CAP);
                for (int j = 0; j < nb.length; j += step) {
                    int v = nb[j];
                    int r = s.rel()[u][j];
                    float sv = codec.score(shifted[r][u < v ? 0 : 1], s.code()[v]);
                    scored[0]++;
                    if (sv > bestS) {
                        bestS = sv;
                        bestV = v;
                        bestR = r;
                    }
                }
                if (bestV < 0) {
                    break;
                }
                parent.putIfAbsent(bestV, u);
                parentRel.putIfAbsent(bestV, bestR);
                u = bestV;
                su = bestS;
            }
            peaks.add(u);
        }
        return new ArrayList<>(peaks);
    }

    /**
     * Threshold expansion: accept a neighbour if its score is within {@code delta} of the
     * best score seen so far, then expand it. Only neighbours of accepted nodes are scored.
     */
    private void expand(Snapshot s, float[] q, List<Integer> peaks, float delta, Map<Integer, Float> accepted,
                        Map<Integer, Integer> parent, Map<Integer, Integer> parentRel, int[] scored, int[] expanded) {
        float[][][] shifted = shifted(s, q);
        BitSet seen = new BitSet(s.size());
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        float best = Float.NEGATIVE_INFINITY;
        for (int p : peaks) {
            float sp = codec.score(q, s.code()[p]);
            scored[0]++;
            best = Math.max(best, sp);
            seen.set(p);
            accepted.put(p, sp);
            queue.add(p);
        }
        while (!queue.isEmpty() && accepted.size() < MAX_ACCEPTED) {
            int u = queue.poll();
            if (accepted.get(u) < best - delta) {
                continue;
            }
            expanded[0]++;
            int[] nb = s.adj()[u];
            int step = Math.max(1, nb.length / HUB_CAP);
            for (int j = 0; j < nb.length; j += step) {
                int v = nb[j];
                if (seen.get(v)) {
                    continue;
                }
                seen.set(v);
                int r = s.rel()[u][j];
                float sv = codec.score(shifted[r][u < v ? 0 : 1], s.code()[v]);
                scored[0]++;
                if (sv >= best - delta) {
                    accepted.put(v, sv);
                    parent.putIfAbsent(v, u);
                    parentRel.putIfAbsent(v, r);
                    queue.add(v);
                    best = Math.max(best, sv);
                }
            }
        }
        // Drop nodes accepted before the best score rose past their threshold.
        float cut = best - delta;
        accepted.values().removeIf(sc -> sc < cut);
    }

    /** q - 0.5 r (forward) and q + 0.5 r (backward) per knowledge relation; SIMILAR unshifted. */
    private static float[][][] shifted(Snapshot s, float[] q) {
        float[][][] out = new float[RELS.length][2][];
        for (int r = 0; r < RELS.length; r++) {
            float[] fwd = q.clone();
            float[] bwd = q.clone();
            if (r != SIMILAR) {
                for (int k = 0; k < q.length; k++) {
                    fwd[k] -= 0.5f * s.relVec()[r][k];
                    bwd[k] += 0.5f * s.relVec()[r][k];
                }
            }
            out[r][0] = fwd;
            out[r][1] = bwd;
        }
        return out;
    }

    private static String path(Snapshot s, int v, Map<Integer, Integer> parent, Map<Integer, Integer> rel) {
        List<String> parts = new ArrayList<>();
        parts.add(describe(s, v));
        int cur = v;
        for (int guard = 0; guard < 6 && parent.containsKey(cur); guard++) {
            int p = parent.get(cur);
            int r = rel.get(cur);
            if (r == SIMILAR) {
                parts.add("~");
            } else {
                parts.add(p < cur ? "-" + RELS[r] + "->" : "<-" + RELS[r] + "-");
            }
            parts.add(describe(s, p));
            cur = p;
        }
        java.util.Collections.reverse(parts);
        return String.join(" ", parts);
    }

    static Set<String> terms(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = TERM.matcher(text);
        while (m.find()) {
            String t = m.group().toUpperCase();
            if (!STOP.contains(t) && !t.chars().allMatch(Character::isDigit)) {
                out.add(t);
            }
        }
        return out;
    }

    private static String describe(Snapshot s, int id) {
        return switch (s.kind()[id]) {
            case TERM -> "[" + s.label()[id] + "]";
            case DOCUMENT -> "(" + s.label()[id] + ")";
            case CHUNK -> s.label()[id];
        };
    }

    private static void link(List<List<int[]>> edges, int a, int b, int rel) {
        edges.get(a).add(new int[] {b, rel});
        edges.get(b).add(new int[] {a, rel});
    }

    private static float[] add(float[] acc, float[] x, int d) {
        if (acc == null) {
            acc = new float[d];
        }
        for (int i = 0; i < d; i++) {
            acc[i] += x[i];
        }
        return acc;
    }

    static float[] parse(String literal) {
        String[] parts = literal.substring(1, literal.length() - 1).split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i]);
        }
        return v;
    }
}
