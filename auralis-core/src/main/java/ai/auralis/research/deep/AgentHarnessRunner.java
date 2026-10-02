package ai.auralis.research.deep;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import ai.auralis.research.deep.DeepResearch.Finding;
import ai.auralis.research.deep.DeepResearch.Query;

/**
 * Runs an external research command that reads a questions JSON file and
 * writes an answers JSON file (the {@code auralis_research.py} contract).
 */
final class AgentHarnessRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentHarnessRunner.class);

    private final JsonMapper json;

    AgentHarnessRunner(JsonMapper json) {
        this.json = json;
    }

    interface CommandFactory {
        /** Builds the command that turns {@code input} into {@code output}. */
        List<String> command(Path input, Path output);
    }

    List<Finding> run(List<Query> queries, File workDir, CommandFactory factory,
                      Duration timeout, String label) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("auralis-research-");
            Path in = dir.resolve("questions.json");
            Path out = dir.resolve("answers.json");
            Path logFile = dir.resolve("run.log");
            json.writeValue(in.toFile(), queries);

            Process p = new ProcessBuilder(factory.command(in, out))
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
            log.info("{} research started for {} question(s), log {}", label, queries.size(), logFile);
            if (!p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return failAll(queries, label + " research timed out after " + timeout);
            }
            if (!Files.exists(out)) {
                return failAll(queries, label + " research produced no answers (exit "
                        + p.exitValue() + "): " + tail(logFile));
            }
            return json.readValue(out.toFile(), new TypeReference<List<Finding>>() { });
        } catch (IOException ex) {
            return failAll(queries, label + " research failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failAll(queries, label + " research interrupted");
        }
    }

    private static List<Finding> failAll(List<Query> queries, String error) {
        log.warn(error);
        return queries.stream()
                .map(q -> new Finding(q.id(), q.question(), "", List.of(), 0, error))
                .toList();
    }

    private static String tail(Path logFile) {
        try {
            String s = Files.readString(logFile, StandardCharsets.UTF_8);
            return s.length() <= 600 ? s : s.substring(s.length() - 600);
        } catch (IOException e) {
            return "(no log)";
        }
    }
}
