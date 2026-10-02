package ai.auralis.design;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * The main LLM's design reasoning: requirements, alternatives, developing a
 * design (optionally calling tools), and reading verification results. Control
 * decisions (which alternative, pass/fix, which tools) are Laya's, via
 * {@link ai.auralis.decision.DecisionService}.
 */
@Component
public class DesignAnalyst {

    private static final String NO_THINK = "\n/no_think";
    private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>");
    /** A fenced block whose first line names its file: "// file: alu.sv" or "# file: model.py". */
    private static final Pattern FILE_BLOCK = Pattern.compile(
            "(?s)```[\\w+-]*\\s*\\n\\s*(?://|#|--)\\s*file:\\s*([\\w./-]+)\\s*\\n(.*?)```");

    public record Requirements(List<String> functional, List<String> constraints,
                               List<String> metrics, List<String> missing,
                               List<String> assumptions) {

        public String render() {
            return section("Functional", functional) + section("Constraints", constraints)
                    + section("Metrics", metrics) + section("Missing information", missing)
                    + section("Assumptions", assumptions);
        }

        private static String section(String title, List<String> items) {
            if (items == null || items.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder(title).append(":\n");
            items.forEach(i -> sb.append("- ").append(i).append('\n'));
            return sb.append('\n').toString();
        }
    }

    public record AlternativeDraft(String name, String summary, String pros, String cons,
                                   String assumptions) {
    }

    record Alternatives(List<AlternativeDraft> alternatives) {
    }

    /** A developed design: the write-up plus the files extracted from it. */
    public record Developed(String spec, Map<String, String> artifacts) {
    }

    private final ChatClient chat;

    public DesignAnalyst(ChatClient.Builder builder) {
        this.chat = builder.defaultSystem("""
                You are the design engineer of Auralis, an AI engineering scientist. You turn
                requirements and research evidence into concrete, checkable designs. You state
                assumptions explicitly, prefer simple designs that meet the requirements, and
                never claim a result you have not computed or verified.""").build();
    }

    public Requirements requirements(String objective, String context) {
        Requirements r = chat.prompt()
                .user(u -> u.text("""
                        Objective:
                        {objective}

                        Known context and research:
                        {context}

                        Extract the engineering requirements: functional requirements,
                        constraints (performance, power, area, cost, interfaces, technology...),
                        measurable metrics, information that is missing, and the explicit
                        assumptions you will design under where information is missing.""" + NO_THINK)
                        .param("objective", objective)
                        .param("context", context.isBlank() ? "(none)" : context))
                .call()
                .entity(Requirements.class);
        return r != null ? r : new Requirements(List.of(objective), List.of(), List.of(),
                List.of(), List.of());
    }

    public List<AlternativeDraft> alternatives(String objective, String requirements,
                                               String context, String guidance, int n) {
        Alternatives a = chat.prompt()
                .user(u -> u.text("""
                        Objective:
                        {objective}

                        Requirements:
                        {requirements}
                        Research context:
                        {context}

                        Domain guidance:
                        {guidance}

                        Propose {n} genuinely different design alternatives (architectures or
                        approaches). For each give a short name, a summary, pros, cons, and the
                        assumptions it depends on.""" + NO_THINK)
                        .param("objective", objective)
                        .param("requirements", requirements)
                        .param("context", context.isBlank() ? "(none)" : context)
                        .param("guidance", guidance)
                        .param("n", n))
                .call()
                .entity(Alternatives.class);
        return a == null || a.alternatives() == null ? List.of()
                : a.alternatives().stream().filter(x -> x.name() != null && !x.name().isBlank())
                        .limit(n).toList();
    }

    public Developed develop(String objective, String requirements, String context,
                             AlternativeDraft alternative, String constraints, String guidance,
                             String previousSpec, String feedback, List<ToolCallback> tools) {
        String prompt = """
                Objective:
                {objective}

                Requirements:
                {requirements}
                Research context:
                {context}

                Selected approach: {altName}
                {altSummary}

                Additional or changed constraints:
                {constraints}

                Domain guidance:
                {guidance}

                Previous version:
                {previous}

                Verification feedback to address:
                {feedback}

                Produce the next version of the design. Start with a short design description
                (structure, key parameters with values and units, how each requirement is met).
                Then give every file of the design as a fenced code block whose FIRST line is a
                comment naming the file, for example:
                ```systemverilog
                // file: counter.sv
                ...
                ```
                Give complete files, not fragments. Use the available tools to compute values
                rather than guessing them.""" + NO_THINK;
        var spec = chat.prompt()
                .user(u -> u.text(prompt)
                        .param("objective", objective)
                        .param("requirements", requirements)
                        .param("context", context.isBlank() ? "(none)" : context)
                        .param("altName", alternative.name())
                        .param("altSummary", alternative.summary())
                        .param("constraints", constraints == null || constraints.isBlank()
                                ? "(none)" : constraints)
                        .param("guidance", guidance)
                        .param("previous", previousSpec == null ? "(this is the first version)"
                                : previousSpec)
                        .param("feedback", feedback == null ? "(none)" : feedback));
        String text = strip((tools.isEmpty() ? spec : spec.toolCallbacks(tools)).call().content());
        return new Developed(text, extractFiles(text));
    }

    public String review(String objective, String requirements, String spec, String toolReport) {
        return strip(chat.prompt()
                .user(u -> u.text("""
                        Objective:
                        {objective}

                        Requirements:
                        {requirements}
                        Design under review:
                        {spec}

                        Automated verification output:
                        {report}

                        Review the design strictly against the requirements and the verification
                        output. List: (1) what passed, (2) every failure or requirement not met,
                        with the likely cause, (3) what to change. If a failure needs knowledge
                        the design lacks, say which question must be researched. End with one
                        line: RECOMMENDATION: PASS, FIX, RESEARCH or ASK_USER.""" + NO_THINK)
                        .param("objective", objective)
                        .param("requirements", requirements)
                        .param("spec", spec)
                        .param("report", toolReport))
                .call()
                .content());
    }

    public String researchQuestion(String objective, String review) {
        return strip(chat.prompt()
                .user(u -> u.text("""
                        Objective: {objective}

                        Design review:
                        {review}

                        Write the single most important research question whose answer would
                        fix the failures in this review. Reply with the question only.""" + NO_THINK)
                        .param("objective", objective)
                        .param("review", review))
                .call()
                .content());
    }

    static Map<String, String> extractFiles(String text) {
        Map<String, String> files = new LinkedHashMap<>();
        Matcher m = FILE_BLOCK.matcher(text);
        while (m.find()) {
            files.put(m.group(1).strip(), m.group(2).strip() + "\n");
        }
        return files;
    }

    static String strip(String text) {
        return text == null ? "" : THINK.matcher(text).replaceAll("").strip();
    }
}
