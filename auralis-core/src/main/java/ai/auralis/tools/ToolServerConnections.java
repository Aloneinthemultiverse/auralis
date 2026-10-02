package ai.auralis.tools;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.auralis.config.AuralisProperties;

/**
 * Lazy, self-healing MCP client connections to the configured tool servers
 * ({@code auralis.tool-servers}). A server that is down is retried after a short
 * back-off, so Auralis starts without its domain servers and picks them up
 * when they come online.
 */
@Component
public class ToolServerConnections {

    private static final Logger log = LoggerFactory.getLogger(ToolServerConnections.class);
    private static final Duration RETRY_AFTER = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private final Map<String, String> servers;
    private final Map<String, McpSyncClient> connected = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastFailure = new ConcurrentHashMap<>();

    public ToolServerConnections(AuralisProperties props) {
        this.servers = props.toolServers() == null ? Map.of() : props.toolServers();
    }

    /** Clients for every reachable server, connecting to new ones on demand. */
    public synchronized List<McpSyncClient> clients() {
        servers.forEach((name, url) -> {
            if (connected.containsKey(name)) {
                return;
            }
            Instant failed = lastFailure.get(name);
            if (failed != null && failed.plus(RETRY_AFTER).isAfter(Instant.now())) {
                return;
            }
            try {
                var transport = HttpClientStreamableHttpTransport.builder(url).endpoint("/mcp").build();
                McpSyncClient client = McpClient.sync(transport).requestTimeout(REQUEST_TIMEOUT).build();
                client.initialize();
                connected.put(name, client);
                lastFailure.remove(name);
                log.info("connected to tool server '{}' at {}", name, url);
            } catch (Exception ex) {
                lastFailure.put(name, Instant.now());
                log.debug("tool server '{}' at {} unavailable: {}", name, url, ex.toString());
            }
        });
        return new ArrayList<>(connected.values());
    }

    /** Drops a client whose calls fail, so the next use reconnects. */
    public synchronized void evict(McpSyncClient client) {
        connected.entrySet().removeIf(e -> {
            if (e.getValue() == client) {
                lastFailure.put(e.getKey(), Instant.now());
                closeQuietly(client);
                return true;
            }
            return false;
        });
    }

    public Map<String, Boolean> status() {
        clients();
        Map<String, Boolean> s = new java.util.LinkedHashMap<>();
        servers.keySet().forEach(n -> s.put(n, connected.containsKey(n)));
        return s;
    }

    @PreDestroy
    void close() {
        connected.values().forEach(ToolServerConnections::closeQuietly);
    }

    private static void closeQuietly(McpSyncClient c) {
        try {
            c.closeGracefully();
        } catch (Exception ignored) {
            // shutting down
        }
    }
}
