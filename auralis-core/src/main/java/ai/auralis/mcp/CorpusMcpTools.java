package ai.auralis.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import ai.auralis.corpus.CorpusBuilder;
import ai.auralis.corpus.CorpusBuilder.Job;

/** Corpus building over MCP: turn a topic manifest into a large, traversable knowledge base. */
@Component
public class CorpusMcpTools {

    private final CorpusBuilder builder;

    public CorpusMcpTools(CorpusBuilder builder) {
        this.builder = builder;
    }

    @McpTool(name = "corpus_build", description = """
            Build or grow a domain research corpus from a manifest (JSON with namespace, domain,
            relevanceQuestion, sources, topics): discovers papers on arXiv and OpenAlex,
            Laya decides relevance for every candidate, thin topics are escalated to deep
            web research restricted to open hosts, open-access PDFs are downloaded and ingested
            into the knowledge base, and a TOPIC → WORK graph with citation links is built.
            Runs in the background for a long time; poll corpus_status.""")
    public String build(
            @McpToolParam(description = "Absolute path of the corpus manifest JSON") String manifest,
            @McpToolParam(description = "Only the first N topics (for a quick trial)", required = false)
            Integer topicLimit) {
        Path m = Path.of(manifest);
        if (!Files.isRegularFile(m)) {
            return "Not a file: " + manifest;
        }
        Job job = builder.start(m, m.toAbsolutePath().getParent().resolve("cache"), topicLimit);
        return "Corpus build started.\njob: " + job.id + "\nnamespace: " + job.namespace
                + "\nPoll corpus_status; browse with graph_browse namespace " + job.namespace + ".";
    }

    @McpTool(name = "corpus_status", description = "Progress of corpus builds (all, or one job).")
    public String status(@McpToolParam(description = "Job id (default: all jobs)", required = false)
                         String jobId) {
        List<Job> jobs = jobId == null || jobId.isBlank() ? builder.jobs()
                : builder.job(UUID.fromString(jobId.strip())).map(List::of).orElse(List.of());
        if (jobs.isEmpty()) {
            return "No corpus builds.";
        }
        StringBuilder sb = new StringBuilder();
        for (Job j : jobs) {
            sb.append("job ").append(j.id).append(" [").append(j.namespace).append("] ")
                    .append(j.status).append(", ")
                    .append(Duration.between(j.started, Instant.now()).toMinutes()).append(" min\n")
                    .append("  phase: ").append(j.phase).append('\n')
                    .append("  discovered ").append(j.discovered).append(", Laya kept ").append(j.kept)
                    .append(", ingested ").append(j.ingested).append(" (").append(j.chunks)
                    .append(" chunks), skipped ").append(j.failed).append(", citation links ")
                    .append(j.citationEdges).append('\n');
            List<String> log = List.copyOf(j.log);
            log.subList(Math.max(0, log.size() - 12), log.size())
                    .forEach(l -> sb.append("  ").append(l).append('\n'));
        }
        return sb.toString();
    }
}
