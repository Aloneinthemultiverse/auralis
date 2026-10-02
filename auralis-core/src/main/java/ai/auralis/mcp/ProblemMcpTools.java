package ai.auralis.mcp;

import java.util.UUID;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import ai.auralis.problem.ProblemService;
import ai.auralis.problem.ProblemService.ProblemJob;

/** The problem-first workflow over MCP: decompose → collect sources per part → design. */
@Component
public class ProblemMcpTools {

    private final ProblemService problems;
    private final ai.auralis.problem.KaggleDesignService kaggle;

    public ProblemMcpTools(ProblemService problems, ai.auralis.problem.KaggleDesignService kaggle) {
        this.problems = problems;
        this.kaggle = kaggle;
    }

    @McpTool(name = "problem_design_kaggle", description = """
            Design a READY problem block by block on a free Kaggle GPU with a large coding model
            (Qwen2.5-Coder-32B): derives the RTL blocks, then for each loops generate -> Verilator
            lint -> Icarus simulation -> Yosys synthesis -> fix. Results are imported into Auralis
            automatically (designs with versions, files, EDA reports, Laya verdicts). Takes
            ~15 min to boot plus minutes per block; poll problem_status.""")
    public String designOnKaggle(@McpToolParam(description = "Problem id") String problemId,
                                 @McpToolParam(description = "Max RTL blocks 1-10 (default 6)", required = false) Integer maxBlocks,
                                 @McpToolParam(description = "Max fix iterations per block 1-8 (default 4)", required = false) Integer iterations) {
        kaggle.start(UUID.fromString(problemId.strip()), maxBlocks == null ? 6 : maxBlocks, iterations == null ? 4 : iterations);
        return "Kaggle design started for problem " + problemId + ". Poll problem_status.";
    }

    @McpTool(name = "problem_start", description = """
            Start from a problem statement (the recommended first step before any design):
            Auralis breaks it into 4-8 parts (subsystems, techniques, interfaces, constraints,
            verification), Laya checks what existing knowledge already covers each part, then
            discovers and imports books, papers, web pages and open-source designs per part into
            a dedicated notebook. Poll problem_status; when READY, call problem_design.""")
    public String start(
            @McpToolParam(description = "The full problem statement with goals and constraints") String statement,
            @McpToolParam(description = "Sources to import per part, 2-25 (default 8)", required = false)
            Integer sourcesPerPart,
            @McpToolParam(description = "Discovery mode: fast (default), deep (adds local deep research), "
                    + "deep-kaggle", required = false) String mode) {
        ProblemJob job = problems.start(statement, sourcesPerPart == null ? 8 : sourcesPerPart, mode);
        return "Problem started.\nid: " + job.id + "\nnotebook: " + job.notebook
                + "\nPoll problem_status; when READY call problem_design.";
    }

    @McpTool(name = "problem_status", description = "Breakdown and per-part source collection progress of a problem.")
    public String status(@McpToolParam(description = "Problem id") String problemId) {
        return problems.job(UUID.fromString(problemId.strip())).map(ProblemMcpTools::render)
                .orElse("No problem " + problemId);
    }

    @McpTool(name = "problem_design", description = """
            Start a design run for a READY problem, grounded part by part in the sources of the
            problem's notebook. Returns the design id; poll design_status.""")
    public String design(@McpToolParam(description = "Problem id") String problemId,
                         @McpToolParam(description = "Max iterations 1-10 (default 3)", required = false)
                         Integer iterations) {
        UUID id = problems.design(UUID.fromString(problemId.strip()), iterations == null ? 3 : iterations);
        return "Design started.\nid: " + id + "\nPoll design_status.";
    }

    @McpTool(name = "problem_design_blocks", description = """
            For large designs (an SoC): derive the RTL blocks to implement and verify (each with
            ports and a test plan) and design them one by one, grounded in the problem's
            notebook. Returns the block list; follow each block's design with design_status.""")
    public String designBlocks(@McpToolParam(description = "Problem id") String problemId,
                               @McpToolParam(description = "Max iterations per block (default 3)", required = false)
                               Integer iterations) {
        var blocks = problems.designBlocks(UUID.fromString(problemId.strip()), iterations == null ? 3 : iterations);
        StringBuilder sb = new StringBuilder("Designing " + blocks.size() + " blocks in order:\n");
        blocks.forEach(b -> sb.append("- ").append(b.name).append(": ").append(b.spec).append('\n'));
        return sb.append("\nPoll problem_status for each block's design id and status.").toString();
    }

    private static String render(ProblemJob j) {
        StringBuilder sb = new StringBuilder("Problem: ").append(j.title == null ? j.statement : j.title)
                .append("\nStatus: ").append(j.status).append(" — ").append(j.phase)
                .append("\nNotebook: ").append(j.notebook).append('\n');
        if (j.breakdown != null) {
            for (var p : j.breakdown.parts()) {
                var pp = j.parts.get(p.name());
                sb.append("\n• ").append(p.name()).append(" [").append(p.kind()).append("] — ").append(p.description())
                        .append("\n  ").append(pp == null ? "" : pp.status + ": found " + pp.found + ", Laya kept "
                                + pp.kept + ", imported " + pp.imported + (pp.failed > 0 ? ", failed " + pp.failed : "")
                                + (pp.coveredByExisting ? ", already covered by existing knowledge" : ""));
            }
        }
        if (j.designId != null) {
            sb.append("\n\nDesign: ").append(j.designId);
        }
        if (!j.blocks.isEmpty()) {
            sb.append("\n\nBlocks:");
            j.blocks.forEach(b -> sb.append("\n- ").append(b.name).append(" [").append(b.status).append("]")
                    .append(b.designId == null ? "" : " design " + b.designId));
        }
        return sb.toString();
    }
}
