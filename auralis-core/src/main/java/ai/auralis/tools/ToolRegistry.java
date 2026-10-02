package ai.auralis.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import ai.auralis.decision.DecisionService;

/**
 * Every tool Auralis can hand the LLM while designing: built-in tools plus the
 * tools of any external MCP server configured under
 * {@code auralis.tool-servers} (simulators, CAD/EDA, solvers, ...).
 * Which tools a step actually gets is decided by Laya per step.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private static final java.util.Set<String> BUILT_IN =
            java.util.Set.of("python_compute");

    public record ToolInfo(String name, String description, String origin) {
    }

    private final PythonComputeTool python;
    private final ToolServerConnections servers;
    private final DecisionService decisions;

    public ToolRegistry(PythonComputeTool python, ToolServerConnections servers,
                        DecisionService decisions) {
        this.python = python;
        this.servers = servers;
        this.decisions = decisions;
    }

    /** All tools, resolved now so newly connected MCP servers show up. */
    public List<ToolCallback> all() {
        List<ToolCallback> tools = new ArrayList<>(List.of(ToolCallbacks.from(python)));
        for (McpSyncClient client : servers.clients()) {
            try {
                tools.addAll(SyncMcpToolCallbackProvider.syncToolCallbacks(List.of(client)));
            } catch (Exception ex) {
                log.warn("tool server stopped answering, will reconnect: {}", ex.toString());
                servers.evict(client);
            }
        }
        return tools;
    }

    public List<ToolInfo> describe() {
        return all().stream().map(t -> new ToolInfo(t.getToolDefinition().name(),
                t.getToolDefinition().description(),
                BUILT_IN.contains(t.getToolDefinition().name()) ? "built-in" : "mcp"))
                .toList();
    }

    /** Laya picks the tools relevant to this step; at least one is always kept. */
    public List<ToolCallback> selectFor(String scope, UUID scopeId, String step) {
        List<ToolCallback> all = all();
        Map<String, String> items = new LinkedHashMap<>();
        all.forEach(t -> items.put(t.getToolDefinition().name(), t.getToolDefinition().description()));
        List<String> chosen = decisions.selectSubset(scope, scopeId, "tool_selection",
                "Is this tool useful for the task: " + step,
                items, 0.5, 1);
        return all.stream().filter(t -> chosen.contains(t.getToolDefinition().name())).toList();
    }
}
