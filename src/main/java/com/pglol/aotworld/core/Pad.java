package com.pglol.aotworld.core;

/** A patch of ground levelled to a target height, blending into the terrain around it. */
public final class Pad {
    public final double x0, z0, x1, z1;
    public final boolean round;
    public final double cx, cz, r;
    public double target;
    public final double blend;

    private Pad(double x0, double z0, double x1, double z1, boolean round, double cx, double cz, double r,
                double target, double blend) {
        this.x0 = x0;
        this.z0 = z0;
        this.x1 = x1;
        this.z1 = z1;
        this.round = round;
        this.cx = cx;
        this.cz = cz;
        this.r = r;
        this.target = target;
        this.blend = blend;
    }

    public static Pad rect(double x0, double z0, double x1, double z1, double target, double blend) {
        return new Pad(Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1), false,
            (x0 + x1) / 2, (z0 + z1) / 2, 0, target, blend);
    }

    public static Pad circle(double cx, double cz, double r, double target, double blend) {
        return new Pad(cx - r, cz - r, cx + r, cz + r, true, cx, cz, r, target, blend);
    }

    /** Distance outside the pad (0 inside). */
    public double outside(double x, double z) {
        if (round) return Math.max(0, Mth.dist(x, z, cx, cz) - r);
        double dx = Math.max(Math.max(x0 - x, 0), x - x1);
        double dz = Math.max(Math.max(z0 - z, 0), z - z1);
        return Math.sqrt(dx * dx + dz * dz);
    }

    public double apply(double x, double z, double h) {
        double d = outside(x, z);
        if (d >= blend) return h;
        double w = 1 - Mth.smoothstep(0, blend, d);
        return Mth.lerp(w, h, target);
    }

    public void index(SpatialIndex<Pad> idx) {
        idx.add(this, x0 - blend, z0 - blend, x1 + blend, z1 + blend);
    }
}
