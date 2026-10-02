package ai.auralis.problem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import ai.auralis.decision.DecisionService;
import ai.auralis.decision.DecisionService.Policy;
import ai.auralis.decision.DecisionService.Spec;
import ai.auralis.design.DesignService;
import ai.auralis.graph.GraphStore;
import ai.auralis.knowledge.retrieval.Evidence;
import ai.auralis.knowledge.retrieval.HybridRetriever;
import ai.auralis.knowledge.retrieval.Scope;
import ai.auralis.notebook.DiscoveryService;
import ai.auralis.notebook.DiscoveryService.Found;

/**
 * Problem → parts → sources per part → design. The LLM decomposes a problem
 * statement into parts with search queries; for each part Laya judges whether
 * existing knowledge already covers it and which discovered sources to keep;
 * the kept books, papers, pages and designs are imported into the problem's
 * notebook under their part, ready for a design run grounded in them.
 */
@Service
public class ProblemService {

    private static final Logger log = LoggerFactory.getLogger(ProblemService.class);
    private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>");

    public record Part(String name, String description, String kind, List<String> queries) {
    }

    public record Breakdown(String title, List<Part> parts) {
    }

    /** Live progress for one part. */
    public static final class PartProgress {
        public final String name;
        public volatile String status = "WAITING";
        public volatile boolean coveredByExisting;
        public volatile Double coverageP;
        public volatile int found;
        public volatile int kept;
        public volatile int imported;
        public volatile int failed;
        public final List<String> sources = Collections.synchronizedList(new ArrayList<>());

        PartProgress(String name) {
            this.name = name;
        }
    }

    public static final class ProblemJob {
        public final UUID id;
        public final String notebook;
        public final String statement;
        public volatile String title;
        public volatile String status = "DECOMPOSING";
        public volatile String phase = "breaking the problem into parts";
        public volatile Breakdown breakdown;
        public final Map<String, PartProgress> parts = Collections.synchronizedMap(new LinkedHashMap<>());
        public volatile UUID designId;

        ProblemJob(UUID id, String notebook, String statement) {
            this.id = id;
            this.notebook = notebook;
            this.statement = statement;
        }
    }

    private final ChatClient chat;
    private final DiscoveryService discovery;
    private final DecisionService decisions;
    private final HybridRetriever retriever;
    private final GraphStore graph;
    private final DesignService designs;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Map<UUID, ProblemJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService runner = Executors.newVirtualThreadPerTaskExecutor();

    public ProblemService(ChatClient.Builder chat, DiscoveryService discovery, DecisionService decisions,
                          HybridRetriever retriever, GraphStore graph, DesignService designs,
                          JdbcClient jdbc, JsonMapper json) {
        this.chat = chat.defaultSystem("""
                You are the chief engineer of Auralis. You break engineering problems into the
                parts that must be understood and designed, so each part can be researched
                separately before anything is designed.""").build();
        this.discovery = discovery;
        this.decisions = decisions;
        this.retriever = retriever;
        this.graph = graph;
        this.designs = designs;
        this.jdbc = jdbc;
        this.json = json;
    }

    public ProblemJob start(String statement, int sourcesPerPart, String mode) {
        String working = statement.length() > 60 ? statement.substring(0, 60) + "…" : statement;
        String notebook = discovery.createNotebook("Problem: " + working);
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO problem (id, statement, notebook, status) VALUES (?, ?, ?, 'DECOMPOSING')")
                .params(id, statement, notebook).update();
        ProblemJob job = new ProblemJob(id, notebook, statement);
        jobs.put(id, job);
        runner.submit(() -> {
            try {
                run(job, Math.max(2, Math.min(25, sourcesPerPart)), mode == null ? "fast" : mode);
            } catch (Exception ex) {
                log.error("problem {} failed", id, ex);
                job.status = "FAILED";
                job.phase = "failed: " + ex.getMessage();
                jdbc.sql("UPDATE problem SET status = 'FAILED', error = ?, updated_at = now() WHERE id = ?")
                        .params(ex.toString(), id).update();
            }
        });
        return job;
    }

    public Optional<ProblemJob> job(UUID id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public List<ProblemJob> jobs() {
        return List.copyOf(jobs.values());
    }

    /** Starts a design run grounded in the problem's notebook, part by part. */
    public UUID design(UUID problemId, int iterations) {
        ProblemJob job = jobs.get(problemId);
        if (job == null) {
            throw new IllegalArgumentException("no problem " + problemId);
        }
        StringBuilder objective = new StringBuilder(job.statement);
        if (job.breakdown != null) {
            objective.append("\n\nBreakdown into parts:\n");
            job.breakdown.parts().forEach(p -> objective.append("- ").append(p.name()).append(": ")
                    .append(p.description()).append('\n'));
        }
        UUID designId = designs.start(objective.toString(), null, iterations, job.notebook);
        job.designId = designId;
        return designId;
    }

    private void run(ProblemJob job, int perPart, String mode) throws Exception {
        Breakdown b = decompose(job.statement);
        job.breakdown = b;
        job.title = b.title();
        b.parts().forEach(p -> job.parts.put(p.name(), new PartProgress(p.name())));
        jdbc.sql("UPDATE problem SET title = ?, breakdown = ?::jsonb, status = 'COLLECTING', updated_at = now() WHERE id = ?")
                .params(b.title(), json.writeValueAsString(b), job.id).update();

        UUID root = discovery.notebookRoot(job.notebook);
        graph.upsertNode(job.notebook, "notebook", "NOTEBOOK", "Problem: " + b.title(), null, null,
                json.writeValueAsString(Map.of("statement", job.statement)));
        job.status = "COLLECTING";

        int i = 0;
        for (Part part : b.parts()) {
            i++;
            PartProgress pp = job.parts.get(part.name());
            job.phase = "part " + i + "/" + b.parts().size() + ": " + part.name();
            UUID partNode = graph.upsertNode(job.notebook, "part:" + part.name(), "PART", part.name(), null, null,
                    json.writeValueAsString(Map.of("description", part.description(), "kind", part.kind(),
                            "queries", part.queries())));
            graph.addEdge(root, partNode, "DECOMPOSES");

            // 1. What does Auralis already know about this part? Laya judges coverage.
            pp.status = "checking existing knowledge";
            List<Evidence> known = retriever.retrieve(part.name() + " " + part.description(), 4, false, Scope.ALL);
            int need = perPart;
            if (!known.isEmpty()) {
                var cover = decisions.decide(Spec.yesNo("problem", job.id, "part_coverage", Policy.LAYA,
                        "Does this existing material cover the topic: " + part.name() + " — " + part.description(),
                        known.stream().map(Evidence::content).reduce("", (a, c) -> a + "\n\n" + c)));
                pp.coverageP = cover.layaProbabilities() == null ? null : cover.layaProbabilities().get("yes");
                pp.coveredByExisting = cover.yes();
                for (Evidence e : known) {
                    UUID ref = graph.upsertNode(job.notebook, "doc:" + e.documentId(), "WORK", e.documentTitle(),
                            null, e.documentId(), "{\"origin\":\"existing knowledge base\"}");
                    graph.addEdge(partNode, ref, "CONTAINS");
                    pp.sources.add("(existing) " + e.documentTitle());
                }
                if (cover.yes()) {
                    need = Math.max(2, perPart / 2);
                }
            }

            // 2. Discover new sources for each of the part's queries; Laya ranks them.
            pp.status = "discovering sources";
            Map<String, Found> pool = new LinkedHashMap<>();
            for (String q : part.queries()) {
                try {
                    for (Found f : discovery.search(q, mode, null)) {
                        pool.putIfAbsent(f.id(), f);
                    }
                } catch (Exception ex) {
                    log.warn("discovery failed for '{}': {}", q, ex.toString());
                }
            }
            pp.found = pool.size();
            // Laya's ranking matters more than its absolute score (specific questions score
            // lower overall), so take the best-ranked sources above a low junk floor, and walk
            // further down the ranking when a download fails so the part still gets `need`.
            List<Found> ranked = pool.values().stream()
                    .filter(f -> f.relevance() != null && f.relevance() >= 0.3)
                    .sorted((x, y) -> Double.compare(y.relevance(), x.relevance()))
                    .toList();
            pp.kept = Math.min(need, ranked.size());

            // 3. Import the best sources under this part until `need` succeed.
            pp.status = "importing";
            for (Found f : ranked) {
                if (pp.imported >= need) {
                    break;
                }
                try {
                    discovery.importOne(job.notebook, partNode, f);
                    pp.imported++;
                    pp.sources.add(f.title());
                } catch (Exception ex) {
                    pp.failed++;
                }
            }
            pp.status = "done";
        }
        job.status = "READY";
        job.phase = "ready to design — every part has its sources";
        jdbc.sql("UPDATE problem SET status = 'READY', updated_at = now() WHERE id = ?").param(job.id).update();
    }

    Breakdown decompose(String statement) {
        Breakdown b = chat.prompt()
                .user(u -> u.text("""
                        Problem statement:
                        {statement}

                        Break this problem into 4 to 8 parts that must each be understood before a
                        design is possible: subsystems/blocks, key techniques, interfaces/standards,
                        constraints (power, area, timing, cost), and verification. For each part give:
                        name (short), description (one sentence: what must be learned or decided),
                        kind (subsystem | technique | interface | constraint | verification), and
                        2-3 search queries that would find textbooks, papers or open-source designs
                        about it. Also give a short title for the whole problem.
                        /no_think""")
                        .param("statement", statement))
                .call()
                .entity(Breakdown.class);
        if (b == null || b.parts() == null || b.parts().isEmpty()) {
            throw new IllegalStateException("the problem could not be decomposed");
        }
        List<Part> clean = b.parts().stream()
                .filter(p -> p.name() != null && !p.name().isBlank())
                .map(p -> new Part(strip(p.name()), strip(p.description()), p.kind() == null ? "technique" : p.kind(),
                        p.queries() == null || p.queries().isEmpty() ? List.of(strip(p.name())) : p.queries().stream().limit(3).toList()))
                .limit(8).toList();
        return new Breakdown(b.title() == null ? statement : strip(b.title()), clean);
    }

    private static String strip(String s) {
        return s == null ? "" : THINK.matcher(s).replaceAll("").strip();
    }

    @PreDestroy
    void shutdown() {
        runner.shutdownNow();
    }
}
