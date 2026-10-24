package ai.auralis.knowledge.graphvec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import ai.auralis.knowledge.graphvec.TwoBitCodec.Code;

/**
 * 2-bit graph RAG: a relationship graph whose every node is a TurboQuant-style
 * 2-bit code, so graph search costs the same as compressed vector search.
 * <p>
 * Nodes are chunks, documents and technical terms (acronyms, part numbers such
 * as AXI, CVA6, LPDDR4) found in the chunks. Edges are MENTIONS (chunk-term),
 * PART_OF (chunk-document) and NEXT (chunk-chunk). Each node keeps two codes:
 * its own vector and a neighbourhood vector (mean of its neighbours), so one
 * scan finds both nodes that match the query and nodes whose neighbours do.
 * Each relation has a TransE-style vector r = mean(dst - src); hops are gated
 * by how well the neighbour matches the query once that relation is removed.
 */
@Service
public class GraphVecIndex {

    private static final Logger log = LoggerFactory.getLogger(GraphVecIndex.class);

    public enum Kind { CHUNK, DOCUMENT, TERM }

    static final String[] RELS = {"MENTIONS", "PART_OF", "NEXT"};
    private static final int MENTIONS = 0;
    private static final int PART_OF = 1;
    private static final int NEXT = 2;

    private static final Pattern TERM = Pattern.compile(
            "\\b(?:[A-Z][A-Z0-9]{1,11}(?:[-_][A-Z0-9]{1,8})?|[A-Za-z]{2,10}\\d{1,5}[A-Za-z0-9]{0,6})\\b");
    private static final Set<String> STOP = Set.of(
            "THE", "AND", "FOR", "WITH", "THIS", "THAT", "FROM", "ARE", "NOT", "BUT", "ALL", "ANY",
            "PDF", "FIG", "TABLE", "NOTE", "SEE", "PAGE", "ET", "AL", "II", "III", "IV", "VI", "OF",
            "IN", "ON", "TO", "IS", "IT", "BY", "OR", "AS", "AN", "AT", "BE", "WE", "IF", "NO", "DO");

    /** A chunk reached by graph search, with the relation path that explains it. */
    public record Hit(UUID chunkId, float score, String path) {
    }

    /** Immutable snapshot; swapped atomically on rebuild. */
    record Snapshot(Kind[] kind, String[] label, UUID[] ref, Code[] self, Code[] hood,
                    int[][] adj, byte[][] rel, float[][] relVec, int chunks, int terms, int docs) {
        int size() {
            return kind.length;
        }
    }

    private final JdbcClient jdbc;
    private final TwoBitCodec codec = new TwoBitCodec(768, 0x5EEDL);
    private volatile Snapshot snap;
    private volatile String status = "not built";

    public GraphVecIndex(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Builds on startup, then rebuilds when the chunk count changes (checked every 10 min). */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void startMaintenance() {
        Thread.ofVirtual().name("graphvec").start(() -> {
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
                    log.warn("2-bit graph index build failed", e);
                    try {
                        Thread.sleep(java.time.Duration.ofMinutes(2));
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        });
    }

    public TwoBitCodec codec() {
        return codec;
    }

    public boolean ready() {
        return snap != null;
    }

    public String status() {
        Snapshot s = snap;
        if (s == null) {
            return status;
        }
        long bytes = (long) s.size() * 2 * (codec.paddedDim() / 4 + codec.paddedDim() / 8 + 4);
        return "%s: %d nodes (%d chunks, %d terms, %d documents), ~%.1f MB of 2-bit codes"
                .formatted(status, s.size(), s.chunks(), s.terms(), s.docs(), bytes / 1e6);
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

        // Terms: keep identifiers that occur in at least 2 chunks but are not ubiquitous.
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
        Code[] self = new Code[n];
        List<List<int[]>> edges = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            edges.add(new ArrayList<>());
        }

        // Term and document vectors are means of their chunks, built in the rotated space.
        float[][] termSum = new float[nTerms][];
        float[][] docSum = new float[nDocs][];
        for (int i = 0; i < nChunks; i++) {
            Row r = rows.get(i);
            float[] x = codec.rotate(r.emb());
            kind[i] = Kind.CHUNK;
            label[i] = r.title() + " #" + r.ordinal();
            ref[i] = r.id();
            self[i] = codec.encodeRotated(x);
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
        for (int t = 0; t < nTerms; t++) {
            int id = nChunks + t;
            kind[id] = Kind.TERM;
            label[id] = kept.get(t);
            self[id] = codec.encodeRotated(termSum[t]);
        }
        for (var e : docIdx.entrySet()) {
            int id = nChunks + nTerms + e.getValue();
            kind[id] = Kind.DOCUMENT;
            label[id] = docTitle.get(e.getKey());
            ref[id] = e.getKey();
            self[id] = codec.encodeRotated(docSum[e.getValue()]);
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
        }

        // Neighbourhood codes and TransE relation vectors, from decoded codes.
        float[][] dec = new float[n][];
        for (int i = 0; i < n; i++) {
            dec[i] = codec.decodeRotated(self[i]);
        }
        float[][] relVec = new float[RELS.length][D];
        int[] relCount = new int[RELS.length];
        Code[] hood = new Code[n];
        for (int i = 0; i < n; i++) {
            float[] sum = new float[D];
            int[] nb = adj[i];
            // Hubs (documents, common terms): sample at most 64 neighbours.
            int step = Math.max(1, nb.length / 64);
            for (int j = 0; j < nb.length; j += step) {
                float[] y = dec[nb[j]];
                for (int k = 0; k < D; k++) {
                    sum[k] += y[k];
                }
                if (rel[i][j] >= 0 && i < nb[j]) {
                    float[] rv = relVec[rel[i][j]];
                    for (int k = 0; k < D; k++) {
                        rv[k] += y[k] - dec[i][k];
                    }
                    relCount[rel[i][j]]++;
                }
            }
            hood[i] = nb.length == 0 ? self[i] : codec.encodeRotated(sum);
        }
        for (int r = 0; r < RELS.length; r++) {
            if (relCount[r] > 0) {
                for (int k = 0; k < D; k++) {
                    relVec[r][k] /= relCount[r];
                }
            }
        }
        snap = new Snapshot(kind, label, ref, self, hood, adj, rel, relVec, nChunks, nTerms, nDocs);
        status = "built in %d ms".formatted(System.currentTimeMillis() - t0);
        log.info("2-bit graph index {}", status());
    }

    /**
     * Graph search. One 2-bit scan scores every node on itself and on its
     * neighbourhood; the best seeds are expanded up to {@code hops} along
     * relation-gated edges; scores on the short list get the residual correction.
     */
    public List<Hit> search(float[] queryEmbedding, int limit, int hops) {
        Snapshot s = snap;
        if (s == null || s.size() == 0) {
            return List.of();
        }
        float[] q = codec.rotate(queryEmbedding);
        int seedsN = 24;
        PriorityQueue<float[]> heap = new PriorityQueue<>((a, b) -> Float.compare(a[0], b[0]));
        for (int i = 0; i < s.size(); i++) {
            float sc = 0.7f * codec.score(q, s.self()[i]) + 0.3f * codec.score(q, s.hood()[i]);
            if (heap.size() < seedsN) {
                heap.add(new float[] {sc, i});
            } else if (sc > heap.peek()[0]) {
                heap.poll();
                heap.add(new float[] {sc, i});
            }
        }
        Map<Integer, Float> best = new HashMap<>();
        Map<Integer, String> path = new HashMap<>();
        List<Integer> frontier = new ArrayList<>();
        for (float[] e : heap) {
            int id = (int) e[1];
            best.put(id, e[0]);
            path.put(id, describe(s, id));
            frontier.add(id);
        }
        // q - r (forward edge) and q + r (backward edge), one pair per relation.
        float[][][] shifted = new float[RELS.length][2][];
        for (int r = 0; r < RELS.length; r++) {
            float[] rv = s.relVec()[r];
            float[] fwd = q.clone();
            float[] bwd = q.clone();
            for (int k = 0; k < q.length; k++) {
                fwd[k] -= 0.5f * rv[k];
                bwd[k] += 0.5f * rv[k];
            }
            shifted[r][0] = fwd;
            shifted[r][1] = bwd;
        }
        for (int h = 0; h < hops && !frontier.isEmpty(); h++) {
            List<Integer> next = new ArrayList<>();
            for (int u : frontier) {
                int[] nb = s.adj()[u];
                int step = Math.max(1, nb.length / 48);
                for (int j = 0; j < nb.length; j += step) {
                    int v = nb[j];
                    int r = s.rel()[u][j];
                    // Gate: does the neighbour match the query once the relation offset is removed?
                    float gate = codec.score(shifted[r][u < v ? 0 : 1], s.self()[v]);
                    float sc = 0.55f * best.get(u) + 0.45f * gate;
                    Float prev = best.get(v);
                    if (prev == null || sc > prev) {
                        best.put(v, sc);
                        path.put(v, path.get(u) + " -" + RELS[r] + (u < v ? "-> " : "<- ") + describe(s, v));
                        next.add(v);
                    }
                }
            }
            next.sort((a, b) -> Float.compare(best.get(b), best.get(a)));
            frontier = next.subList(0, Math.min(seedsN, next.size()));
        }
        // Short list: chunks only, residual-corrected.
        List<Hit> hits = new ArrayList<>();
        for (var e : best.entrySet()) {
            int id = e.getKey();
            if (s.kind()[id] == Kind.CHUNK) {
                float corrected = codec.scoreCorrected(q, s.self()[id]);
                hits.add(new Hit(s.ref()[id], 0.5f * e.getValue() + 0.5f * corrected, path.get(id)));
            }
        }
        hits.sort((a, b) -> Float.compare(b.score(), a.score()));
        return hits.subList(0, Math.min(limit, hits.size()));
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
