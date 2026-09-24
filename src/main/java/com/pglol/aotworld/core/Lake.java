package com.pglol.aotworld.core;

/** A still lake with a lobed shoreline. */
public final class Lake {
    public final double cx, cz, r, depth;
    public final int level;
    private final double p1, p2;

    public Lake(double cx, double cz, double r, int level, double depth, long seed) {
        this.cx = cx;
        this.cz = cz;
        this.r = r;
        this.level = level;
        this.depth = depth;
        this.p1 = Hash.unit(seed) * 6.28;
        this.p2 = Hash.unit(Hash.mix(seed + 1)) * 6.28;
    }

    public double radiusAt(double x, double z) {
        double a = Math.atan2(z - cz, x - cx);
        return r * (1 + 0.18 * Math.sin(3 * a + p1) + 0.1 * Math.sin(5 * a + p2));
    }

    /** Distance from the centre divided by the local shore radius. */
    public double norm(double x, double z) {
        return Mth.dist(x, z, cx, cz) / radiusAt(x, z);
    }

    public double reach() {
        return r * 1.3 + 18;
    }
}
