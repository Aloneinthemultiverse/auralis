package ai.auralis.research.deep;

import java.io.File;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;

import ai.auralis.config.AuralisProperties;

/**
 * AgentHarness on a free Kaggle T4 GPU via {@code kaggle/run_worker.sh}: slower
 * to start (a kernel boots per batch) but serves the real Apodex model.
 */
public class KaggleDeepResearch implements DeepResearch {

    private final AgentHarnessRunner runner;
    private final AuralisProperties.DeepResearch cfg;

    public KaggleDeepResearch(JsonMapper json, AuralisProperties props) {
        this.runner = new AgentHarnessRunner(json);
        this.cfg = props.deepResearch();
    }

    @Override
    public List<Finding> research(List<Query> queries) {
        File kaggleDir = new File(cfg.kaggleDir());
        String script = new File(kaggleDir, "run_worker.sh").getAbsolutePath();
        return runner.run(queries, kaggleDir,
                (in, out) -> List.of(cfg.bash(), script, in.toString(),
                        out.getParent().toString(), "auto", String.valueOf(cfg.maxTurns())),
                cfg.kaggleTimeout(), "kaggle");
    }
}
