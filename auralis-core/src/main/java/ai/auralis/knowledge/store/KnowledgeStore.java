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

    public KnowledgeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
        for (int i = 0; i < chunks.size(); i++) {
            jdbc.sql("""
                    INSERT INTO chunk (id, document_id, ordinal, content, embedding)
                    VALUES (?, ?, ?, ?, ?::vector)""")
                    .params(UUID.randomUUID(), docId, i, chunks.get(i).content(),
                            toVectorLiteral(chunks.get(i).embedding()))
                    .update();
        }
        return docId;
    }

    /** Nearest chunks by cosine distance; rawScore is cosine similarity. */
    public List<ChunkHit> vectorSearch(float[] queryEmbedding, int limit) {
        return vectorSearch(queryEmbedding, limit, Scope.ALL);
    }

    public List<ChunkHit> vectorSearch(float[] queryEmbedding, int limit, Scope scope) {
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
