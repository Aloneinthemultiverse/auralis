package ai.auralis.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ai.auralis.notebook.NotebookService;
import ai.auralis.notebook.NotebookService.Answer;
import ai.auralis.notebook.NotebookService.Notebook;
import ai.auralis.notebook.NotebookService.Source;
import ai.auralis.notebook.NotebookService.StudioDoc;

@RestController
@RequestMapping("/api")
public class NotebookController {

    private final NotebookService notebooks;
    private final ai.auralis.notebook.DiscoveryService discovery;
    private final JdbcClient jdbc;
    private final ai.auralis.mcp.StatusMcpTools status;

    public NotebookController(NotebookService notebooks, ai.auralis.notebook.DiscoveryService discovery,
                              JdbcClient jdbc,
                              ai.auralis.mcp.StatusMcpTools status) {
        this.notebooks = notebooks;
        this.discovery = discovery;
        this.jdbc = jdbc;
        this.status = status;
    }

    /** The same stack health report Claude gets from auralis_status. */
    @GetMapping(value = "/status", produces = "text/plain")
    public String status() {
        return status.status();
    }

    public record AskRequest(@NotBlank String question, List<UUID> documentIds) {
    }

    public record StudioRequest(@NotBlank String type, String focus, List<UUID> documentIds) {
    }

    public record RunSummary(UUID id, String kind, String title, String status, String at) {
    }

    public record CreateNotebook(@NotBlank String title) {
    }

    public record DiscoverRequest(@NotBlank String query, String mode) {
    }

    public record ImportRequest(List<ai.auralis.notebook.DiscoveryService.Found> sources) {
    }

    public record JobRef(UUID jobId, String notebook) {
    }

    @PostMapping("/notebooks")
    public JobRef create(@Valid @RequestBody CreateNotebook req) {
        return new JobRef(null, discovery.createNotebook(req.title()));
    }

    /** NotebookLM-style "discover sources": fast (literature + web + designs) or deep (adds a research report). */
    @PostMapping("/notebooks/{id}/discover")
    public JobRef discover(@PathVariable String id, @Valid @RequestBody DiscoverRequest req) {
        return new JobRef(discovery.discover(id, req.query(), req.mode()).id, id);
    }

    @GetMapping("/discover/{jobId}")
    public org.springframework.http.ResponseEntity<ai.auralis.notebook.DiscoveryService.DiscoverJob> discoverJob(
            @PathVariable UUID jobId) {
        return discovery.discoverJob(jobId).map(org.springframework.http.ResponseEntity::ok)
                .orElse(org.springframework.http.ResponseEntity.notFound().build());
    }

    @PostMapping("/notebooks/{id}/import")
    public JobRef importSources(@PathVariable String id, @RequestBody ImportRequest req) {
        return new JobRef(discovery.importSources(id, req.sources()).id, id);
    }

    @GetMapping("/imports/{jobId}")
    public org.springframework.http.ResponseEntity<ai.auralis.notebook.DiscoveryService.ImportJob> importJob(
            @PathVariable UUID jobId) {
        return discovery.importJob(jobId).map(org.springframework.http.ResponseEntity::ok)
                .orElse(org.springframework.http.ResponseEntity.notFound().build());
    }

    @GetMapping("/notebooks")
    public List<Notebook> list() {
        return notebooks.notebooks();
    }

    @GetMapping("/notebooks/{id}/sources")
    public List<Source> sources(@PathVariable String id, @RequestParam(required = false) String q,
                                @RequestParam(defaultValue = "200") int limit,
                                @RequestParam(defaultValue = "0") int offset) {
        return notebooks.sources(id, q, Math.max(1, Math.min(1000, limit)), Math.max(0, offset));
    }

    @PostMapping("/notebooks/{id}/ask")
    public Answer ask(@PathVariable String id, @Valid @RequestBody AskRequest req) {
        return notebooks.ask(id, req.question(), req.documentIds());
    }

    @PostMapping("/notebooks/{id}/studio")
    public StudioDoc studio(@PathVariable String id, @Valid @RequestBody StudioRequest req) {
        return notebooks.studio(id, req.type(), req.focus(), req.documentIds());
    }

    /** Recent research sessions and designs, newest first, for the studio panel. */
    @GetMapping("/runs")
    public List<RunSummary> runs(@RequestParam(defaultValue = "30") int limit) {
        return jdbc.sql("""
                (SELECT id, 'research' AS kind, objective, status, to_char(created_at, 'YYYY-MM-DD HH24:MI') AS at, created_at
                   FROM research_session)
                UNION ALL
                (SELECT id, 'design', objective, status, to_char(created_at, 'YYYY-MM-DD HH24:MI'), created_at
                   FROM design)
                ORDER BY 6 DESC LIMIT ?""")
                .param(Math.max(1, Math.min(200, limit)))
                .query((rs, i) -> new RunSummary(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5)))
                .list();
    }
}
