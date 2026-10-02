package ai.auralis.knowledge.retrieval;

import java.util.UUID;

/**
 * A retrieved chunk with the signals that put it in the evidence set, so a
 * later decision can say why this passage was trusted.
 *
 * @param vectorRank   rank in dense retrieval, or null if not retrieved there
 * @param keywordRank  rank in lexical retrieval, or null if not retrieved there
 * @param fusedScore   reciprocal rank fusion score
 * @param relevance    Laya's probability the passage is relevant, or null if not reranked
 */
public record Evidence(
        UUID chunkId,
        UUID documentId,
        String documentTitle,
        int ordinal,
        String content,
        Integer vectorRank,
        Integer keywordRank,
        double fusedScore,
        Double relevance) {

    Evidence withRelevance(double r) {
        return new Evidence(chunkId, documentId, documentTitle, ordinal, content,
                vectorRank, keywordRank, fusedScore, r);
    }
}
