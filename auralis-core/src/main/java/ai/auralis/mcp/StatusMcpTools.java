package ai.auralis.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import ai.auralis.config.AuralisProperties;
import ai.auralis.tools.ToolServerConnections;

/** One call that tells Claude (and the user) what is up and how to fix what is not. */
@Component
public class StatusMcpTools {

    private final RestClient http;
    private final JdbcClient jdbc;
    private final ToolServerConnections servers;
    private final String layaUrl;
    private final String ollamaUrl;

    public StatusMcpTools(RestClient.Builder builder, JdbcClient jdbc, ToolServerConnections servers,
                          AuralisProperties props,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${spring.ai.ollama.base-url}") String ollamaUrl) {
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(3));
        f.setReadTimeout(Duration.ofSeconds(5));
        this.http = builder.clone().requestFactory(f).build();
        this.jdbc = jdbc;
        this.servers = servers;
        this.layaUrl = props.laya().baseUrl();
        this.ollamaUrl = ollamaUrl;
    }

    @McpTool(name = "auralis_status", description = """
            Health of the Auralis stack: core, database and knowledge size, Laya, the local
            LLM (Ollama), and connected domain tool servers (e.g. semiconductor EDA). Call it
            first in a session; it says exactly how to fix anything that is down.""")
    public String status() {
        List<String> lines = new ArrayList<>();
        List<String> fixes = new ArrayList<>();
        lines.add("✓ core (this server)");
        try {
            long chunks = jdbc.sql("SELECT count(*) FROM chunk").query(Long.class).single();
            long docs = jdbc.sql("SELECT count(*) FROM source_document").query(Long.class).single();
            lines.add("✓ database: " + docs + " documents, " + chunks + " chunks");
        } catch (Exception e) {
            lines.add("✗ database");
            fixes.add("start Postgres: `docker compose up -d` in the Auralis repo");
        }
        lines.add(check(layaUrl + "/health", "Laya decision model", fixes,
                "start Laya: `scripts/start-auralis.sh` (or vendor/laya: laya-serve)"));
        lines.add(check(ollamaUrl + "/api/tags", "Ollama (main LLM + embeddings)", fixes,
                "start Ollama: `ollama serve`"));
        servers.status().forEach((name, up) -> {
            lines.add((up ? "✓ " : "✗ ") + "tool server '" + name + "'");
            if (!up) {
                fixes.add("start the '" + name + "' tool server: `scripts/start-auralis.sh`");
            }
        });
        StringBuilder sb = new StringBuilder(String.join("\n", lines));
        sb.append(fixes.isEmpty() ? "\n\nAll systems ready." : "\n\nTo fix:\n- " + String.join("\n- ", fixes));
        return sb.toString();
    }

    private String check(String url, String name, List<String> fixes, String fix) {
        try {
            http.get().uri(url).retrieve().toBodilessEntity();
            return "✓ " + name;
        } catch (Exception e) {
            fixes.add(fix);
            return "✗ " + name;
        }
    }
}
