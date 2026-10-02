package ai.auralis.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ai.auralis.research.ResearchService;
import ai.auralis.research.ResearchService.SessionView;
import ai.auralis.research.store.ResearchStore.DeepMode;

/** Starts research sessions (they run in the background) and exposes their full trace. */
@RestController
@RequestMapping("/api/research")
public class ResearchController {

    private final ResearchService research;

    public ResearchController(ResearchService research) {
        this.research = research;
    }

    public record StartRequest(@NotBlank String objective, @Min(1) @Max(10) Integer maxRounds,
                               DeepMode deepResearch) {
    }

    public record Started(UUID id, String status) {
    }

    @PostMapping
    public ResponseEntity<Started> start(@Valid @RequestBody StartRequest req) {
        UUID id = research.start(req.objective(), req.maxRounds(), req.deepResearch());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Started(id, "RUNNING"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SessionView> get(@PathVariable UUID id) {
        return research.view(id).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
