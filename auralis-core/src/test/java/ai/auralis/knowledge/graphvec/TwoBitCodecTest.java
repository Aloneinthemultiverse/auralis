package ai.auralis.knowledge.graphvec;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TwoBitCodecTest {

    private static float[] randomUnit(Random r, int d) {
        float[] v = new float[d];
        double n = 0;
        for (int i = 0; i < d; i++) {
            v[i] = (float) r.nextGaussian();
            n += v[i] * v[i];
        }
        for (int i = 0; i < d; i++) {
            v[i] /= (float) Math.sqrt(n);
        }
        return v;
    }

    private static double cos(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    @Test
    void scoresTrackTrueCosine() {
        TwoBitCodec codec = new TwoBitCodec(768, 7);
        Random r = new Random(1);
        float[] q = randomUnit(r, 768);
        float[] rq = codec.rotate(q);
        double errCoarse = 0;
        double errCorrected = 0;
        for (int t = 0; t < 200; t++) {
            float[] x = randomUnit(r, 768);
            // Mix in some of q so the true cosines span a range.
            double a = t / 200.0;
            for (int i = 0; i < 768; i++) {
                x[i] = (float) (a * q[i] + (1 - a) * x[i]);
            }
            double truth = cos(q, x) / Math.sqrt(cos(x, x));
            TwoBitCodec.Code c = codec.encode(x);
            errCoarse += Math.abs(codec.score(rq, c) - truth);
            errCorrected += Math.abs(codec.scoreCorrected(rq, c) - truth);
        }
        assertTrue(errCoarse / 200 < 0.06, "coarse error " + errCoarse / 200);
        assertTrue(errCorrected / 200 < errCoarse / 200, "coarse " + errCoarse / 200 + " corrected " + errCorrected / 200);
    }

    @Test
    void extractsTechnicalTerms() {
        Set<String> t = GraphVecIndex.terms("The CVA6 core uses AXI4 to reach LPDDR4; see Table 3 and the SECDED ECC.");
        assertTrue(t.containsAll(Set.of("CVA6", "AXI4", "LPDDR4", "SECDED", "ECC")), t.toString());
        assertTrue(!t.contains("THE") && !t.contains("TABLE"), t.toString());
    }
}
