package ai.auralis.research.deep;

import java.io.File;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;

import ai.auralis.config.AuralisProperties;

/** AgentHarness on this machine (Ollama + SearXNG + Docker sandbox). */
public class LocalDeepResearch implements DeepResearch {

    private final AgentHarnessRunner runner;
    private final AuralisProperties.DeepResearch cfg;

    public LocalDeepResearch(JsonMapper json, AuralisProperties props) {
        this.runner = new AgentHarnessRunner(json);
        this.cfg = props.deepResearch();
    }

    @Override
    public List<Finding> research(List<Query> queries) {
        File harness = new File(cfg.agentHarnessDir());
        String python = new File(harness, cfg.python()).getAbsolutePath();
        return runner.run(queries, harness,
                (in, out) -> List.of(python, "auralis_research.py",
                        "--input", in.toString(), "--output", out.toString()),
                cfg.localTimeout(), "local");
    }
}
