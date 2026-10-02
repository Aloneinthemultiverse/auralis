package ai.auralis.tools;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Built-in free design tool: runs Python in the throwaway {@code auralis-pysandbox}
 * container (numpy, scipy, pandas, sympy, matplotlib; no network, 1 CPU, 1 GB).
 * Lets the LLM calculate, size, sweep parameters and simulate instead of guessing.
 */
@Component
public class PythonComputeTool {

    private static final int TIMEOUT_S = 120;
    private static final int MAX_OUTPUT = 8000;

    @Tool(name = "python_compute", description = """
            Run a short Python 3 program and return its stdout. numpy, scipy, pandas, sympy
            and matplotlib are installed; there is no network or file access outside the
            run. Use it for engineering calculations, unit conversions, sizing, parameter
            sweeps, optimisation and small simulations. Always print() the results.""")
    public String compute(@ToolParam(description = "Complete Python program; print the results")
                          String code) {
        try {
            Process p = new ProcessBuilder(List.of("docker", "run", "--rm", "-i",
                    "--network", "none", "--memory", "1g", "--cpus", "1", "--pids-limit", "128",
                    "auralis-pysandbox", "python", "-"))
                    .redirectErrorStream(true)
                    .start();
            try (var in = p.getOutputStream()) {
                in.write(code.getBytes(StandardCharsets.UTF_8));
            }
            if (!p.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "Error: timed out after " + TIMEOUT_S + "s";
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (out.length() > MAX_OUTPUT) {
                out = out.substring(0, MAX_OUTPUT) + "\n… (truncated)";
            }
            return p.exitValue() == 0
                    ? (out.isBlank() ? "(no output — use print())" : out)
                    : "Error (exit " + p.exitValue() + "):\n" + out;
        } catch (Exception ex) {
            return "Error: could not run the sandbox: " + ex.getMessage();
        }
    }
}
