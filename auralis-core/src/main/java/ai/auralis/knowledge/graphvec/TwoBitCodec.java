package ai.auralis.knowledge.graphvec;

import java.util.SplittableRandom;

/**
 * TurboQuant-style 2-bit vector codec, in plain Java.
 * <p>
 * A vector is unit-normalised, padded to a power of two and rotated with a
 * seeded randomized Hadamard transform, which makes every coordinate roughly
 * N(0, 1/D). Each coordinate is then quantised to one of the four Lloyd-Max
 * levels for a Gaussian (2 bits), and the sign of the leftover residual is kept
 * as a 1-bit term used when decoding; each code also stores its exact gain. Scoring is asymmetric: the query stays full precision
 * (rotated once) and is dotted against the codes without decompressing them.
 */
public final class TwoBitCodec {

    /** Lloyd-Max levels of a standard normal for 4 cells. */
    private static final float[] LEVELS = {-1.5104f, -0.4528f, 0.4528f, 1.5104f};
    /** Decision thresholds between the levels. */
    private static final float T = 0.9816f;

    /** A compressed vector: 2-bit codes, residual signs, and their scales. */
    public record Code(long[] q2, long[] sign, float rMean, float gain) {
    }

    private final int dim;
    private final int padded;
    private final float[] flips;
    private final float unit;

    public TwoBitCodec(int dim, long seed) {
        this.dim = dim;
        this.padded = Integer.highestOneBit(Math.max(1, dim - 1)) << 1;
        this.flips = new float[padded];
        SplittableRandom rnd = new SplittableRandom(seed);
        for (int i = 0; i < padded; i++) {
            flips[i] = rnd.nextBoolean() ? 1f : -1f;
        }
        this.unit = (float) (1.0 / Math.sqrt(padded));
    }

    public int paddedDim() {
        return padded;
    }

    /** Rotated, unit-length copy of {@code v}; used for queries and for encoding. */
    public float[] rotate(float[] v) {
        float[] x = new float[padded];
        double n = 0;
        for (int i = 0; i < Math.min(dim, v.length); i++) {
            n += (double) v[i] * v[i];
        }
        float inv = n > 0 ? (float) (1.0 / Math.sqrt(n)) : 0f;
        for (int i = 0; i < Math.min(dim, v.length); i++) {
            x[i] = v[i] * inv * flips[i];
        }
        hadamard(x);
        for (int i = 0; i < padded; i++) {
            x[i] *= unit;
        }
        return x;
    }

    public Code encode(float[] v) {
        return encodeRotated(rotate(v));
    }

    /** Encodes a vector already in the rotated space (it is re-normalised). */
    public Code encodeRotated(float[] x) {
        double n = 0;
        for (float f : x) {
            n += (double) f * f;
        }
        float inv = n > 0 ? (float) (1.0 / Math.sqrt(n)) : 0f;
        long[] q2 = new long[padded / 32];
        long[] sign = new long[padded / 64];
        float s = unit;
        double absSum = 0;
        double xq = 0;
        for (int i = 0; i < padded; i++) {
            float z = x[i] * inv / s;
            int c = z < -T ? 0 : z < 0 ? 1 : z < T ? 2 : 3;
            q2[i >> 5] |= (long) c << ((i & 31) << 1);
            float r = x[i] * inv - LEVELS[c] * s;
            if (r >= 0) {
                sign[i >> 6] |= 1L << (i & 63);
            }
            absSum += Math.abs(r);
            xq += x[i] * inv * LEVELS[c] * s;
        }
        return new Code(q2, sign, (float) (absSum / padded), xq > 0 ? (float) (1.0 / xq) : 1f);
    }

    /**
     * Coarse 2-bit inner product with a rotated query: the fast scan. Lloyd-Max
     * reconstruction keeps E[x·Q(x)] = 0.8825 of the signal, so that is divided out.
     */
    public float score(float[] rq, Code c) {
        return raw(rq, c) / 0.8825f;
    }

    private float raw(float[] rq, Code c) {
        float acc = 0;
        long[] q2 = c.q2();
        for (int w = 0; w < q2.length; w++) {
            long bits = q2[w];
            int base = w << 5;
            for (int j = 0; j < 32; j++) {
                acc += rq[base + j] * LEVELS[(int) (bits >>> (j << 1)) & 3];
            }
        }
        return acc * unit;
    }

    /**
     * 2-bit score with this vector's exact gain 1/<x, Q(x)> instead of the average
     * 0.8825: unbiased per vector, used on the short list.
     */
    public float scoreCorrected(float[] rq, Code c) {
        return raw(rq, c) * c.gain();
    }

    /** Approximate rotated vector back from a code (for building neighbourhood codes). */
    public float[] decodeRotated(Code c) {
        float[] x = new float[padded];
        for (int i = 0; i < padded; i++) {
            int code = (int) (c.q2()[i >> 5] >>> ((i & 31) << 1)) & 3;
            boolean pos = ((c.sign()[i >> 6] >>> (i & 63)) & 1L) != 0;
            x[i] = LEVELS[code] * unit + (pos ? c.rMean() : -c.rMean());
        }
        return x;
    }

    private static void hadamard(float[] a) {
        for (int h = 1; h < a.length; h <<= 1) {
            for (int i = 0; i < a.length; i += h << 1) {
                for (int j = i; j < i + h; j++) {
                    float x = a[j];
                    float y = a[j + h];
                    a[j] = x + y;
                    a[j + h] = x - y;
                }
            }
        }
    }
}
