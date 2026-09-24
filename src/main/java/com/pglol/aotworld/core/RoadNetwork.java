package com.pglol.aotworld.core;

import java.util.ArrayList;
import java.util.List;

/** All roads with a spatial index for per-column queries. */
public final class RoadNetwork {
    /** How far from a road the terrain is graded towards the road's smooth level. */
    public static final double GRADE = 7;

    public static final class Hit {
        public double dist = Double.POSITIVE_INFINITY;
        public double along, side;
        public Road road;

        public boolean on() {
            return road != null && dist <= road.type.halfWidth;
        }
    }

    private final List<Road> roads = new ArrayList<>();
    private volatile SpatialIndex<long[]> index = new SpatialIndex<>(128);

    public void add(Road r) {
        roads.add(r);
    }

    public List<Road> roads() {
        return roads;
    }

    public void build() {
        SpatialIndex<long[]> idx = new SpatialIndex<>(128);
        double m = 2.5 + GRADE;
        for (int ri = 0; ri < roads.size(); ri++) {
            Road r = roads.get(ri);
            for (int si = 0; si + 1 < r.xs.length; si++) {
                idx.add(new long[] {ri, si},
                    Math.min(r.xs[si], r.xs[si + 1]) - m, Math.min(r.zs[si], r.zs[si + 1]) - m,
                    Math.max(r.xs[si], r.xs[si + 1]) + m, Math.max(r.zs[si], r.zs[si + 1]) + m);
            }
        }
        index = idx;
    }

    /** Nearest road segment near (x, z); returns false when none is within grading range. */
    public boolean query(double x, double z, Hit hit) {
        hit.dist = Double.POSITIVE_INFINITY;
        hit.road = null;
        double[] t = new double[1];
        for (long[] e : index.at(x, z)) {
            Road r = roads.get((int) e[0]);
            int si = (int) e[1];
            double d = Mth.segDist(x, z, r.xs[si], r.zs[si], r.xs[si + 1], r.zs[si + 1], t);
            // Prefer wider roads where two overlap.
            double score = d - r.type.halfWidth;
            double best = hit.road == null ? Double.POSITIVE_INFINITY : hit.dist - hit.road.type.halfWidth;
            if (score < best) {
                hit.dist = d;
                hit.road = r;
                hit.along = r.cum[si] + t[0] * (r.cum[si + 1] - r.cum[si]);
                double dx = r.xs[si + 1] - r.xs[si], dz = r.zs[si + 1] - r.zs[si];
                hit.side = Math.signum(dx * (z - r.zs[si]) - dz * (x - r.xs[si]));
            }
        }
        return hit.road != null && hit.dist <= hit.road.type.halfWidth + GRADE;
    }

    /** Nearest point on any road of the given minimum rank (brute force; used at build time). */
    public double[] nearestPoint(double x, double z, boolean mainOnly, double maxDist) {
        double best = maxDist;
        double[] out = null, t = new double[1];
        for (Road r : roads) {
            if (mainOnly && (r.type == Road.Type.PATH || r.type == Road.Type.TRAIL)) continue;
            if (r.type == Road.Type.PATH) continue;
            for (int si = 0; si + 1 < r.xs.length; si++) {
                double minX = Math.min(r.xs[si], r.xs[si + 1]), maxX = Math.max(r.xs[si], r.xs[si + 1]);
                double minZ = Math.min(r.zs[si], r.zs[si + 1]), maxZ = Math.max(r.zs[si], r.zs[si + 1]);
                if (x < minX - best || x > maxX + best || z < minZ - best || z > maxZ + best) continue;
                double d = Mth.segDist(x, z, r.xs[si], r.zs[si], r.xs[si + 1], r.zs[si + 1], t);
                if (d < best) {
                    best = d;
                    out = new double[] {Mth.lerp(t[0], r.xs[si], r.xs[si + 1]), Mth.lerp(t[0], r.zs[si], r.zs[si + 1]), d};
                }
            }
        }
        return out;
    }

    /** Distance to the nearest road edge within range, or +inf. */
    public double clearance(double x, double z) {
        Hit h = new Hit();
        query(x, z, h);
        return h.road == null ? Double.POSITIVE_INFINITY : h.dist - h.road.type.halfWidth;
    }
}
