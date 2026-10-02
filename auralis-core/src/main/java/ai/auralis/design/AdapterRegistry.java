package ai.auralis.design;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import ai.auralis.decision.DecisionService;
import ai.auralis.decision.DecisionService.Policy;
import ai.auralis.decision.DecisionService.Spec;
import ai.auralis.tools.ToolRegistry;

/**
 * Domain adapters: the built-in {@link GeneralAdapter} plus any connected MCP
 * server that exposes a {@code <domain>_guidance} and a {@code <domain>_validate}
 * tool (e.g. auralis-semiconductor). Laya routes each objective to one of them.
 */
@Component
public class AdapterRegistry {

    private static final String GUIDANCE = "_guidance";
    private static final String VALIDATE = "_validate";

    private final GeneralAdapter general;
    private final ToolRegistry tools;
    private final DecisionService decisions;
    private final JsonMapper json;

    public AdapterRegistry(GeneralAdapter general, ToolRegistry tools, DecisionService decisions,
                           JsonMapper json) {
        this.general = general;
        this.tools = tools;
        this.decisions = decisions;
        this.json = json;
    }

    public List<DomainAdapter> all() {
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        tools.all().forEach(t -> byName.put(t.getToolDefinition().name(), t));
        List<DomainAdapter> adapters = new ArrayList<>();
        for (var e : byName.entrySet()) {
            String name = e.getKey();
            if (name.endsWith(VALIDATE)) {
                String domain = name.substring(0, name.length() - VALIDATE.length());
                ToolCallback guidance = byName.get(domain + GUIDANCE);
                if (guidance != null) {
                    adapters.add(new McpDomainAdapter(domain, guidance, e.getValue(), json));
                }
            }
        }
        adapters.add(general);
        return adapters;
    }

    public Optional<DomainAdapter> byName(String name) {
        return all().stream().filter(a -> a.name().equals(name)).findFirst();
    }

    /** Laya picks the adapter for an objective; the LLM decides when Laya is unsure. */
    public DomainAdapter choose(UUID designId, String objective) {
        List<DomainAdapter> adapters = all();
        if (adapters.size() == 1) {
            return adapters.getFirst();
        }
        Map<String, String> options = new LinkedHashMap<>();
        adapters.forEach(a -> options.put(a.name(), a.description()));
        var d = decisions.decide(Spec.choice("design", designId, "domain_routing",
                Policy.LAYA_THEN_LLM, "Which engineering domain does this objective belong to?",
                options, objective));
        return adapters.stream().filter(a -> a.name().equals(d.answer())).findFirst()
                .orElse(general);
    }

    /** An adapter backed by a domain MCP server's guidance/validate tools. */
    static final class McpDomainAdapter implements DomainAdapter {

        private final String name;
        private final ToolCallback guidanceTool;
        private final ToolCallback validateTool;
        private final JsonMapper json;

        McpDomainAdapter(String name, ToolCallback guidanceTool, ToolCallback validateTool,
                         JsonMapper json) {
            this.name = name;
            this.guidanceTool = guidanceTool;
            this.validateTool = validateTool;
            this.json = json;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return guidanceTool.getToolDefinition().description();
        }

        @Override
        public String guidance() {
            return guidanceTool.call("{}");
        }

        @Override
        public String validate(Map<String, String> artifacts) {
            return validateTool.call(json.writeValueAsString(
                    Map.of("filesJson", json.writeValueAsString(artifacts))));
        }
    }
}
