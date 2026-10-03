package ai.auralis.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The pure parts of the Laya-rated retrieval loop: Laya's short state and the query expansion. */
class RatedRetrieverTest {

    private static Evidence ev(String title, String content) {
        return new Evidence(UUID.randomUUID(), UUID.randomUUID(), title, 0, content, 1, 1, 0.5, 0.8);
    }

    @Test
    void stateIsNeedPlusDistinctTitlesOnly() {
        String s = RatedRetriever.state("SECDED SRAM ECC", List.of(
                ev("ECC for SRAM", "long passage text that must not be sent"),
                ev("ECC for SRAM", "another chunk of the same document"),
                ev("Bit-plane SECDED", "more text")));
        assertThat(s).isEqualTo("Need: SECDED SRAM ECC. Sources: ECC for SRAM; Bit-plane SECDED");
    }

    @Test
    void expansionAddsFrequentUnseenEvidenceTerms() {
        var rounds = List.of(new RatedRetriever.Round(1, "power gating", "REFINE", 0.6));
        String q = RatedRetriever.expand("power gating", rounds, List.of(
                ev("a", "isolation retention isolation switch power"),
                ev("b", "retention isolation acknowledge gating")));
        assertThat(q).startsWith("power gating ").contains("isolation", "retention");
        assertThat(q.substring("power gating".length())).doesNotContain("power", "gating");
    }
}
