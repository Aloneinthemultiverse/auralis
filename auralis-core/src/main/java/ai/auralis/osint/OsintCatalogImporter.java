package ai.auralis.osint;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import ai.auralis.graph.GraphStore;
import ai.auralis.knowledge.ingest.IngestionService;

/**
 * Imports the OSINT Framework (github.com/lockfale/osint-framework, {@code public/arf.json})
 * as a source catalog: the category tree becomes a traversable graph, and each
 * category's tool list is ingested into the knowledge base so research can find
 * the right free source for a kind of data.
 */
@Service
public class OsintCatalogImporter {

    public static final String NAMESPACE = "osint";

    /**
     * Top-level categories about locating or profiling individuals. They are
     * catalogued, but flagged so Auralis does not suggest them by default.
     */
    static final Set<String> PERSON_CATEGORIES = Set.of(
            "Username", "Email Address", "Social Networks", "Instant Messaging",
            "People Search Engines", "Dating", "Telephone Numbers");

    private static final List<String> METADATA = List.of("description", "status", "pricing",
            "bestFor", "input", "output", "opsec", "opsecNote", "localInstall", "googleDork",
            "registration", "editUrl", "api", "invitationOnly", "deprecated");

    public record ImportResult(int categories, int tools, int documents) {
    }

    private final GraphStore graph;
    private final IngestionService ingestion;
    private final JsonMapper json;

    public OsintCatalogImporter(GraphStore graph, IngestionService ingestion, JsonMapper json) {
        this.graph = graph;
        this.ingestion = ingestion;
        this.json = json;
    }

    public ImportResult importFile(Path arfJson) {
        JsonNode root = json.readTree(arfJson.toFile());
        int[] counts = new int[3];
        UUID rootId = graph.upsertNode(NAMESPACE, "OSINT Framework", "CATALOG",
                "OSINT Framework", "https://osintframework.com", null,
                "{\"source\":\"github.com/lockfale/osint-framework\"}");
        for (JsonNode child : root.path("children")) {
            walk(child, rootId, List.of(), counts);
        }
        return new ImportResult(counts[0], counts[1], counts[2]);
    }

    private void walk(JsonNode n, UUID parentId, List<String> parentPath, int[] counts) {
        String name = n.path("name").asString("").strip();
        List<String> path = append(parentPath, name);
        String key = String.join(" / ", path);
        boolean sensitive = PERSON_CATEGORIES.contains(path.getFirst());

        if (n.hasNonNull("url")) {
            ObjectNode props = json.createObjectNode();
            for (String f : METADATA) {
                if (n.has(f)) {
                    props.set(f, n.get(f));
                }
            }
            props.put("category", String.join(" / ", parentPath));
            props.put("personLookup", sensitive);
            UUID id = graph.upsertNode(NAMESPACE, key, "TOOL", name, n.get("url").asString(),
                    null, json.writeValueAsString(props));
            graph.addEdge(parentId, id, "CONTAINS");
            counts[1]++;
            return;
        }

        UUID id = graph.upsertNode(NAMESPACE, key, "CATEGORY", name, null, null,
                "{\"personLookup\":" + sensitive + "}");
        graph.addEdge(parentId, id, "CONTAINS");
        counts[0]++;

        List<JsonNode> tools = new ArrayList<>();
        for (JsonNode child : n.path("children")) {
            if (child.hasNonNull("url")) {
                tools.add(child);
            }
            walk(child, id, path, counts);
        }
        if (!tools.isEmpty()) {
            var doc = ingestion.ingestText(render(key, tools, sensitive), "OSINT sources: " + key,
                    "osint-catalog", "https://osintframework.com");
            graph.linkDocument(id, doc.documentId());
            if (!doc.alreadyPresent()) {
                counts[2]++;
            }
        }
    }

    /** One paragraph per tool so chunks keep a tool and its metadata together. */
    static String render(String category, List<JsonNode> tools, boolean sensitive) {
        StringBuilder sb = new StringBuilder("OSINT Framework category: ").append(category).append('\n');
        if (sensitive) {
            sb.append("These sources look up individuals; use only with a lawful, authorised purpose.\n");
        }
        for (JsonNode t : tools) {
            Map<String, String> f = new LinkedHashMap<>();
            for (String k : List.of("description", "bestFor", "input", "output", "pricing",
                    "opsec", "status")) {
                if (t.hasNonNull(k) && !t.get(k).asString("").isBlank()) {
                    f.put(k, t.get(k).asString());
                }
            }
            sb.append("\n").append(t.path("name").asString()).append(" — ")
                    .append(t.path("url").asString()).append('\n');
            f.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        }
        return sb.toString();
    }

    private static List<String> append(List<String> path, String name) {
        List<String> out = new ArrayList<>(path);
        out.add(name);
        return out;
    }
}
