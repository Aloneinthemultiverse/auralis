package ai.auralis.graph;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Traversable knowledge graph: typed nodes in namespaces, typed edges, and
 * the navigation queries (children, parents, path to root, neighbourhood).
 */
@Repository
public class GraphStore {

    public record Node(UUID id, String kind, String name, String namespace, String externalId,
                       String uri, UUID documentId, String properties) {
    }

    /** A node seen from another node across one edge. */
    public record Link(String rel, String direction, Node node) {
    }

    private static final String COLS =
            "n.id, n.kind, n.name, n.namespace, n.external_id, n.uri, n.document_id, n.properties::text";

    private final JdbcClient jdbc;

    public GraphStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert or update by (namespace, externalId); returns the node id. */
    public UUID upsertNode(String namespace, String externalId, String kind, String name,
                           String uri, UUID documentId, String propertiesJson) {
        return jdbc.sql("""
                INSERT INTO kg_node (id, kind, name, namespace, external_id, uri, document_id, properties)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (namespace, external_id) DO UPDATE
                SET kind = EXCLUDED.kind, name = EXCLUDED.name, uri = EXCLUDED.uri,
                    document_id = COALESCE(EXCLUDED.document_id, kg_node.document_id),
                    properties = EXCLUDED.properties
                RETURNING id""")
                .params(UUID.randomUUID(), kind, name, namespace, externalId, uri, documentId,
                        propertiesJson == null ? "{}" : propertiesJson)
                .query(UUID.class)
                .single();
    }

    public void linkDocument(UUID nodeId, UUID documentId) {
        jdbc.sql("UPDATE kg_node SET document_id = ? WHERE id = ?").params(documentId, nodeId).update();
    }

    public void addEdge(UUID src, UUID dst, String rel) {
        jdbc.sql("INSERT INTO kg_edge (src, dst, rel) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
                .params(src, dst, rel)
                .update();
    }

    public Optional<Node> node(UUID id) {
        return jdbc.sql("SELECT " + COLS + " FROM kg_node n WHERE n.id = ?")
                .param(id).query(GraphStore::row).optional();
    }

    public Optional<Node> root(String namespace) {
        return jdbc.sql("""
                SELECT %s FROM kg_node n
                WHERE n.namespace = ? AND NOT EXISTS (
                    SELECT 1 FROM kg_edge e WHERE e.dst = n.id AND e.rel = 'CONTAINS')
                ORDER BY n.kind LIMIT 1""".formatted(COLS))
                .param(namespace).query(GraphStore::row).optional();
    }

    /** Outgoing and incoming neighbours of a node. */
    public List<Link> links(UUID id, int limit) {
        return jdbc.sql("""
                SELECT e.rel, 'out', %1$s FROM kg_edge e JOIN kg_node n ON n.id = e.dst WHERE e.src = ?
                UNION ALL
                SELECT e.rel, 'in',  %1$s FROM kg_edge e JOIN kg_node n ON n.id = e.src WHERE e.dst = ?
                ORDER BY 2 DESC, 5
                LIMIT ?""".formatted(COLS))
                .params(id, id, limit)
                .query((rs, n) -> new Link(rs.getString(1), rs.getString(2), mapAt(rs, 2)))
                .list();
    }

    /** Ancestors along CONTAINS edges, root first. */
    public List<Node> pathToRoot(UUID id) {
        return jdbc.sql("""
                WITH RECURSIVE up(id, depth) AS (
                    SELECT ?::uuid, 0
                    UNION ALL
                    SELECT e.src, up.depth + 1 FROM kg_edge e JOIN up ON e.dst = up.id
                    WHERE e.rel = 'CONTAINS' AND up.depth < 32
                )
                SELECT %s FROM up JOIN kg_node n ON n.id = up.id ORDER BY up.depth DESC""".formatted(COLS))
                .param(id).query(GraphStore::row).list();
    }

    /** Name search inside a namespace (or all namespaces when null). */
    public List<Node> findByName(String namespace, String text, int limit) {
        return jdbc.sql("""
                SELECT %s FROM kg_node n
                WHERE (?::text IS NULL OR n.namespace = ?)
                  AND (n.name ILIKE '%%' || ? || '%%'
                       OR to_tsvector('simple', n.name) @@ plainto_tsquery('simple', ?))
                ORDER BY (n.kind = 'CATEGORY') DESC, length(n.name)
                LIMIT ?""".formatted(COLS))
                .params(namespace, namespace, text, text, limit)
                .query(GraphStore::row).list();
    }

    /** All nodes of one kind in a namespace, in creation order of their names. */
    public List<Node> byKind(String namespace, String kind) {
        return jdbc.sql("SELECT " + COLS + " FROM kg_node n WHERE n.namespace = ? AND n.kind = ? ORDER BY n.name")
                .params(namespace, kind).query(GraphStore::row).list();
    }

    public long count(String namespace) {
        return jdbc.sql("SELECT count(*) FROM kg_node WHERE namespace = ?")
                .param(namespace).query(Long.class).single();
    }

    /** RowMapper for queries that select only the node columns. */
    private static Node row(ResultSet rs, int rowNum) throws SQLException {
        return mapAt(rs, 0);
    }

    /** Reads the eight node columns starting after {@code offset} leading columns. */
    private static Node mapAt(ResultSet rs, int offset) throws SQLException {
        return new Node(rs.getObject(offset + 1, UUID.class), rs.getString(offset + 2),
                rs.getString(offset + 3), rs.getString(offset + 4), rs.getString(offset + 5),
                rs.getString(offset + 6), rs.getObject(offset + 7, UUID.class),
                rs.getString(offset + 8));
    }
}
