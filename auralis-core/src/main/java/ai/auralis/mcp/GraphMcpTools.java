package ai.auralis.mcp;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import ai.auralis.graph.GraphService;
import ai.auralis.graph.GraphService.NodeView;
import ai.auralis.graph.GraphStore.Link;
import ai.auralis.graph.GraphStore.Node;

/** Traversal of the Auralis knowledge graph (catalogs, concepts, books) over MCP. */
@Component
public class GraphMcpTools {

    private static final int MAX_SHOWN = 60;

    private final GraphService graph;

    public GraphMcpTools(GraphService graph) {
        this.graph = graph;
    }

    @McpTool(name = "graph_browse", description = """
            Browse the Auralis knowledge graph one node at a time. Without nodeId, opens the
            root of a namespace (e.g. 'osint' for the OSINT Framework source catalog). Shows
            the node, its path from the root, and its neighbours with their ids so you can
            keep walking. Tool nodes include URL, pricing, opsec and input/output metadata.""")
    public String browse(
            @McpToolParam(description = "Node id to open", required = false) String nodeId,
            @McpToolParam(description = "Namespace whose root to open when no nodeId (default osint)",
                    required = false) String namespace) {
        var view = nodeId == null || nodeId.isBlank()
                ? graph.root(namespace == null ? "osint" : namespace)
                : parse(nodeId).flatMap(graph::view);
        return view.map(GraphMcpTools::render)
                .orElse("No such node. Use graph_find to look nodes up by name.");
    }

    @McpTool(name = "graph_find", description = """
            Find knowledge-graph nodes by name (categories, tools, concepts, books), to start
            browsing from them with graph_browse.""")
    public String find(
            @McpToolParam(description = "Name or words in the name") String text,
            @McpToolParam(description = "Restrict to a namespace, e.g. osint", required = false)
            String namespace) {
        List<Node> nodes = graph.find(namespace, text, 25);
        if (nodes.isEmpty()) {
            return "No nodes named like '" + text + "'.";
        }
        StringBuilder sb = new StringBuilder();
        for (Node n : nodes) {
            sb.append("- ").append(n.kind()).append(" ").append(n.name())
                    .append("  [").append(n.namespace()).append(": ").append(n.externalId())
                    .append("]  id=").append(n.id()).append('\n');
        }
        return sb.toString();
    }

    private static String render(NodeView v) {
        Node n = v.node();
        StringBuilder sb = new StringBuilder()
                .append(n.kind()).append(": ").append(n.name()).append('\n')
                .append("id: ").append(n.id()).append('\n')
                .append("path: ").append(String.join(" > ", v.path().stream().map(Node::name).toList()))
                .append('\n');
        if (n.uri() != null) {
            sb.append("url: ").append(n.uri()).append('\n');
        }
        if (n.properties() != null && !n.properties().equals("{}")) {
            sb.append("properties: ").append(n.properties()).append('\n');
        }
        if (n.properties() != null && n.properties().contains("\"personLookup\": true")) {
            sb.append("note: person-lookup source — catalogued only; use solely with a lawful, "
                    + "authorised purpose.\n");
        }
        List<Link> links = v.links();
        sb.append("\nneighbours (").append(links.size()).append("):\n");
        links.stream().limit(MAX_SHOWN).forEach(l -> sb.append("  ")
                .append("out".equals(l.direction()) ? "→ " : "← ").append(l.rel()).append(' ')
                .append(l.node().kind()).append(' ').append(l.node().name())
                .append("  id=").append(l.node().id()).append('\n'));
        if (links.size() > MAX_SHOWN) {
            sb.append("  … ").append(links.size() - MAX_SHOWN).append(" more\n");
        }
        return sb.toString();
    }

    private static java.util.Optional<UUID> parse(String id) {
        try {
            return java.util.Optional.of(UUID.fromString(id.strip()));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }
}
