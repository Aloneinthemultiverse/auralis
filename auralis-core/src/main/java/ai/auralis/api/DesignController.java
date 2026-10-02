package ai.auralis.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ai.auralis.decision.DecisionLedger;
import ai.auralis.design.DesignService;
import ai.auralis.design.DesignService.DesignView;
import ai.auralis.tools.ToolRegistry;
import ai.auralis.tools.ToolRegistry.ToolInfo;

@RestController
@RequestMapping("/api")
public class DesignController {

    private final DesignService designs;
    private final ToolRegistry tools;
    private final DecisionLedger ledger;

    public DesignController(DesignService designs, ToolRegistry tools, DecisionLedger ledger) {
        this.designs = designs;
        this.tools = tools;
        this.ledger = ledger;
    }

    public record StartDesign(@NotBlank String objective, UUID researchSessionId,
                              @Min(1) @Max(10) Integer maxIterations) {
    }

    public record Branch(@NotNull UUID fromVersionId, @NotBlank String constraintChange,
                         @Min(1) @Max(10) Integer iterations) {
    }

    public record Started(UUID id, String status) {
    }

    @PostMapping("/designs")
    public ResponseEntity<Started> start(@Valid @RequestBody StartDesign req) {
        UUID id = designs.start(req.objective(), req.researchSessionId(),
                req.maxIterations() == null ? 3 : req.maxIterations());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Started(id, "RUNNING"));
    }

    @GetMapping("/designs/{id}")
    public ResponseEntity<DesignView> get(@PathVariable UUID id) {
        return designs.view(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/designs/{id}/branches")
    public ResponseEntity<?> branch(@PathVariable UUID id, @Valid @RequestBody Branch req) {
        return designs.branch(id, req.fromVersionId(), req.constraintChange(),
                        req.iterations() == null ? 2 : req.iterations())
                .<ResponseEntity<?>>map(err -> ResponseEntity.badRequest().body(err))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.ACCEPTED).body(new Started(id, "RUNNING")));
    }

    @GetMapping("/tools")
    public List<ToolInfo> tools() {
        return tools.describe();
    }

    @GetMapping("/decisions")
    public List<DecisionLedger.Entry> decisions(@RequestParam(required = false) String scope,
                                                @RequestParam(required = false) UUID scopeId,
                                                @RequestParam(defaultValue = "50") int limit) {
        return ledger.recent(scope, scopeId, Math.max(1, Math.min(500, limit)));
    }

    @GetMapping("/decisions/agreement")
    public List<DecisionLedger.Agreement> agreement() {
        return ledger.agreement();
    }
}
