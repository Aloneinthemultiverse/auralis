package ai.auralis.knowledge.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ai.auralis.knowledge.retrieval.Scope;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for evidence sources and their chunks, plus the two raw
 * retrieval queries (dense and lexical) that hybrid retrieval fuses.
 */
@Repository
public class KnowledgeStore {

    private final JdbcClient jdbc;
    private final TurboVecClient turbovec;
    private final boolean useTurbovec;
    private final int candidatesFactor;

    public KnowledgeStore(JdbcClient jdbc, TurboVecClient turbovec,
                          @org.springframework.beans.factory.annotation.Value("${auralis.vector.backend:turbovec}") String backend,
                          @org.springframework.beans.factory.annotation.Value("${auralis.vector.candidates-factor:10}") int candidatesFactor) {
        this.jdbc = jdbc;
        this.turbovec = turbovec;
        this.useTurbovec = "turbovec".equalsIgnoreCase(backend);
        this.candidatesFactor = Math.max(1, candidatesFactor);
    }

    public record NewChunk(String content, float[] embedding) {
    }

    /** A chunk returned by one retriever, with its 1-based rank in that list. */
    public record ChunkHit(UUID chunkId, UUID documentId, String documentTitle,
                           int ordinal, String content, int rank, double rawScore) {
    }

    public Optional<UUID> findDocumentByHash(String contentHash) {
        return jdbc.sql("SELECT id FROM source_document WHERE content_hash = ?")
                .param(contentHash)
                .query(UUID.class)
                .optional();
    }

    @Transactional
    public UUID saveDocument(String title, String sourceType, String uri,
                             String contentHash, List<NewChunk> chunks) {
        UUID docId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO source_document (id, title, source_type, uri, content_hash)
                VALUES (?, ?, ?, ?, ?)""")
                .params(docId, title, sourceType, uri, contentHash)
                .update();
        List<Long> vecIds = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            vecIds.add(jdbc.sql("""
                    INSERT INTO chunk (id, document_id, ordinal, content, embedding)
                    VALUES (?, ?, ?, ?, ?::vector) RETURNING vec_id""")
                    .params(UUID.randomUUID(), docId, i, chunks.get(i).content(),
                            toVectorLiteral(chunks.get(i).embedding()))
                    .query(Long.class).single());
        }
        if (useTurbovec) {
            // Inside the transaction: if the index refuses, the document is not half-stored.
            turbovec.add(vecIds, chunks.stream().map(NewChunk::embedding).toList());
        }
        return docId;
    }

    /** Nearest chunks by cosine distance; rawScore is cosine similarity. */
    public List<ChunkHit> vectorSearch(float[] queryEmbedding, int limit) {
        return vectorSearch(queryEmbedding, limit, Scope.ALL);
    }

    public List<ChunkHit> vectorSearch(float[] queryEmbedding, int limit, Scope scope) {
        return useTurbovec ? turboSearch(queryEmbedding, limit, scope) : pgvectorSearch(queryEmbedding, limit, scope);
    }

    /**
     * TurboVec (2-bit TurboQuant) proposes {@code limit × factor} candidates within the
     * scope; Postgres re-scores exactly those against the full embeddings.
     */
    private List<ChunkHit> turboSearch(float[] queryEmbedding, int limit, Scope scope) {
        List<Long> allow = null;
        if (scope.namespace() != null || !scope.documentIds().isEmpty()) {
            List<Object> p = new ArrayList<>(java.util.Arrays.asList(scope.params()));
            allow = jdbc.sql("SELECT c.vec_id FROM chunk c WHERE true" + scope.sql())
                    .params(p).query(Long.class).list();
            if (allow.isEmpty()) {
                return List.of();
            }
        }
        List<Long> ids = turbovec.search(queryEmbedding, limit * candidatesFactor, allow);
        if (ids.isEmpty()) {
            return List.of();
        }
        String v = toVectorLiteral(queryEmbedding);
        String csv = ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        return jdbc.sql("""
                SELECT c.id, c.document_id, d.title, c.ordinal, c.content,
                       1 - (c.embedding <=> ?::vector) AS score
                FROM chunk c JOIN source_document d ON d.id = c.document_id
                WHERE c.vec_id = ANY(string_to_array(?, ',')::bigint[])
                ORDER BY c.embedding <=> ?::vector
                LIMIT ?""")
                .params(v, csv, v, limit)
                .query(KnowledgeStore::mapRow)
                .list();
    }

    /** Exact nearest chunks over all embeddings (sequential scan): ground truth for evaluation. */
    public List<ChunkHit> exactSearch(float[] queryEmbedding, int limit) {
        return pgvectorSearch(queryEmbedding, limit, Scope.ALL);
    }

    /** The flat TurboVec index alone (2-bit scan + exact re-score). */
    public List<ChunkHit> turbovecSearch(float[] queryEmbedding, int limit) {
        return turboSearch(queryEmbedding, limit, Scope.ALL);
    }

    /** Random chunk texts, used as evaluation queries. */
    public List<String> sampleChunkTexts(int n, double seed) {
        jdbc.sql("SELECT setseed(?)").param(seed).query(Object.class).list();
        return jdbc.sql("SELECT content FROM chunk WHERE length(content) > 200 ORDER BY random() LIMIT ?")
                .param(n).query(String.class).list();
    }

    /** Exact cosine re-score of given chunks within a scope, best first. */
    public List<ChunkHit> exactRescore(float[] queryEmbedding, List<UUID> chunkIds, int limit, Scope scope) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        String v = toVectorLiteral(queryEmbedding);
        String csv = chunkIds.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
        List<Object> params = new ArrayList<>(List.of(v, csv));
        params.addAll(java.util.Arrays.asList(scope.params()));
        params.add(v);
        params.add(limit);
        return jdbc.sql("""
                SELECT c.id, c.document_id, d.title, c.ordinal, c.content,
                       1 - (c.embedding <=> ?::vector) AS score
                FROM chunk c JOIN source_document d ON d.id = c.document_id
                WHERE c.id = ANY(string_to_array(?, ',')::uuid[])""" + scope.sql() + """

                ORDER BY c.embedding <=> ?::vector
                LIMIT ?""")
                .params(params)
                .query(KnowledgeStore::mapRow)
                .list();
    }

    /** One-time / repair: (re)loads every chunk's embedding into TurboVec, calibrating first. */
    public int reindexTurbovec() {
        List<float[]> sample = jdbc.sql("SELECT embedding::text FROM chunk ORDER BY random() LIMIT 4000")
                .query((rs, i) -> parseVector(rs.getString(1))).list();
        if (!sample.isEmpty()) {
            turbovec.calibrate(sample);
        }
        int total = 0;
        long after = 0;
        while (true) {
            var rows = jdbc.sql("SELECT vec_id, embedding::text FROM chunk WHERE vec_id > ? ORDER BY vec_id LIMIT 500")
                    .param(after)
                    .query((rs, i) -> java.util.Map.entry(rs.getLong(1), parseVector(rs.getString(2)))).list();
            if (rows.isEmpty()) {
                return total;
            }
            turbovec.add(rows.stream().map(java.util.Map.Entry::getKey).toList(),
                    rows.stream().map(java.util.Map.Entry::getValue).toList());
            total += rows.size();
            after = rows.getLast().getKey();
        }
    }

    static float[] parseVector(String literal) {
        String[] parts = literal.substring(1, literal.length() - 1).split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i]);
        }
        return v;
    }

    private List<ChunkHit> pgvectorSearch(float[] queryEmbedding, int limit, Scope scope) {
        String v = toVectorLiteral(queryEmbedding);
        List<Object> params = new ArrayList<>(List.of(v));
        params.addAll(java.util.Arrays.asList(scope.params())); // may contain nulls (unscoped)
        params.add(v);
        params.add(limit);
        return jdbc.sql("""
                SELECT c.id, c.document_id, d.title, c.ordinal, c.content,
                       1 - (c.embedding <=> ?::vector) AS score
                FROM chunk c JOIN source_document d ON d.id = c.document_id
                WHERE true""" + scope.sql() + """

                ORDER BY c.embedding <=> ?::vector
                LIMIT ?""")
                .params(params)
                .query(KnowledgeStore::mapRow)
                .list();
    }

    /**
     * Lexical search over the full-text index, catching exact part numbers,
     * identifiers and units that embeddings tend to blur. Query terms are OR-ed
     * (a natural-language question rarely has every word in one chunk) and
     * cover-density ranking rewards chunks matching more terms close together.
     */
    public List<ChunkHit> keywordSearch(String query, int limit) {
        return keywordSearch(query, limit, Scope.ALL);
    }

    /**
     * BM25-style lexical search: each query term is weighted by its inverse document
     * frequency over all chunks, so rare decisive terms (SECDED, a part number, a
     * protocol name) outrank common ones (bit, word, design). Postgres' ts_rank has no
     * IDF on its own; document frequencies come from the GIN index per query.
     */
    public List<ChunkHit> keywordSearch(String query, int limit, Scope scope) {
        List<String> terms = jdbc.sql("SELECT unnest(tsvector_to_array(to_tsvector('english', ?)))")
                .param(query).query(String.class).list();
        if (terms.isEmpty()) {
            return List.of();
        }
        terms = terms.stream().limit(12).toList();
        double n = Math.max(1, countChunks());
        StringBuilder score = new StringBuilder();
        List<Object> scoreParams = new ArrayList<>();
        List<String> quoted = new ArrayList<>();
        for (String t : terms) {
            String q = "'" + t.replace("'", "''") + "'";
            long df = jdbc.sql("SELECT count(*) FROM chunk WHERE tsv @@ to_tsquery('simple', ?)")
                    .param(q).query(Long.class).single();
            double idf = Math.log((n - df + 0.5) / (df + 0.5) + 1.0);
            if (score.length() > 0) {
                score.append(" + ");
            }
            // BM25-like term weight: presence earns the full IDF, repetition only a small,
            // saturating bonus (ts_rank_cd normalised into [0,1)).
            score.append("?::float8 * (CASE WHEN c.tsv @@ to_tsquery('simple', ?) THEN 1 + 0.5 * ts_rank_cd(c.tsv, to_tsquery('simple', ?), 32) ELSE 0 END)");
            scoreParams.add(idf);
            scoreParams.add(q);
            scoreParams.add(q);
            quoted.add(q);
        }
        List<Object> params = new ArrayList<>(scoreParams);
        params.add(String.join(" | ", quoted));
        params.addAll(java.util.Arrays.asList(scope.params())); // may contain nulls (unscoped)
        params.add(limit);
        return jdbc.sql("""
                SELECT c.id, c.document_id, d.title, c.ordinal, c.content, (""" + score + """
                ) AS score
                FROM chunk c JOIN source_document d ON d.id = c.document_id
                WHERE c.tsv @@ to_tsquery('simple', ?)""" + scope.sql() + """

                ORDER BY score DESC
                LIMIT ?""")
                .params(params)
                .query(KnowledgeStore::mapRow)
                .list();
    }

    public long countChunks() {
        return jdbc.sql("SELECT count(*) FROM chunk").query(Long.class).single();
    }

    private static ChunkHit mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ChunkHit(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getInt(4), rs.getString(5), rowNum + 1, rs.getDouble(6));
    }

    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
