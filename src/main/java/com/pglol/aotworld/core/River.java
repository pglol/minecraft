package com.pglol.aotworld.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A river as a polyline with a water level that falls towards the sea. */
public final class River {
    public final String name;
    public final double[] xs, zs, levels;
    public final double halfWidth = 7, depth = 5;

    public River(String name, double[] xs, double[] zs, double[] levels) {
        this.name = name;
        this.xs = xs;
        this.zs = zs;
        this.levels = levels;
    }

    /** Spatial index over all river segments for fast nearest queries. */
    public static final class Index {
        public static final double INFLUENCE = 7 + 48;
        private static final int CELL = 256;
        private final List<River> rivers;
        private final Map<Long, int[]> cells = new HashMap<>();

        public Index(List<River> rivers) {
            this.rivers = rivers;
            Map<Long, List<Integer>> tmp = new HashMap<>();
            for (int ri = 0; ri < rivers.size(); ri++) {
                River r = rivers.get(ri);
                for (int si = 0; si + 1 < r.xs.length; si++) {
                    double minX = Math.min(r.xs[si], r.xs[si + 1]) - INFLUENCE;
                    double maxX = Math.max(r.xs[si], r.xs[si + 1]) + INFLUENCE;
                    double minZ = Math.min(r.zs[si], r.zs[si + 1]) - INFLUENCE;
                    double maxZ = Math.max(r.zs[si], r.zs[si + 1]) + INFLUENCE;
                    for (int cx = Mth.floor(minX / CELL); cx <= Mth.floor(maxX / CELL); cx++) {
                        for (int cz = Mth.floor(minZ / CELL); cz <= Mth.floor(maxZ / CELL); cz++) {
                            tmp.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add((ri << 16) | si);
                        }
                    }
                }
            }
            tmp.forEach((k, v) -> cells.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
        }

        private static long key(int cx, int cz) {
            return ((long) cx << 32) ^ (cz & 0xffffffffL);
        }

        /**
         * Distance to the nearest river centre line (or +inf when none is within
         * influence). out[0] receives the water level at that point.
         */
        public double query(double x, double z, double[] out) {
            int[] segs = cells.get(key(Mth.floor(x / CELL), Mth.floor(z / CELL)));
            if (segs == null) return Double.POSITIVE_INFINITY;
            double best = Double.POSITIVE_INFINITY;
            double[] t = new double[1];
            for (int packed : segs) {
                River r = rivers.get(packed >>> 16);
                int si = packed & 0xffff;
                double d = Mth.segDist(x, z, r.xs[si], r.zs[si], r.xs[si + 1], r.zs[si + 1], t);
                if (d < best) {
                    best = d;
                    out[0] = Mth.lerp(t[0], r.levels[si], r.levels[si + 1]);
                }
            }
            return best;
        }
    }
}
