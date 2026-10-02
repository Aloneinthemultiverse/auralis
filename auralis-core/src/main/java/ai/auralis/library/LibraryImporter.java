package ai.auralis.library;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import ai.auralis.graph.GraphStore;
import ai.auralis.knowledge.ingest.IngestionService;

/**
 * Builds a domain library from a manifest of legally free sources: downloads
 * each part (cached on disk), ingests it into the knowledge base, and makes it
 * traversable as LIBRARY → CATEGORY → BOOK → PART nodes linked to their
 * documents. Re-running is cheap: downloads are cached and ingestion is
 * idempotent on content.
 */
@Service
public class LibraryImporter {

    private static final Logger log = LoggerFactory.getLogger(LibraryImporter.class);

    public record PartResult(String book, String part, String status, int chunks) {
    }

    public record ImportReport(String namespace, int books, int parts, int newChunks,
                               List<PartResult> results) {
    }

    private final IngestionService ingestion;
    private final GraphStore graph;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public LibraryImporter(IngestionService ingestion, GraphStore graph, JsonMapper json) {
        this.ingestion = ingestion;
        this.graph = graph;
        this.json = json;
    }

    public ImportReport importManifest(Path manifest, Path cacheDir) throws IOException {
        JsonNode root = json.readTree(manifest.toFile());
        String ns = root.path("namespace").asString();
        Files.createDirectories(cacheDir);

        var libId = graph.upsertNode(ns, "library", "LIBRARY", root.path("title").asString(ns),
                null, null, "{}");
        List<PartResult> results = new ArrayList<>();
        int books = 0;
        int newChunks = 0;
        for (JsonNode book : root.path("books")) {
            books++;
            String bookKey = book.path("id").asString();
            String category = book.path("category").asString("uncategorised");
            var catId = graph.upsertNode(ns, "category:" + category, "CATEGORY", category,
                    null, null, "{}");
            graph.addEdge(libId, catId, "CONTAINS");

            ObjectNode props = json.createObjectNode();
            props.put("authors", book.path("authors").asString(""));
            props.put("license", book.path("license").asString(""));
            props.put("sourceType", book.path("sourceType").asString("book"));
            var bookId = graph.upsertNode(ns, "book:" + bookKey, "BOOK",
                    book.path("title").asString(bookKey), null, null, json.writeValueAsString(props));
            graph.addEdge(catId, bookId, "CONTAINS");

            int ordinal = 0;
            for (JsonNode part : book.path("parts")) {
                ordinal++;
                String partTitle = part.path("title").asString("Part " + ordinal);
                String url = part.path("url").asString();
                String title = book.path("title").asString() + " — " + partTitle;
                var partId = graph.upsertNode(ns, "part:" + bookKey + ":" + ordinal, "PART",
                        partTitle, url, null, "{\"ordinal\":" + ordinal + "}");
                graph.addEdge(bookId, partId, "CONTAINS");
                try {
                    Path file = download(url, cacheDir.resolve(bookKey + "-" + ordinal + ".pdf"));
                    var r = ingestion.ingest(new FileSystemResource(file), title,
                            book.path("sourceType").asString("book"), url);
                    graph.linkDocument(partId, r.documentId());
                    newChunks += r.chunks();
                    results.add(new PartResult(bookKey, partTitle,
                            r.alreadyPresent() ? "already present" : "ingested", r.chunks()));
                    log.info("library {}: {} ({} chunks)", ns, title, r.chunks());
                } catch (Exception ex) {
                    results.add(new PartResult(bookKey, partTitle, "FAILED: " + ex.getMessage(), 0));
                    log.warn("library {}: {} failed: {}", ns, title, ex.toString());
                }
            }
        }
        return new ImportReport(ns, books, results.size(), newChunks, results);
    }

    private Path download(String url, Path target) throws IOException, InterruptedException {
        if (Files.exists(target) && Files.size(target) > 0) {
            return target;
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "Auralis-Library/0.1 (open educational sources)")
                .build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        Path tmp = Files.createTempFile(target.getParent(), "dl-", ".part");
        try (InputStream in = resp.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
