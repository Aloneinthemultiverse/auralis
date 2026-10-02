package ai.auralis.knowledge.retrieval;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What retrieval may search: everything, one notebook (a graph namespace whose
 * nodes link documents), and/or an explicit set of documents.
 */
public record Scope(String namespace, List<UUID> documentIds) {

    public static final Scope ALL = new Scope(null, List.of());

    public static Scope of(String namespace, List<UUID> documentIds) {
        return new Scope(namespace == null || namespace.isBlank() || namespace.equals("all") ? null : namespace,
                documentIds == null ? List.of() : documentIds);
    }

    /** SQL fragment over chunk alias {@code c}; bind with {@link #params()}. */
    public String sql() {
        return " AND (?::text IS NULL OR c.document_id IN "
                + "(SELECT document_id FROM kg_node WHERE namespace = ? AND document_id IS NOT NULL))"
                + " AND (?::text IS NULL OR c.document_id = ANY(string_to_array(?, ',')::uuid[]))";
    }

    public Object[] params() {
        String ids = documentIds.isEmpty() ? null
                : documentIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        return new Object[] {namespace, namespace, ids, ids};
    }
}
