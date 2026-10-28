package ai.auralis.knowledge.hippo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import ai.auralis.knowledge.graphvec.TwoBitCodec;

class HippoGraphTest {

    private static float[] random(Random r) {
        float[] v = new float[768];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) r.nextGaussian();
        }
        return v;
    }

    private static float[] mix(float[] a, float[] b, double w) {
        float[] v = new float[a.length];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (w * a[i] + (1 - w) * b[i]);
        }
        return v;
    }

    /**
     * "What is one of the stars of The Newcomers known for?" Chunk 0 says The Newcomers stars
     * Chris Evans; chunk 1 says Chris Evans is known for Captain America but is not similar to
     * the question. Dense ranking misses chunk 1; the fact graph reaches it through the shared
     * entity, as HippoRAG does.
     */
    @Test
    void reachesSecondHopThroughSharedEntity() {
        Random r = new Random(3);
        List<float[]> chunks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            chunks.add(random(r));
        }
        float[] newcomers = random(r);
        float[] evans = random(r);
        float[] captain = random(r);
        List<float[]> ents = new ArrayList<>(List.of(newcomers, evans, captain));
        for (int i = 0; i < 30; i++) {
            ents.add(random(r));
        }
        float[] question = mix(newcomers, evans, 0.6);
        chunks.set(0, mix(question, chunks.get(0), 0.7));
        List<HippoGraph.Fact> facts = new ArrayList<>();
        facts.add(new HippoGraph.Fact(0, 0, 1, "the newcomers —stars→ chris evans", mix(question, evans, 0.8)));
        facts.add(new HippoGraph.Fact(1, 1, 2, "chris evans —known for→ captain america", mix(evans, captain, 0.5)));
        for (int i = 0; i < 60; i++) {
            int s = 3 + r.nextInt(30);
            int o = 3 + r.nextInt(30);
            facts.add(new HippoGraph.Fact(2 + r.nextInt(38), s, o, "noise", random(r)));
        }
        TwoBitCodec codec = new TwoBitCodec(768, 0x5EEDL);
        HippoGraph g = HippoGraph.build(codec, chunks, ents, facts);

        float[] dense = new float[chunks.size()];
        float[] rq = codec.rotate(question);
        for (int i = 0; i < dense.length; i++) {
            dense[i] = codec.score(rq, codec.encode(chunks.get(i)));
        }
        int[] denseTop = HippoGraph.topK(dense, 5);
        assertTrue(Arrays.stream(denseTop).noneMatch(i -> i == 1), "dense retrieval should miss the second hop");

        HippoGraph.Result res = g.search(question, 5);
        assertTrue(Arrays.stream(res.chunks()).limit(3).anyMatch(i -> i == 0), Arrays.toString(res.chunks()));
        assertTrue(Arrays.stream(res.chunks()).limit(3).anyMatch(i -> i == 1), Arrays.toString(res.chunks()));
        assertEquals("the newcomers —stars→ chris evans", res.facts().getFirst());
    }

    @Test
    void sizeIsTwoBitCodesPlusEdges() {
        Random r = new Random(1);
        List<float[]> chunks = List.of(random(r), random(r));
        List<float[]> ents = List.of(random(r), random(r));
        HippoGraph g = HippoGraph.build(new TwoBitCodec(768, 1), chunks, ents,
                List.of(new HippoGraph.Fact(0, 0, 1, "a —r→ b", random(r))));
        assertEquals(5L * (1024 / 4 + 4) + g.edges() * 7, g.bytes());
    }

    @Test
    void parsesModelJson() {
        List<String[]> t = FactExtractor.parse("""
                Sure: {"entities": ["CVA6", "AXI4"], "triples": [["CVA6", "connects  to", "AXI4 crossbar"], ["bad"]]}""");
        assertEquals(1, t.size());
        assertEquals("cva6", t.getFirst()[0]);
        assertEquals("connects to", t.getFirst()[1]);
        assertEquals("axi4 crossbar", t.getFirst()[2]);
        assertTrue(FactExtractor.parse("no json").isEmpty());
    }
}
