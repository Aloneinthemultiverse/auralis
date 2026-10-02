package ai.auralis.knowledge.retrieval;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.auralis.knowledge.store.KnowledgeStore.ChunkHit;

/**
 * Reciprocal rank fusion: score(d) = Σ 1 / (k + rank_i(d)). Uses ranks only,
 * so cosine similarities and text-rank scores never need to be calibrated
 * against each other.
 */
public final class ReciprocalRankFusion {

    private ReciprocalRankFusion() {
    }

    public static List<Evidence> fuse(List<ChunkHit> vector, List<ChunkHit> keyword, int k) {
        Map<UUID, Evidence> byId = new LinkedHashMap<>();
        for (ChunkHit h : vector) {
            byId.put(h.chunkId(), evidence(h, h.rank(), null, 1.0 / (k + h.rank())));
        }
        for (ChunkHit h : keyword) {
            double add = 1.0 / (k + h.rank());
            byId.merge(h.chunkId(), evidence(h, null, h.rank(), add),
                    (prev, kw) -> evidence(h, prev.vectorRank(), h.rank(), prev.fusedScore() + add));
        }
        return byId.values().stream()
                .sorted(Comparator.comparingDouble(Evidence::fusedScore).reversed())
                .toList();
    }

    private static Evidence evidence(ChunkHit h, Integer vRank, Integer kRank, double score) {
        return new Evidence(h.chunkId(), h.documentId(), h.documentTitle(), h.ordinal(),
                h.content(), vRank, kRank, score, null);
    }
}
