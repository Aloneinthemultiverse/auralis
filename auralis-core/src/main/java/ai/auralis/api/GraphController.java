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
import ai.auralis.library.LibraryImporter;
import ai.auralis.osint.OsintCatalogImporter;
import ai.auralis.osint.OsintCatalogImporter.ImportResult;

@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final GraphService graph;
    private final OsintCatalogImporter osint;
    private final LibraryImporter library;

    public GraphController(GraphService graph, OsintCatalogImporter osint, LibraryImporter library) {
        this.graph = graph;
        this.osint = osint;
        this.library = library;
    }

    public record LibraryRequest(@NotBlank String manifest, String cacheDir) {
    }

    /** Download, ingest and link a library of free sources described by a manifest. */
    @PostMapping("/import/library")
    public ResponseEntity<?> importLibrary(@Valid @RequestBody LibraryRequest req) throws java.io.IOException {
        Path manifest = Path.of(req.manifest());
        if (!Files.isRegularFile(manifest)) {
            return ResponseEntity.badRequest().body("not a file: " + req.manifest());
        }
        Path cache = req.cacheDir() != null ? Path.of(req.cacheDir())
                : manifest.toAbsolutePath().getParent().resolve("cache");
        return ResponseEntity.ok(library.importManifest(manifest, cache));
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
