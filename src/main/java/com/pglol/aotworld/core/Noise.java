package com.pglol.aotworld.core;

import java.util.SplittableRandom;

/** Seeded 2D gradient (Perlin) noise with fractal helpers. Thread-safe and immutable. */
public final class Noise {
    private final int[] perm = new int[512];

    public Noise(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        SplittableRandom r = new SplittableRandom(seed);
        for (int i = 255; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double grad(int h, double x, double y) {
        switch (h & 7) {
            case 0: return (x + y) * 0.7071;
            case 1: return (-x + y) * 0.7071;
            case 2: return (x - y) * 0.7071;
            case 3: return (-x - y) * 0.7071;
            case 4: return x;
            case 5: return -x;
            case 6: return y;
            default: return -y;
        }
    }

    /** Roughly in [-1, 1]. */
    public double sample(double x, double y) {
        int xi = Mth.floor(x), yi = Mth.floor(y);
        double xf = x - xi, yf = y - yi;
        int X = xi & 255, Y = yi & 255;
        double u = fade(xf), v = fade(yf);
        int aa = perm[perm[X] + Y], ab = perm[perm[X] + Y + 1];
        int ba = perm[perm[X + 1] + Y], bb = perm[perm[X + 1] + Y + 1];
        double x1 = Mth.lerp(u, grad(aa, xf, yf), grad(ba, xf - 1, yf));
        double x2 = Mth.lerp(u, grad(ab, xf, yf - 1), grad(bb, xf - 1, yf - 1));
        return Mth.clamp(Mth.lerp(v, x1, x2) * 1.5, -1, 1);
    }

    /** Fractal sum, normalised to roughly [-1, 1]. */
    public double fbm(double x, double y, int octaves) {
        double sum = 0, amp = 1, norm = 0, f = 1;
        for (int i = 0; i < octaves; i++) {
            sum += amp * sample(x * f + i * 17.3, y * f - i * 9.1);
            norm += amp;
            amp *= 0.5;
            f *= 2.03;
        }
        return sum / norm;
    }

    /** Ridged fractal in [0, 1]; sharp crests for mountain ranges. */
    public double ridged(double x, double y, int octaves) {
        double sum = 0, amp = 1, norm = 0, f = 1;
        for (int i = 0; i < octaves; i++) {
            double n = 1 - Math.abs(sample(x * f + i * 31.7, y * f + i * 5.3));
            sum += amp * n * n;
            norm += amp;
            amp *= 0.5;
            f *= 2.1;
        }
        return sum / norm;
    }
}
