package ai.auralis.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ai.auralis.knowledge.ingest.IngestionService;
import ai.auralis.knowledge.ingest.IngestionService.IngestResult;
import ai.auralis.knowledge.retrieval.Evidence;
import ai.auralis.knowledge.retrieval.HybridRetriever;
import ai.auralis.research.ResearchAnalyst.Answer;
import ai.auralis.research.ResearchGate.Sufficiency;
import ai.auralis.research.deep.DeepResearch;
import ai.auralis.research.deep.LocalDeepResearch;
import ai.auralis.research.store.ResearchStore;
import ai.auralis.research.store.ResearchStore.DeepMode;
import ai.auralis.research.store.ResearchStore.Question;
import ai.auralis.research.store.ResearchStore.QuestionStatus;
import ai.auralis.research.store.ResearchStore.Status;

/** Loop control logic against the real store, with the models mocked. */
@SpringBootTest
@ActiveProfiles("test")
class ResearchEngineTest {

    private static final String KNOWN = "What is the leakage of power gating?";
    private static final String UNKNOWN = "What soft error rate does 22nm SRAM have?";

    @Autowired
    ResearchEngine engine;
    @Autowired
    ResearchStore store;

    @MockitoBean
    ResearchAnalyst analyst;
    @MockitoBean
    ResearchGate gate;
    @MockitoBean
    HybridRetriever retriever;
    @MockitoBean
    LocalDeepResearch local;
    @MockitoBean
    IngestionService ingestion;

    private void commonStubs() {
        when(analyst.plan(anyString(), anyInt())).thenReturn(List.of(KNOWN, UNKNOWN));
        when(retriever.retrieve(anyString(), anyInt(), anyBoolean())).thenReturn(List.of(
                new Evidence(null, UUID.randomUUID(), "notes", 0, "power gating cuts leakage 10x",
                        1, 1, 0.03, 0.9)));
        when(analyst.answer(eq(KNOWN), anyList()))
                .thenReturn(new Answer("leakage drops 10x [1]", true, null));
        // Not answerable from the knowledge base; answerable once web evidence is added.
        when(analyst.answer(eq(UNKNOWN), anyList()))
                .thenReturn(new Answer("not in evidence", false, "a measured SER"),
                        new Answer("about 1000 FIT/Mb [2]", true, null));
        when(analyst.report(anyString(), anyList())).thenReturn("report");
        when(ingestion.ingestText(anyString(), anyString(), anyString(), any()))
                .thenReturn(new IngestResult(UUID.randomUUID(), 1, false));
    }

    @Test
    void escalatesOnlyUnansweredQuestionsAndStopsWhenSufficient() {
        commonStubs();
        when(gate.sufficiency(anyString(), anyString()))
                .thenReturn(new Sufficiency(0.9, 0.1, "design", Map.of()));
        when(local.research(anyList())).thenAnswer(inv -> {
            List<DeepResearch.Query> qs = inv.getArgument(0);
            return qs.stream().map(q -> new DeepResearch.Finding(q.id(), q.question(),
                    "web answer", List.of("https://example.org/paper"), 12, null)).toList();
        });

        UUID id = store.createSession("objective", DeepMode.LOCAL, 3);
        engine.run(id);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepResearch.Query>> sent = ArgumentCaptor.forClass(List.class);
        verify(local).research(sent.capture());
        assertThat(sent.getValue()).extracting(DeepResearch.Query::question).containsExactly(UNKNOWN);
        // Web finding becomes engineering memory.
        verify(ingestion).ingestText(anyString(), eq("Web research: " + UNKNOWN),
                eq("web-research"), eq("https://example.org/paper"));

        var session = store.session(id).orElseThrow();
        assertThat(session.status()).isEqualTo(Status.SUFFICIENT);
        assertThat(session.roundsRun()).isEqualTo(1);
        assertThat(session.report()).isEqualTo("report");
        assertThat(store.questions(id)).extracting(Question::status)
                .containsOnly(QuestionStatus.ANSWERED);
        verify(analyst, never()).gaps(anyString(), anyList(), anyInt());
    }

    @Test
    void withoutDeepResearchUnansweredStaysUnresolvedAndGapsDriveNextRound() {
        commonStubs();
        when(gate.sufficiency(anyString(), anyString()))
                .thenReturn(new Sufficiency(0.2, 0.1, "research", Map.of()));
        when(analyst.answer(eq(UNKNOWN), anyList()))
                .thenReturn(new Answer("not in evidence", false, "a measured SER"));
        when(analyst.answer(eq("Which ECC scheme fits 32-bit words?"), anyList()))
                .thenReturn(new Answer("SECDED [1]", true, null));
        when(analyst.gaps(anyString(), anyList(), anyInt()))
                .thenReturn(List.of("Which ECC scheme fits 32-bit words?", KNOWN.toUpperCase()));

        UUID id = store.createSession("objective", DeepMode.NONE, 2);
        engine.run(id);

        verify(local, never()).research(anyList());
        var session = store.session(id).orElseThrow();
        assertThat(session.status()).isEqualTo(Status.EXHAUSTED);
        assertThat(session.roundsRun()).isEqualTo(2);

        List<Question> qs = store.questions(id);
        // The repeated question (different case) was de-duplicated.
        assertThat(qs).hasSize(3);
        assertThat(qs).filteredOn(q -> q.text().equals(UNKNOWN))
                .extracting(Question::status).containsExactly(QuestionStatus.UNRESOLVED);
        assertThat(qs).filteredOn(q -> q.origin().equals("GAP"))
                .extracting(Question::round).containsExactly(2);
    }

    @Test
    void failureIsRecordedOnTheSession() {
        when(analyst.plan(anyString(), anyInt())).thenThrow(new IllegalStateException("model down"));

        UUID id = store.createSession("objective", DeepMode.NONE, 2);
        engine.run(id);

        var session = store.session(id).orElseThrow();
        assertThat(session.status()).isEqualTo(Status.FAILED);
        assertThat(session.error()).contains("model down");
    }
}
