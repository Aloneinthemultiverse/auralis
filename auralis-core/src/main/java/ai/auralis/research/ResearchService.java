package ai.auralis.research;

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

import ai.auralis.config.AuralisProperties;
import ai.auralis.research.store.ResearchStore;
import ai.auralis.research.store.ResearchStore.DeepMode;
import ai.auralis.research.store.ResearchStore.EvidenceRow;
import ai.auralis.research.store.ResearchStore.Event;
import ai.auralis.research.store.ResearchStore.Question;
import ai.auralis.research.store.ResearchStore.Session;

/** Starts research sessions in the background and assembles their full view. */
@Service
public class ResearchService {

    private static final Logger log = LoggerFactory.getLogger(ResearchService.class);

    public record QuestionView(Question question, List<EvidenceRow> evidence) {
    }

    public record SessionView(Session session, List<QuestionView> questions, List<Event> trace) {
    }

    private final ResearchEngine engine;
    private final ResearchStore store;
    private final AuralisProperties.Research cfg;
    private final ExecutorService runner = Executors.newVirtualThreadPerTaskExecutor();

    public ResearchService(ResearchEngine engine, ResearchStore store, AuralisProperties props) {
        this.engine = engine;
        this.store = store;
        this.cfg = props.research();
    }

    @EventListener(ApplicationReadyEvent.class)
    void closeInterruptedSessions() {
        int n = store.failInterrupted();
        if (n > 0) {
            log.warn("marked {} research session(s) interrupted by the last shutdown as FAILED", n);
        }
    }

    public UUID start(String objective, Integer maxRounds, DeepMode mode) {
        int rounds = maxRounds != null ? maxRounds : cfg.maxRounds();
        UUID id = store.createSession(objective, mode != null ? mode : DeepMode.NONE, rounds);
        runner.submit(() -> engine.run(id));
        return id;
    }

    public Optional<SessionView> view(UUID id) {
        return store.session(id).map(s -> new SessionView(s,
                store.questions(id).stream()
                        .map(q -> new QuestionView(q, store.evidence(q.id()))).toList(),
                store.events(id)));
    }

    @PreDestroy
    void shutdown() {
        runner.shutdownNow();
    }
}
