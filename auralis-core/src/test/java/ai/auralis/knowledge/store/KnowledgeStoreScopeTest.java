package ai.auralis.knowledge.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import ai.auralis.graph.GraphStore;
import ai.auralis.knowledge.retrieval.Scope;
import ai.auralis.knowledge.store.KnowledgeStore.NewChunk;

/** Scoped retrieval against the real database: unscoped, by notebook, by document. */
@SpringBootTest
@ActiveProfiles("test")
class KnowledgeStoreScopeTest {

    @Autowired
    KnowledgeStore store;
    @Autowired
    GraphStore graph;

    private static float[] vec(float first) {
        float[] v = new float[768];
        Arrays.fill(v, 0.01f);
        v[0] = first;
        return v;
    }

    @Test
    void rareTermsOutrankRepeatedCommonOnesBm25Idf() {
        String tag = "zq" + UUID.randomUUID().toString().substring(0, 6).replaceAll("[0-9-]", "x");
        // Common words repeated many times vs. one rare, decisive identifier.
        UUID noisy = store.saveDocument("noisy " + tag, "paper", null, "n1" + tag,
                List.of(new NewChunk(("word bit word bit memory word bit " + tag + " ").repeat(20), vec(0.5f))));
        UUID decisive = store.saveDocument("decisive " + tag, "paper", null, "n2" + tag,
                List.of(new NewChunk("The " + tag + "secded code protects a 64-bit word.", vec(0.4f))));
        var hits = store.keywordSearch(tag + "secded word bit", 5, Scope.ALL);
        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().documentId()).isEqualTo(decisive);
        assertThat(hits).extracting(KnowledgeStore.ChunkHit::documentId).contains(noisy);
    }

    @Test
    void unscopedNotebookAndDocumentScopesAllWork() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UUID inNotebook = store.saveDocument("in " + tag, "paper", null, "h1" + tag,
                List.of(new NewChunk("systolic array dataflow " + tag, vec(1f))));
        UUID outside = store.saveDocument("out " + tag, "paper", null, "h2" + tag,
                List.of(new NewChunk("systolic array weight stationary " + tag, vec(0.9f))));
        String ns = "test-nb-" + tag;
        graph.upsertNode(ns, "doc", "WORK", "in", null, inNotebook, "{}");

        // Unscoped (Scope.ALL has null parameters) must not fail and must see both.
        assertThat(store.keywordSearch("systolic " + tag, 10, Scope.ALL))
                .extracting(KnowledgeStore.ChunkHit::documentId).contains(inNotebook, outside);
        assertThat(store.vectorSearch(vec(1f), 50, Scope.ALL)).isNotEmpty();

        // Notebook scope only sees documents linked into that notebook.
        assertThat(store.keywordSearch("systolic " + tag, 10, Scope.of(ns, null)))
                .extracting(KnowledgeStore.ChunkHit::documentId).containsExactly(inNotebook);
        assertThat(store.vectorSearch(vec(1f), 10, Scope.of(ns, null)))
                .extracting(KnowledgeStore.ChunkHit::documentId).containsOnly(inNotebook);

        // Explicit document selection.
        assertThat(store.keywordSearch("systolic " + tag, 10, Scope.of(null, List.of(outside))))
                .extracting(KnowledgeStore.ChunkHit::documentId).containsExactly(outside);
    }
}
