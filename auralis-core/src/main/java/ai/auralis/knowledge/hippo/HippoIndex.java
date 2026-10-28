package ai.auralis.knowledge.hippo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import ai.auralis.knowledge.graphvec.TwoBitCodec;

/**
 * Holds the TurboVec-HippoRAG graph built from the knowledge store (chunks) and the
 * extracted facts, rebuilding it when the number of facts or chunks changes.
 */
@Service
public class HippoIndex {

    private static final Logger log = LoggerFactory.getLogger(HippoIndex.class);

    /** Chunks best first, with the facts that seeded the search. */
    public record Hits(List<UUID> chunkIds, List<String> facts) {
    }

    private record Snapshot(HippoGraph graph, UUID[] chunkIds, long facts, long chunks) {
    }

    private final JdbcClient jdbc;
    private final HippoStore store;
    private final TwoBitCodec codec = new TwoBitCodec(768, 0x5EEDL);
    private volatile Snapshot snap;
    private volatile String status = "not built (no facts yet)";

    public HippoIndex(JdbcClient jdbc, HippoStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    public boolean ready() {
        return snap != null && snap.graph().facts() > 0;
    }

    public String status() {
        Snapshot s = snap;
        String counts = "facts in store %d, entities %d, chunks processed %d"
                .formatted(store.countFacts(), store.countEntities(), store.countDone());
        if (s == null) {
            return status + "; " + counts;
        }
        HippoGraph g = s.graph();
        return "%s: %d chunks, %d entities, %d facts, %d exact edges, %.1f MB of 2-bit codes + edges; %s"
                .formatted(status, g.chunks(), g.entities(), g.facts(), g.edges(), g.bytes() / 1e6, counts);
    }

    /** Builds on startup and rebuilds every 10 minutes if facts or chunks changed. */
    @EventListener(ApplicationReadyEvent.class)
    public void startMaintenance() {
        Thread.ofVirtual().name("hippo-index").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    long facts = store.countFacts();
                    long chunks = jdbc.sql("SELECT count(*) FROM chunk").query(Long.class).single();
                    Snapshot s = snap;
                    if (facts > 0 && (s == null || s.facts() != facts || s.chunks() != chunks)) {
                        rebuild();
                    }
                    Thread.sleep(java.time.Duration.ofMinutes(10));
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    status = "build failed: " + e.getMessage();
                    log.warn("TurboVec-HippoRAG build failed", e);
                    try {
                        Thread.sleep(java.time.Duration.ofMinutes(2));
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        });
    }

    public synchronized void rebuild() {
        long t0 = System.currentTimeMillis();
        status = "building";
        List<UUID> ids = new ArrayList<>();
        List<float[]> chunkEmb = new ArrayList<>();
        long after = 0;
        while (true) {
            final long from = after;
            var page = jdbc.sql("SELECT vec_id, id, embedding::text FROM chunk WHERE vec_id > ? ORDER BY vec_id LIMIT 2000")
                    .param(from)
                    .query((rs, i) -> new Object[] {rs.getLong(1), rs.getObject(2, UUID.class), HippoStore.parse(rs.getString(3))})
                    .list();
            if (page.isEmpty()) {
                break;
            }
            for (Object[] r : page) {
                ids.add((UUID) r[1]);
                chunkEmb.add((float[]) r[2]);
            }
            after = (Long) page.getLast()[0];
        }
        Map<UUID, Integer> chunkIdx = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            chunkIdx.put(ids.get(i), i);
        }
        List<HippoStore.EntityRow> ents = store.entities();
        Map<Long, Integer> entIdx = new HashMap<>();
        Map<Long, String> entName = new HashMap<>();
        List<float[]> entEmb = new ArrayList<>(ents.size());
        for (HippoStore.EntityRow r : ents) {
            entIdx.put(r.id(), entEmb.size());
            entName.put(r.id(), r.name());
            entEmb.add(r.embedding());
        }
        List<HippoGraph.Fact> facts = new ArrayList<>();
        for (HippoStore.FactRow r : store.facts()) {
            Integer ch = chunkIdx.get(r.chunkId());
            Integer s = entIdx.get(r.subjectId());
            Integer o = entIdx.get(r.objectId());
            if (ch != null && s != null && o != null) {
                facts.add(new HippoGraph.Fact(ch, s, o,
                        entName.get(r.subjectId()) + " —" + r.relation() + "→ " + entName.get(r.objectId()),
                        r.embedding()));
            }
        }
        HippoGraph g = HippoGraph.build(codec, chunkEmb, entEmb, facts);
        snap = new Snapshot(g, ids.toArray(UUID[]::new), store.countFacts(), ids.size());
        status = "built in %d ms".formatted(System.currentTimeMillis() - t0);
        log.info("TurboVec-HippoRAG {}", status());
    }

    public Hits search(float[] queryEmbedding, int limit) {
        Snapshot s = snap;
        if (s == null) {
            return new Hits(List.of(), List.of());
        }
        HippoGraph.Result r = s.graph().search(queryEmbedding, limit);
        List<UUID> out = new ArrayList<>(r.chunks().length);
        for (int i : r.chunks()) {
            out.add(s.chunkIds()[i]);
        }
        return new Hits(out, r.facts());
    }
}
