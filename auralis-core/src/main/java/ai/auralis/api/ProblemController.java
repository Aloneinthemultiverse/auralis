package ai.auralis.api;

import java.util.List;
import java.util.Map;
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

import ai.auralis.problem.ProblemService;
import ai.auralis.problem.ProblemService.ProblemJob;

/** Problem statement → parts → sources per part → design. */
@RestController
@RequestMapping("/api/problems")
public class ProblemController {

    private final ProblemService problems;
    private final ai.auralis.problem.KaggleDesignService kaggle;

    public ProblemController(ProblemService problems, ai.auralis.problem.KaggleDesignService kaggle) {
        this.problems = problems;
        this.kaggle = kaggle;
    }

    public record KaggleRequest(Integer maxBlocks, Integer iterations) {
    }

    /** Block-by-block RTL design on a free Kaggle GPU with a large model; imported automatically. */
    @PostMapping("/{id}/design-kaggle")
    public Map<String, Object> designOnKaggle(@PathVariable UUID id, @RequestBody(required = false) KaggleRequest req) {
        int blocks = req == null || req.maxBlocks() == null ? 6 : Math.max(1, Math.min(10, req.maxBlocks()));
        int it = req == null || req.iterations() == null ? 4 : Math.max(1, Math.min(8, req.iterations()));
        return Map.of("problem", kaggle.start(id, blocks, it).id, "blocks", blocks, "iterations", it);
    }

    public record StartRequest(@NotBlank String statement, Integer sourcesPerPart, String mode) {
    }

    public record DesignRequest(Integer iterations) {
    }

    @PostMapping
    public Map<String, Object> start(@Valid @RequestBody StartRequest req) {
        ProblemJob job = problems.start(req.statement(),
                req.sourcesPerPart() == null ? 8 : req.sourcesPerPart(), req.mode());
        return Map.of("id", job.id, "notebook", job.notebook);
    }

    @GetMapping
    public List<ProblemJob> list() {
        return problems.jobs();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProblemJob> get(@PathVariable UUID id) {
        return problems.job(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** Large designs: derive the RTL blocks and design them one by one. */
    @PostMapping("/{id}/design-blocks")
    public List<ProblemService.BlockRun> designBlocks(@PathVariable UUID id,
                                                      @RequestBody(required = false) DesignRequest req) {
        int it = req == null || req.iterations() == null ? 3 : Math.max(1, Math.min(10, req.iterations()));
        return problems.designBlocks(id, it);
    }

    @PostMapping("/{id}/design")
    public Map<String, Object> design(@PathVariable UUID id, @RequestBody(required = false) DesignRequest req) {
        int it = req == null || req.iterations() == null ? 3 : Math.max(1, Math.min(10, req.iterations()));
        return Map.of("designId", problems.design(id, it));
    }
}
