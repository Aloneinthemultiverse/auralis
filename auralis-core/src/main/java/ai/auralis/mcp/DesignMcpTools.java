package ai.auralis.mcp;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import ai.auralis.decision.DecisionLedger;
import ai.auralis.decision.DecisionService;
import ai.auralis.decision.DecisionService.Policy;
import ai.auralis.decision.DecisionService.Spec;
import ai.auralis.design.DesignService;
import ai.auralis.design.DesignService.DesignView;
import ai.auralis.design.DesignStore.Version;
import ai.auralis.tools.ToolRegistry;

/** Design, tools and Laya decisions over MCP. */
@Component
public class DesignMcpTools {

    private static final int MAX_SPEC = 3000;

    private final DesignService designs;
    private final ToolRegistry tools;
    private final DecisionService decisions;
    private final DecisionLedger ledger;

    public DesignMcpTools(DesignService designs, ToolRegistry tools, DecisionService decisions,
                          DecisionLedger ledger) {
        this.designs = designs;
        this.tools = tools;
        this.decisions = decisions;
        this.ledger = ledger;
    }

    @McpTool(name = "design_start", description = """
            Start an Auralis design run on an engineering objective: requirements extraction,
            alternatives, Laya-selected approach, development with Laya-selected tools,
            automated validation by the domain adapter (Laya routes the objective to a domain,
            e.g. semiconductor RTL lint/simulation/synthesis, or a general numerical model),
            and a Laya verdict per version: PASS, FIX, RESEARCH (runs research and retries) or
            ASK_USER. Runs in the background; poll design_status.""")
    public String start(
            @McpToolParam(description = "The engineering objective with its requirements/constraints")
            String objective,
            @McpToolParam(description = "Research session id whose report to design from",
                    required = false) String researchSessionId,
            @McpToolParam(description = "Maximum develop/verify iterations, 1-10 (default 3)",
                    required = false) Integer maxIterations) {
        Optional<UUID> rs = researchSessionId == null || researchSessionId.isBlank()
                ? Optional.empty() : uuid(researchSessionId);
        if (researchSessionId != null && !researchSessionId.isBlank() && rs.isEmpty()) {
            return "Not a research session id: " + researchSessionId;
        }
        int it = maxIterations == null ? 3 : Math.max(1, Math.min(10, maxIterations));
        UUID id = designs.start(objective, rs.orElse(null), it);
        return "Design started.\nid: " + id + "\nmax iterations: " + it
                + "\nPoll design_status with this id.";
    }

    @McpTool(name = "design_status", description = """
            State of an Auralis design: status, trace, alternatives with Laya fit scores, the
            version tree with verdicts, the latest version's design and verification, and the
            decisions taken (with Laya's vote and who decided).""")
    public String status(
            @McpToolParam(description = "Design id") String designId,
            @McpToolParam(description = "Include the full files of the latest version (default false)",
                    required = false) Boolean includeFiles) {
        return uuid(designId).flatMap(designs::view)
                .map(v -> render(v, Boolean.TRUE.equals(includeFiles)))
                .orElse("No design " + designId);
    }

    @McpTool(name = "design_branch", description = """
            What-if exploration: branch a finished design from one of its versions under a
            changed or added constraint (e.g. 'halve the area budget', 'add a cache'). The
            branch develops and verifies new child versions; compare verdicts and metrics in
            design_status.""")
    public String branch(
            @McpToolParam(description = "Design id") String designId,
            @McpToolParam(description = "Version id to branch from") String fromVersionId,
            @McpToolParam(description = "The changed or added constraint") String constraintChange,
            @McpToolParam(description = "Iterations for the branch, 1-10 (default 2)", required = false)
            Integer iterations) {
        var d = uuid(designId);
        var v = uuid(fromVersionId);
        if (d.isEmpty() || v.isEmpty()) {
            return "designId and fromVersionId must be ids";
        }
        int it = iterations == null ? 2 : Math.max(1, Math.min(10, iterations));
        return designs.branch(d.get(), v.get(), constraintChange, it)
                .map(err -> "Not branched: " + err)
                .orElse("Branch started from " + fromVersionId + ". Poll design_status.");
    }

    @McpTool(name = "tools_list", description = """
            List the design tools Auralis can use: built-in tools and every tool of the
            connected domain MCP servers (e.g. EDA tools from auralis-semiconductor).""")
    public String toolsList() {
        StringBuilder sb = new StringBuilder();
        tools.describe().forEach(t -> sb.append("- ").append(t.name()).append(" [")
                .append(t.origin()).append("]: ")
                .append(t.description().replaceAll("\\s+", " ").strip()).append('\n'));
        return sb.toString();
    }

    @McpTool(name = "laya_decide", description = """
            Ask Laya, Auralis' fast decision model, for a typed decision over a piece of text:
            a yes/no question (omit options) or a choice among labelled options. Returns the
            answer with probabilities. Laya is reliable for topic, routing and triage-style
            decisions; it is a signal, not proof — verify high-stakes calls.""")
    public String layaDecide(
            @McpToolParam(description = "The text to decide about") String state,
            @McpToolParam(description = "The decision question") String question,
            @McpToolParam(description = "Choice options as 'label: description' lines; omit for yes/no",
                    required = false) String options) {
        Map<String, String> opts = null;
        if (options != null && !options.isBlank()) {
            opts = new LinkedHashMap<>();
            for (String line : options.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                int c = line.indexOf(':');
                String label = (c > 0 ? line.substring(0, c) : line).strip();
                opts.put(label, c > 0 ? line.substring(c + 1).strip() : label);
            }
        }
        var spec = opts == null
                ? Spec.yesNo("claude", null, "claude_decision", Policy.LAYA, question, state)
                : Spec.choice("claude", null, "claude_decision", Policy.LAYA, question, opts, state);
        var d = decisions.decide(spec);
        return "answer: " + d.answer() + "\ndecided by: " + d.decidedBy()
                + "\nprobabilities: " + d.layaProbabilities();
    }

    @McpTool(name = "decisions_recent", description = """
            The decision ledger: recent control decisions Auralis made (research, design,
            Claude's laya_decide calls), with Laya's vote, the LLM's vote when consulted, and
            the final answer; plus per-kind Laya/LLM agreement.""")
    public String decisionsRecent(
            @McpToolParam(description = "Scope filter: research, design or claude", required = false)
            String scope,
            @McpToolParam(description = "How many (default 20)", required = false) Integer limit) {
        StringBuilder sb = new StringBuilder("Agreement (kind: decisions, compared, Laya agreed):\n");
        ledger.agreement().forEach(a -> sb.append("  ").append(a.kind()).append(": ")
                .append(a.decisions()).append(", ").append(a.compared()).append(", ")
                .append(a.agreed()).append('\n'));
        sb.append("\nRecent:\n");
        ledger.recent(scope, null, limit == null ? 20 : Math.max(1, Math.min(200, limit)))
                .forEach(e -> sb.append("  ").append(e.at()).append(' ').append(e.scope())
                        .append('/').append(e.kind()).append(" → ").append(e.finalAnswer())
                        .append(" by ").append(e.decidedBy())
                        .append(e.layaAnswer() == null ? "" : String.format(Locale.ROOT,
                                " (Laya: %s %.2f)", e.layaAnswer(), e.layaP() == null ? 0 : e.layaP()))
                        .append(e.llmAnswer() == null ? "" : " (LLM: " + e.llmAnswer() + ")")
                        .append('\n'));
        return sb.toString();
    }

    private static String render(DesignView v, boolean files) {
        var d = v.design();
        StringBuilder sb = new StringBuilder("Objective: ").append(d.objective()).append('\n')
                .append("Status: ").append(d.status()).append('\n');
        if (d.error() != null) {
            sb.append("Error: ").append(d.error()).append('\n');
        }
        sb.append("\nTrace:\n");
        v.trace().forEach(e -> sb.append("  ").append(e.at()).append(" i").append(e.iteration())
                .append(' ').append(e.type()).append(": ").append(e.message()).append('\n'));
        sb.append("\nAlternatives:\n");
        v.alternatives().forEach(a -> sb.append(a.selected() ? "  * " : "  - ").append(a.name())
                .append(a.fitScore() == null ? "" : String.format(Locale.ROOT, " (Laya %.2f)", a.fitScore()))
                .append(": ").append(a.summary()).append('\n'));
        sb.append("\nVersions:\n");
        v.versions().forEach(ver -> sb.append("  ").append(ver.label()).append(" id=").append(ver.id())
                .append(" verdict=").append(ver.verdict())
                .append(ver.constraints() == null ? "" : " what-if: " + ver.constraints())
                .append(" files=").append(ver.artifacts().keySet()).append('\n'));
        if (!v.versions().isEmpty()) {
            Version last = v.versions().getLast();
            sb.append("\nLatest version ").append(last.label()).append(":\n")
                    .append(clip(last.spec())).append("\n\nVerification:\n")
                    .append(clip(last.verification() == null ? "(pending)" : last.verification()))
                    .append('\n');
            if (files) {
                last.artifacts().forEach((name, content) -> sb.append("\n--- ").append(name)
                        .append(" ---\n").append(content));
            }
        }
        sb.append("\nDecisions:\n");
        v.decisions().forEach(e -> sb.append("  ").append(e.kind()).append(" → ").append(e.finalAnswer())
                .append(" by ").append(e.decidedBy())
                .append(e.layaAnswer() == null ? "" : " (Laya: " + e.layaAnswer() + ")").append('\n'));
        return sb.toString();
    }

    private static String clip(String s) {
        return s.length() <= MAX_SPEC ? s : s.substring(0, MAX_SPEC) + " …";
    }

    private static Optional<UUID> uuid(String s) {
        try {
            return Optional.of(UUID.fromString(s.strip()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
