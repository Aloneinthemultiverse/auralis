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

    public ProblemMcpTools(ProblemService problems) {
        this.problems = problems;
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
        return sb.toString();
    }
}
