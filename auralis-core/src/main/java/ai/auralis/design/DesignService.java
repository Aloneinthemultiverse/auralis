package ai.auralis.design;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import ai.auralis.decision.DecisionLedger;
import ai.auralis.design.DesignStore.Alternative;
import ai.auralis.design.DesignStore.Design;
import ai.auralis.design.DesignStore.Event;
import ai.auralis.design.DesignStore.Status;
import ai.auralis.design.DesignStore.Version;

/** Starts design runs and what-if branches in the background; assembles their view. */
@Service
public class DesignService {

    private static final Logger log = LoggerFactory.getLogger(DesignService.class);

    public record DesignView(Design design, List<Alternative> alternatives, List<Version> versions,
                             List<Event> trace, List<DecisionLedger.Entry> decisions) {
    }

    private final DesignEngine engine;
    private final DesignStore store;
    private final DecisionLedger ledger;
    private final ExecutorService runner = Executors.newVirtualThreadPerTaskExecutor();

    public DesignService(DesignEngine engine, DesignStore store, DecisionLedger ledger) {
        this.engine = engine;
        this.store = store;
        this.ledger = ledger;
    }

    @EventListener(ApplicationReadyEvent.class)
    void closeInterrupted() {
        int n = store.failInterrupted();
        if (n > 0) {
            log.warn("marked {} design(s) interrupted by the last shutdown as FAILED", n);
        }
    }

    public UUID start(String objective, UUID researchSessionId, int maxIterations) {
        return start(objective, researchSessionId, maxIterations, null);
    }

    /** @param notebook a notebook whose sources ground the design (e.g. a problem's), or null */
    public UUID start(String objective, UUID researchSessionId, int maxIterations, String notebook) {
        UUID id = store.create(objective, researchSessionId, maxIterations, notebook);
        runner.submit(() -> engine.run(id));
        return id;
    }

    public Optional<String> branch(UUID designId, UUID fromVersionId, String constraintChange,
                                   int iterations) {
        var design = store.design(designId);
        if (design.isEmpty()) {
            return Optional.of("no design " + designId);
        }
        if (design.get().status() == Status.RUNNING) {
            return Optional.of("design is still running; branch when it finishes");
        }
        if (store.version(designId, fromVersionId).isEmpty()) {
            return Optional.of("no version " + fromVersionId + " in design " + designId);
        }
        store.setStatus(designId, Status.RUNNING, null);
        runner.submit(() -> engine.branch(designId, fromVersionId, constraintChange, iterations));
        return Optional.empty();
    }

    public Optional<DesignView> view(UUID id) {
        return store.design(id).map(d -> new DesignView(d, store.alternatives(id),
                store.versions(id), store.events(id), ledger.recent("design", id, 100)));
    }

    @PreDestroy
    void shutdown() {
        runner.shutdownNow();
    }
}
