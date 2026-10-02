package ai.auralis.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import ai.auralis.knowledge.store.KnowledgeStore.ChunkHit;

class ReciprocalRankFusionTest {

    private static final UUID DOC = UUID.randomUUID();

    private static ChunkHit hit(UUID id, int rank) {
        return new ChunkHit(id, DOC, "doc", 0, "text", rank, 0.0);
    }

    @Test
    void chunkFoundByBothRetrieversRanksFirst() {
        UUID both = UUID.randomUUID();
        UUID vectorOnly = UUID.randomUUID();
        UUID keywordOnly = UUID.randomUUID();

        List<Evidence> fused = ReciprocalRankFusion.fuse(
                List.of(hit(vectorOnly, 1), hit(both, 2)),
                List.of(hit(keywordOnly, 1), hit(both, 2)),
                60);

        assertThat(fused).hasSize(3);
        Evidence top = fused.getFirst();
        assertThat(top.chunkId()).isEqualTo(both);
        assertThat(top.vectorRank()).isEqualTo(2);
        assertThat(top.keywordRank()).isEqualTo(2);
        assertThat(top.fusedScore()).isCloseTo(2.0 / 62, within(1e-12));
    }

    @Test
    void singleRetrieverKeepsItsOrder() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<Evidence> fused = ReciprocalRankFusion.fuse(List.of(hit(a, 1), hit(b, 2)), List.of(), 60);
        assertThat(fused).extracting(Evidence::chunkId).containsExactly(a, b);
        assertThat(fused.getFirst().keywordRank()).isNull();
    }
}
