package ai.auralis.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ai.auralis.graph.GraphService;
import ai.auralis.graph.GraphService.NodeView;
import ai.auralis.graph.GraphStore.Node;
import ai.auralis.osint.OsintCatalogImporter;
import ai.auralis.osint.OsintCatalogImporter.ImportResult;

@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final GraphService graph;
    private final OsintCatalogImporter osint;

    public GraphController(GraphService graph, OsintCatalogImporter osint) {
        this.graph = graph;
        this.osint = osint;
    }

    public record ImportRequest(@NotBlank String path) {
    }

    @PostMapping("/import/osint")
    public ResponseEntity<?> importOsint(@Valid @RequestBody ImportRequest req) {
        Path p = Path.of(req.path());
        if (!Files.isRegularFile(p)) {
            return ResponseEntity.badRequest().body("not a file: " + req.path());
        }
        ImportResult r = osint.importFile(p);
        return ResponseEntity.ok(r);
    }

    @GetMapping("/{namespace}/root")
    public ResponseEntity<NodeView> root(@PathVariable String namespace) {
        return graph.root(namespace).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/nodes/{id}")
    public ResponseEntity<NodeView> node(@PathVariable UUID id) {
        return graph.view(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/search")
    public List<Node> search(@RequestParam String q,
                             @RequestParam(required = false) String namespace,
                             @RequestParam(defaultValue = "20") int limit) {
        return graph.find(namespace, q, Math.max(1, Math.min(100, limit)));
    }
}
