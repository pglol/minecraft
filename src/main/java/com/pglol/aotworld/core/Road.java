package com.pglol.aotworld.core;

import java.util.ArrayList;
import java.util.List;

/** A road as a polyline. Main roads are wide highways; trails and paths are narrower. */
public final class Road {
    public enum Type {
        MAIN(2.5), PAVED(2.5), TRAIL(1.5), PATH(1.0);

        public final double halfWidth;

        Type(double halfWidth) {
            this.halfWidth = halfWidth;
        }
    }

    public final Type type;
    public final double[] xs, zs, cum;

    public Road(Type type, double[] xs, double[] zs) {
        this.type = type;
        this.xs = xs;
        this.zs = zs;
        cum = new double[xs.length];
        for (int i = 1; i < xs.length; i++) cum[i] = cum[i - 1] + Mth.dist(xs[i - 1], zs[i - 1], xs[i], zs[i]);
    }

    public double length() {
        return cum[cum.length - 1];
    }

    public static Road straight(Type type, double ax, double az, double bx, double bz) {
        int n = Math.max(1, (int) (Mth.dist(ax, az, bx, bz) / 24));
        double[] xs = new double[n + 1], zs = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            xs[i] = Mth.lerp((double) i / n, ax, bx);
            zs[i] = Mth.lerp((double) i / n, az, bz);
        }
        return new Road(type, xs, zs);
    }

    /** A gently meandering road between two points. */
    public static Road curve(Type type, double ax, double az, double bx, double bz, long seed) {
        double len = Mth.dist(ax, az, bx, bz);
        if (len < 1) return straight(type, ax, az, bx, bz);
        int n = Math.max(2, (int) (len / 10));
        double nx = -(bz - az) / len, nz = (bx - ax) / len;
        double amp = Math.min(len * 0.09, 70);
        double p1 = Hash.unit(seed) * 6.28, p2 = Hash.unit(Hash.mix(seed + 1)) * 6.28;
        double f1 = 1 + Hash.range(Hash.mix(seed + 2), 0, 2), f2 = 3 + Hash.range(Hash.mix(seed + 3), 0, 3);
        double[] xs = new double[n + 1], zs = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            double env = Math.sin(Math.PI * t);
            double off = env * amp * (0.75 * Math.sin(t * f1 * Math.PI + p1) + 0.25 * Math.sin(t * f2 * Math.PI + p2));
            xs[i] = ax + (bx - ax) * t + nx * off;
            zs[i] = az + (bz - az) * t + nz * off;
        }
        return new Road(type, xs, zs);
    }

    public static Road ring(Type type, double r) {
        int n = Math.max(64, (int) (2 * Math.PI * r / 16));
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            double a = 2 * Math.PI * i / n;
            pts.add(new double[] {Math.cos(a) * r, Math.sin(a) * r});
        }
        double[] xs = new double[pts.size()], zs = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            xs[i] = pts.get(i)[0];
            zs[i] = pts.get(i)[1];
        }
        return new Road(type, xs, zs);
    }
}
