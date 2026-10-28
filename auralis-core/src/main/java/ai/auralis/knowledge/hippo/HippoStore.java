package ai.auralis.knowledge.hippo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for extracted entities and facts. */
@Repository
public class HippoStore {

    /** A fact to save: the entities by name, with the fact and entity embeddings. */
    public record NewFact(String subject, String relation, String object, float[] factEmbedding) {
    }

    /** A stored fact as the index loads it. */
    public record FactRow(long id, UUID chunkId, long subjectId, String relation, long objectId, float[] embedding) {
    }

    public record EntityRow(long id, String name, float[] embedding) {
    }

    private final JdbcClient jdbc;

    public HippoStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Saves a chunk's facts; entities are created once by normalised name. */
    @Transactional
    public void saveFacts(UUID chunkId, List<NewFact> facts, Map<String, float[]> entityEmbeddings) {
        Map<String, Long> ids = new HashMap<>();
        for (var e : entityEmbeddings.entrySet()) {
            ids.put(e.getKey(), upsertEntity(e.getKey(), e.getValue()));
        }
        for (NewFact f : facts) {
            Long s = ids.get(f.subject());
            Long o = ids.get(f.object());
            if (s == null || o == null) {
                continue;
            }
            jdbc.sql("INSERT INTO hippo_fact (chunk_id, subject_id, relation, object_id, embedding) VALUES (?, ?, ?, ?, ?::vector)")
                    .params(chunkId, s, f.relation(), o, literal(f.factEmbedding())).update();
        }
        markDone(chunkId);
    }

    public long upsertEntity(String name, float[] embedding) {
        return jdbc.sql("""
                INSERT INTO hippo_entity (name, embedding) VALUES (?, ?::vector)
                ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name
                RETURNING id""")
                .params(name, literal(embedding)).query(Long.class).single();
    }

    public void insertFact(UUID chunkId, long subjectId, String relation, long objectId, float[] embedding) {
        jdbc.sql("INSERT INTO hippo_fact (chunk_id, subject_id, relation, object_id, embedding) VALUES (?, ?, ?, ?, ?::vector)")
                .params(chunkId, subjectId, relation, objectId, literal(embedding)).update();
    }

    public void markDone(UUID chunkId) {
        jdbc.sql("INSERT INTO hippo_done (chunk_id) VALUES (?) ON CONFLICT DO NOTHING").param(chunkId).update();
    }

    /** Chunks that still need fact extraction, oldest first. */
    public List<Map.Entry<UUID, String>> pending(int limit) {
        return jdbc.sql("""
                SELECT c.id, c.content FROM chunk c
                WHERE NOT EXISTS (SELECT 1 FROM hippo_done d WHERE d.chunk_id = c.id)
                ORDER BY c.vec_id LIMIT ?""")
                .param(limit)
                .query((rs, i) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2))).list();
    }

    public long countFacts() {
        return jdbc.sql("SELECT count(*) FROM hippo_fact").query(Long.class).single();
    }

    public long countEntities() {
        return jdbc.sql("SELECT count(*) FROM hippo_entity").query(Long.class).single();
    }

    public long countDone() {
        return jdbc.sql("SELECT count(*) FROM hippo_done").query(Long.class).single();
    }

    public List<EntityRow> entities() {
        List<EntityRow> out = new ArrayList<>();
        long after = 0;
        while (true) {
            List<EntityRow> page = jdbc.sql("SELECT id, name, embedding::text FROM hippo_entity WHERE id > ? ORDER BY id LIMIT 5000")
                    .param(after)
                    .query((rs, i) -> new EntityRow(rs.getLong(1), rs.getString(2), parse(rs.getString(3)))).list();
            if (page.isEmpty()) {
                return out;
            }
            out.addAll(page);
            after = page.getLast().id();
        }
    }

    public List<FactRow> facts() {
        List<FactRow> out = new ArrayList<>();
        long after = 0;
        while (true) {
            List<FactRow> page = jdbc.sql("""
                    SELECT id, chunk_id, subject_id, relation, object_id, embedding::text
                    FROM hippo_fact WHERE id > ? ORDER BY id LIMIT 5000""")
                    .param(after)
                    .query((rs, i) -> new FactRow(rs.getLong(1), rs.getObject(2, UUID.class), rs.getLong(3),
                            rs.getString(4), rs.getLong(5), parse(rs.getString(6)))).list();
            if (page.isEmpty()) {
                return out;
            }
            out.addAll(page);
            after = page.getLast().id();
        }
    }

    static String literal(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(i == 0 ? "" : ",").append(v[i]);
        }
        return sb.append(']').toString();
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
