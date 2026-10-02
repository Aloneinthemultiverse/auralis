package ai.auralis.graph;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ai.auralis.graph.GraphStore.Link;
import ai.auralis.graph.GraphStore.Node;

/** Navigation over the knowledge graph: a node with its path and neighbours. */
@Service
public class GraphService {

    public record NodeView(Node node, List<Node> path, List<Link> links) {
    }

    private static final int MAX_LINKS = 200;

    private final GraphStore store;

    public GraphService(GraphStore store) {
        this.store = store;
    }

    public Optional<NodeView> view(UUID id) {
        return store.node(id).map(n -> new NodeView(n, store.pathToRoot(id), store.links(id, MAX_LINKS)));
    }

    public Optional<NodeView> root(String namespace) {
        return store.root(namespace).flatMap(n -> view(n.id()));
    }

    public List<Node> find(String namespace, String text, int limit) {
        return store.findByName(namespace, text, limit);
    }
}
