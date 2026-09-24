package com.pglol.aotworld.core;

public final class Mth {
    private Mth() {}

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }

    public static double smoothstep(double e0, double e1, double x) {
        double t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
        return t * t * (3 - 2 * t);
    }

    public static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    public static double dist(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2, dz = z1 - z2;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Distance from point p to segment ab; out[0] receives the segment parameter t in [0,1]. */
    public static double segDist(double px, double pz, double ax, double az, double bx, double bz, double[] out) {
        double dx = bx - ax, dz = bz - az;
        double len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : clamp(((px - ax) * dx + (pz - az) * dz) / len2, 0, 1);
        if (out != null) out[0] = t;
        double cx = ax + dx * t, cz = az + dz * t;
        return dist(px, pz, cx, cz);
    }
}
