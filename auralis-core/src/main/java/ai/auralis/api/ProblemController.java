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

    public ProblemController(ProblemService problems) {
        this.problems = problems;
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

    @PostMapping("/{id}/design")
    public Map<String, Object> design(@PathVariable UUID id, @RequestBody(required = false) DesignRequest req) {
        int it = req == null || req.iterations() == null ? 3 : Math.max(1, Math.min(10, req.iterations()));
        return Map.of("designId", problems.design(id, it));
    }
}
