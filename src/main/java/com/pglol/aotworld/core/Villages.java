package com.pglol.aotworld.core;

import com.pglol.aotworld.core.build.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Procedurally scattered farming villages, one candidate per 1400-block cell. */
public final class Villages {
    public static final int CELL = 1400;
    private final AotWorld world;
    private final ConcurrentHashMap<Long, Optional<Village>> cache = new ConcurrentHashMap<>();

    Villages(AotWorld world) {
        this.world = world;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    public Village get(int cx, int cz) {
        Optional<Village> v = cache.get(key(cx, cz));
        if (v == null) {
            v = Optional.ofNullable(create(cx, cz));
            Optional<Village> prev = cache.putIfAbsent(key(cx, cz), v);
            if (prev != null) v = prev;
        }
        return v.orElse(null);
    }

    public List<Village> near(int x0, int z0, int x1, int z1) {
        List<Village> out = new ArrayList<>(2);
        int m = 300;
        for (int cx = Math.floorDiv(x0 - m, CELL); cx <= Math.floorDiv(x1 + m, CELL); cx++) {
            for (int cz = Math.floorDiv(z0 - m, CELL); cz <= Math.floorDiv(z1 + m, CELL); cz++) {
                Village v = get(cx, cz);
                if (v != null && v.intersects(x0, z0, x1, z1)) out.add(v);
            }
        }
        return out;
    }

    public double roadDistance(int x, int z) {
        int cx = Math.floorDiv(x, CELL), cz = Math.floorDiv(z, CELL);
        double best = 99;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Village v = get(cx + dx, cz + dz);
                if (v != null) best = Math.min(best, v.roadDistance(x, z));
            }
        }
        return best <= 2.5 ? best : -1;
    }

    private Village create(int cx, int cz) {
        Atlas atlas = world.atlas;
        long h = Hash.of(world.spec.seed ^ 0x51AA6E, cx, cz);
        if (Hash.unit(h) > 0.5) return null;
        int x = cx * CELL + Hash.range(Hash.mix(h + 1), 200, CELL - 200);
        int z = cz * CELL + Hash.range(Hash.mix(h + 2), 200, CELL - 200);
        if (x < atlas.minX + 500 || x > atlas.maxX - 500) return null;
        if (atlas.landSD(x, z) < 300) return null;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            if (atlas.wallFlatten(x + Math.cos(a) * 170, z + Math.sin(a) * 170) > 0) return null;
        }
        for (Atlas.Site s : atlas.sites) {
            if (Mth.dist(x, z, s.x, s.z) < s.radius + 260) return null;
        }
        double[] tmp = new double[1];
        for (int dx = -160; dx <= 160; dx += 80) {
            for (int dz = -160; dz <= 160; dz += 80) {
                if (atlas.riverIndex.query(x + dx, z + dz, tmp) < River.Index.INFLUENCE + 20) return null;
            }
        }
        if (world.terrain.mountainMask(x, z) > 0.15) return null;
        int hc = world.terrain.height(x, z);
        if (hc > 100 || hc <= WorldSpec.SEA + 1) return null;
        int land = atlas.landmass(x, z);
        return new Village(world, null, x, z, 55, Hash.mix(h + 3), land == Column.MARLEY, roadTarget(x, z, land));
    }

    /** Where a village road connects to the network. */
    public double[] roadTarget(double x, double z, int land) {
        Atlas atlas = world.atlas;
        if (land == Column.PARADIS) {
            double r = Math.sqrt(x * x + z * z);
            double target = atlas.ringRoads[Math.min(atlas.ring(x, z), 3)];
            if (atlas.ring(x, z) == 3 && r < target) target = atlas.ringRoads[3];
            double f = target / r;
            return new double[] {x * f, z * f};
        }
        double best = Double.MAX_VALUE;
        double[] out = null, t = new double[1];
        for (double[] s : atlas.roads) {
            if (atlas.landmass(s[0], s[1]) != Column.MARLEY) continue;
            double d = Mth.segDist(x, z, s[0], s[1], s[2], s[3], t);
            if (d < best) {
                best = d;
                out = new double[] {Mth.lerp(t[0], s[0], s[2]), Mth.lerp(t[0], s[1], s[3])};
            }
        }
        return out;
    }
}
