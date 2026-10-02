package ai.auralis.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ai.auralis.mcp.CorpusMcpTools;

/** REST face of corpus building; reuses the MCP tool's plain-text status. */
@RestController
@RequestMapping("/api/corpus")
public class CorpusController {

    private final CorpusMcpTools corpus;
    private final ai.auralis.corpus.CorpusBuilder builder;

    public CorpusController(CorpusMcpTools corpus, ai.auralis.corpus.CorpusBuilder builder) {
        this.corpus = corpus;
        this.builder = builder;
    }

    public record BuildRequest(@NotBlank String manifest, Integer topicLimit) {
    }

    @PostMapping("/build")
    public ResponseEntity<String> build(@Valid @RequestBody BuildRequest req) {
        if (!Files.isRegularFile(Path.of(req.manifest()))) {
            return ResponseEntity.badRequest().body("not a file: " + req.manifest());
        }
        return ResponseEntity.accepted().body(corpus.build(req.manifest(), req.topicLimit()));
    }

    @PostMapping("/stop")
    public String stopAll() {
        return builder.stopAll() + " corpus build(s) pausing; they stop at the next item.";
    }

    @GetMapping("/jobs")
    public String jobs() {
        return corpus.status(null);
    }

    @GetMapping("/jobs/{id}")
    public String job(@PathVariable UUID id) {
        return corpus.status(id.toString());
    }
}
