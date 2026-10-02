package ai.auralis.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ai.auralis.knowledge.ingest.IngestionService;
import ai.auralis.knowledge.ingest.IngestionService.IngestResult;
import ai.auralis.knowledge.retrieval.Evidence;
import ai.auralis.knowledge.retrieval.HybridRetriever;
import ai.auralis.knowledge.store.KnowledgeStore;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final IngestionService ingestion;
    private final HybridRetriever retriever;
    private final KnowledgeStore store;

    public KnowledgeController(IngestionService ingestion, HybridRetriever retriever,
                               KnowledgeStore store) {
        this.ingestion = ingestion;
        this.retriever = retriever;
        this.store = store;
    }

    public record TextDocument(@NotBlank String title, @NotBlank String text,
                               String sourceType, String uri) {
    }

    public record SearchRequest(@NotBlank String query,
                                @Min(1) @Max(100) Integer topK,
                                Boolean rerank) {
    }

    public record Stats(long chunks) {
    }

    @PostMapping(path = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public IngestResult upload(@RequestParam("file") MultipartFile file,
                               @RequestParam(value = "sourceType", defaultValue = "document") String sourceType) {
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        return ingestion.ingest(file.getResource(), name, sourceType, null);
    }

    @PostMapping(path = "/documents/text", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public IngestResult uploadText(@Valid @RequestBody TextDocument doc) {
        return ingestion.ingestText(doc.text(), doc.title(),
                doc.sourceType() != null ? doc.sourceType() : "document", doc.uri());
    }

    @PostMapping("/search")
    public List<Evidence> search(@Valid @RequestBody SearchRequest req) {
        int topK = req.topK() != null ? req.topK() : 8;
        boolean rerank = req.rerank() == null || req.rerank();
        return retriever.retrieve(req.query(), topK, rerank);
    }

    @GetMapping("/stats")
    public Stats stats() {
        return new Stats(store.countChunks());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest(IllegalArgumentException ex) {
        return ex.getMessage();
    }
}
