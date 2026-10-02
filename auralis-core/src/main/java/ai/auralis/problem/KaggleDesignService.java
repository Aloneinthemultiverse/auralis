package ai.auralis.problem;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import ai.auralis.config.AuralisProperties;
import ai.auralis.decision.DecisionService;
import ai.auralis.decision.DecisionService.Policy;
import ai.auralis.decision.DecisionService.Spec;
import ai.auralis.design.AdapterRegistry;
import ai.auralis.design.DesignStore;
import ai.auralis.graph.GraphStore;
import ai.auralis.knowledge.retrieval.HybridRetriever;
import ai.auralis.knowledge.retrieval.Scope;
import ai.auralis.problem.ProblemService.BlockRun;
import ai.auralis.problem.ProblemService.ProblemJob;

/**
 * Runs a problem's block-by-block RTL design on a free Kaggle GPU with a large coding
 * model (kaggle/run_design.sh), then imports every block as a design: versions,
 * files and EDA reports, with Laya giving the verdict on each version.
 */
@Service
public class KaggleDesignService {

    private static final Logger log = LoggerFactory.getLogger(KaggleDesignService.class);

    private final ProblemService problems;
    private final HybridRetriever retriever;
    private final GraphStore graph;
    private final AdapterRegistry adapters;
    private final DesignStore designs;
    private final DecisionService decisions;
    private final JsonMapper json;
    private final AuralisProperties.DeepResearch cfg;
    private final ExecutorService runner = Executors.newVirtualThreadPerTaskExecutor();

    public KaggleDesignService(ProblemService problems, HybridRetriever retriever, GraphStore graph,
                               AdapterRegistry adapters, DesignStore designs, DecisionService decisions,
                               JsonMapper json, AuralisProperties props) {
        this.problems = problems;
        this.retriever = retriever;
        this.graph = graph;
        this.adapters = adapters;
        this.designs = designs;
        this.decisions = decisions;
        this.json = json;
        this.cfg = props.deepResearch();
    }

    public ProblemJob start(UUID problemId, int maxBlocks, int iterations) {
        ProblemJob job = problems.job(problemId).orElseThrow(() -> new IllegalArgumentException("no problem " + problemId));
        job.phase = "Kaggle design: building the job";
        runner.submit(() -> run(job, maxBlocks, iterations));
        return job;
    }

    private void run(ProblemJob job, int maxBlocks, int iterations) {
        try {
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("statement", job.statement);
            spec.put("evidence", evidence(job.notebook));
            spec.put("guidance", adapters.byName("semiconductor").map(a -> a.guidance())
                    .orElse("Produce synthesizable SystemVerilog with self-checking testbenches tb_<top>.sv."));
            spec.put("maxBlocks", maxBlocks);
            spec.put("iterations", iterations);
            Path dir = Files.createTempDirectory("auralis-kaggle-design-");
            Path jobFile = dir.resolve("job.json");
            json.writeValue(jobFile.toFile(), spec);

            job.phase = "Kaggle design: running on the GPU (boot ~10-15 min, then block by block)";
            File kaggleDir = new File(cfg.kaggleDir());
            Process p = new ProcessBuilder(cfg.bash(), new File(kaggleDir, "run_design.sh").getAbsolutePath(),
                    jobFile.toString(), dir.resolve("out").toString())
                    .directory(kaggleDir).redirectErrorStream(true)
                    .redirectOutput(dir.resolve("run.log").toFile()).start();
            if (!p.waitFor(8, TimeUnit.HOURS)) {
                p.destroyForcibly();
                job.phase = "Kaggle design timed out";
                return;
            }
            Path result = dir.resolve("out").resolve("design_result.json");
            if (!Files.exists(result)) {
                job.phase = "Kaggle design produced no result — see " + dir.resolve("run.log");
                return;
            }
            importResult(job, json.readTree(result.toFile()));
        } catch (Exception ex) {
            log.error("Kaggle design for problem {} failed", job.id, ex);
            job.phase = "Kaggle design failed: " + ex.getMessage();
        }
    }

    private String evidence(String notebook) {
        Scope scope = Scope.of(notebook, null);
        StringBuilder sb = new StringBuilder();
        for (var part : graph.byKind(notebook, "PART")) {
            sb.append("## ").append(part.name()).append('\n');
            for (var e : retriever.retrieve(part.name(), 4, false, scope)) {
                String t = e.content().length() > 600 ? e.content().substring(0, 600) + " …" : e.content();
                sb.append("- (").append(e.documentTitle()).append(") ").append(t.replace('\n', ' ')).append('\n');
            }
        }
        return sb.toString();
    }

    private void importResult(ProblemJob job, JsonNode res) {
        String model = res.path("model").asString("?");
        job.blocks.clear();
        for (JsonNode b : res.path("blocks")) {
            String name = b.path("name").asString();
            BlockRun run = new BlockRun(name, b.path("spec").asString(""));
            UUID designId = designs.create("Block '" + name + "' of " + (job.title == null ? "the design" : job.title)
                    + "\n\n" + run.spec, null, b.path("versions").size(), job.notebook);
            designs.event(designId, 0, "KAGGLE", "designed on Kaggle GPU with " + model
                    + " in " + b.path("seconds").asInt(0) + " s");
            UUID parent = null;
            boolean passed = false;
            for (JsonNode v : b.path("versions")) {
                Map<String, String> files = json.convertValue(v.path("files"), new TypeReference<Map<String, String>>() { });
                String label = parent == null ? "v1" : "v1." + v.path("iteration").asInt();
                UUID vid = designs.addVersion(designId, parent, label, v.path("iteration").asInt(), name,
                        v.path("spec").asString(""), files, null, null);
                String report = v.path("report").asString("");
                Map<String, String> verdicts = new LinkedHashMap<>();
                verdicts.put("PASS", "all checks pass");
                verdicts.put("FIX", "there are failures to fix");
                var verdict = decisions.decide(Spec.choice("design", designId, "verification_verdict",
                        Policy.LAYA_THEN_LLM, "What is the verdict on this design version?", verdicts,
                        report.length() > 3000 ? report.substring(report.length() - 3000) : report));
                designs.verifyVersion(vid, "== automated (Kaggle EDA) ==\n" + report, verdict.answer());
                designs.event(designId, v.path("iteration").asInt(), "VERDICT", label + ": " + verdict.answer()
                        + " (Laya/LLM) — tools: " + (v.path("passed").asBoolean() ? "OVERALL PASS" : "OVERALL FAIL"));
                passed = v.path("passed").asBoolean();
                parent = vid;
            }
            designs.setStatus(designId, passed ? DesignStore.Status.VERIFIED : DesignStore.Status.EXHAUSTED,
                    b.hasNonNull("error") ? b.path("error").asString() : null);
            run.designId = designId;
            run.status = passed ? "VERIFIED" : "EXHAUSTED";
            job.blocks.add(run);
        }
        long ok = job.blocks.stream().filter(r -> "VERIFIED".equals(r.status)).count();
        job.phase = "Kaggle design imported: " + ok + "/" + job.blocks.size() + " blocks verified (" + model + ")";
    }

    @PreDestroy
    void shutdown() {
        runner.shutdownNow();
    }

    List<BlockRun> blocks(ProblemJob job) {
        return job.blocks;
    }
}
