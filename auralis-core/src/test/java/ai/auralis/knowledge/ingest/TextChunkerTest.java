package ai.auralis.knowledge.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class TextChunkerTest {

    @Test
    void shortTextIsOneChunk() {
        assertThat(new TextChunker(100, 10).chunk("Hello world.")).containsExactly("Hello world.");
    }

    @Test
    void blankTextHasNoChunks() {
        assertThat(new TextChunker(100, 10).chunk("  \n\n  ")).isEmpty();
    }

    @Test
    void chunksRespectTargetAndCarryOverlap() {
        String para = "alpha beta gamma delta epsilon zeta eta theta. ";
        String text = (para.repeat(3) + "\n\n").repeat(10);
        List<String> chunks = new TextChunker(400, 60).chunk(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        // Each chunk stays near the target (target + carried overlap at most).
        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(400 + 60));
        // Consecutive chunks share text, so boundary facts are not lost.
        for (int i = 1; i < chunks.size(); i++) {
            String prevEnd = chunks.get(i - 1).substring(chunks.get(i - 1).length() - 20);
            assertThat(chunks.get(i)).contains(prevEnd.strip().split(" ")[1]);
        }
    }

    @Test
    void oversizedSentenceIsHardSplit() {
        String longWord = "x".repeat(1000);
        assertThat(new TextChunker(300, 0).chunk(longWord))
                .allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(300));
    }

    @Test
    void overlapMustBeSmallerThanTarget() {
        assertThatThrownBy(() -> new TextChunker(100, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
