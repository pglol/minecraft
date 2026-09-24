package com.pglol.aotworld.core.build;

import com.pglol.aotworld.core.AotWorld;
import com.pglol.aotworld.core.Blocks;
import com.pglol.aotworld.core.ChunkBuffer;
import com.pglol.aotworld.core.Column;
import com.pglol.aotworld.core.Feature;
import com.pglol.aotworld.core.Hash;
import com.pglol.aotworld.core.Mth;
import com.pglol.aotworld.core.WorldSpec;

import java.util.ArrayList;
import java.util.List;

/** A farming village: cottages around a well, dirt paths, and crop fields. */
public final class Village extends Feature {
    public final String name;
    public final int cx, cz, radius;
    private final List<House> houses = new ArrayList<>();
    private final List<int[]> fields = new ArrayList<>(); // x0, z0, x1, z1, crop, alongX
    private final double[] road;
    private final long seed;
    private static final int[] CROPS = {Blocks.WHEAT, Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS};

    public Village(AotWorld world, String name, int cx, int cz, int radius, long seed, boolean marley, double[] roadTo) {
        super(cx - radius - 80, cz - radius - 80, cx + radius + 80, cz + radius + 80);
        this.name = name;
        this.cx = cx;
        this.cz = cz;
        this.radius = radius;
        this.seed = seed;
        this.road = roadTo == null ? null : new double[] {cx, cz, roadTo[0], roadTo[1]};

        Style[] styles = marley ? new Style[] {Style.LIBERIO[2], Style.RURAL[0]} : Style.RURAL;
        int want = name != null ? 14 : Hash.range(seed, 6, 11);
        for (int i = 0; i < 80 && houses.size() < want; i++) {
            long h = Hash.of(seed, i);
            double ang = Hash.unit(h) * Math.PI * 2;
            double dist = 14 + Hash.unit(Hash.mix(h + 1)) * (radius - 18);
            int hx = cx + (int) (Math.cos(ang) * dist), hz = cz + (int) (Math.sin(ang) * dist);
            int w = Hash.range(Hash.mix(h + 2), 7, 9), d = Hash.range(Hash.mix(h + 3), 6, 8);
            int dx = hx - cx, dz = hz - cz;
            boolean doorOnX = Math.abs(dx) > Math.abs(dz);
            int x0, z0, x1, z1;
            if (doorOnX) { x0 = hx - d / 2; x1 = x0 + d - 1; z0 = hz - w / 2; z1 = z0 + w - 1; }
            else { x0 = hx - w / 2; x1 = x0 + w - 1; z0 = hz - d / 2; z1 = z0 + d - 1; }
            if (overlaps(x0 - 4, z0 - 4, x1 + 4, z1 + 4)) continue;
            int hmin = Integer.MAX_VALUE, hmax = Integer.MIN_VALUE;
            for (int[] c : new int[][] {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}, {hx, hz}}) {
                int hh = world.terrain.height(c[0], c[1]);
                hmin = Math.min(hmin, hh);
                hmax = Math.max(hmax, hh);
            }
            if (hmax - hmin > 3 || hmin <= WorldSpec.SEA) continue;
            int base = world.terrain.height(hx, hz);
            int doorSide = doorOnX ? (dx > 0 ? -1 : 1) : (dz > 0 ? -1 : 1);
            int floors = Hash.unit(Hash.mix(h + 4)) < 0.7 ? 1 : 2;
            Style st = styles[Hash.range(Hash.mix(h + 5), 0, styles.length - 1)];
            houses.add(new House(x0, z0, x1, z1, !doorOnX, base, floors, st, doorSide, Hash.unit(Hash.mix(h + 6)) < 0.5));
        }
        int nf = Hash.range(Hash.mix(seed + 9), 3, 5) + (name != null ? 2 : 0);
        for (int i = 0; i < 30 && fields.size() < nf; i++) {
            long h = Hash.of(seed, 1000 + i);
            double ang = Hash.unit(h) * Math.PI * 2;
            double dist = radius + 16 + Hash.unit(Hash.mix(h + 1)) * 40;
            int fx = cx + (int) (Math.cos(ang) * dist), fz = cz + (int) (Math.sin(ang) * dist);
            int w = Hash.range(Hash.mix(h + 2), 16, 28), d = Hash.range(Hash.mix(h + 3), 12, 20);
            int[] f = {fx - w / 2, fz - d / 2, fx + w / 2, fz + d / 2, CROPS[Hash.range(Hash.mix(h + 4), 0, 4)],
                Hash.unit(Hash.mix(h + 5)) < 0.5 ? 1 : 0};
            boolean clash = false;
            for (int[] o : fields) {
                if (f[2] + 3 >= o[0] && f[0] - 3 <= o[2] && f[3] + 3 >= o[1] && f[1] - 3 <= o[3]) clash = true;
            }
            if (!clash) fields.add(f);
        }
    }

    private boolean overlaps(int x0, int z0, int x1, int z1) {
        if (Math.abs((x0 + x1) / 2 - cx) < 9 && Math.abs((z0 + z1) / 2 - cz) < 9) return true;
        for (House o : houses) {
            if (x1 >= o.x0 && x0 <= o.x1 && z1 >= o.z0 && z0 <= o.z1) return true;
        }
        return false;
    }

    public double roadDistance(int x, int z) {
        if (road == null) return 99;
        if (Math.hypot(x - cx, z - cz) < radius) return 99;
        return Mth.segDist(x, z, road[0], road[1], road[2], road[3], null);
    }

    @Override
    public int layer() {
        return 5;
    }

    @Override
    public boolean occupies(int x, int z) {
        if (Math.hypot(x - cx, z - cz) < radius + 6) return true;
        for (int[] f : fields) if (x >= f[0] - 1 && x <= f[2] + 1 && z >= f[1] - 1 && z <= f[3] + 1) return true;
        return false;
    }

    @Override
    public void column(ChunkBuffer buf, int x, int z, Column col) {
        if (col.underwater()) return;
        int h = col.height;
        for (int[] f : fields) {
            if (x < f[0] || x > f[2] || z < f[1] || z > f[3]) continue;
            if (col.road >= 0 || col.slope > 2) return;
            int row = f[5] == 1 ? z - f[1] : x - f[0];
            buf.set(x, h + 1, z, Blocks.AIR);
            if (x == f[0] || x == f[2] || z == f[1] || z == f[3]) {
                buf.set(x, h, z, Blocks.DIRT_PATH);
            } else if (row % 9 == 4) {
                buf.set(x, h, z, Blocks.WATER);
            } else {
                buf.set(x, h, z, Blocks.FARMLAND);
                buf.set(x, h + 1, z, f[4]);
            }
            return;
        }
        for (House house : houses) {
            if (house.covers(x, z)) {
                house.column(buf, x, z, h);
                return;
            }
        }
        double dc = Math.hypot(x - cx, z - cz);
        if (dc <= 2.2) {
            if (dc <= 0.8) {
                buf.fill(x, h - 4, h, z, Blocks.WATER);
            } else {
                buf.set(x, h, z, Blocks.COBBLE);
                buf.set(x, h + 1, z, Blocks.COBBLE_WALL);
            }
            return;
        }
        if (dc <= 6) {
            buf.set(x, h, z, Blocks.DIRT_PATH);
            buf.set(x, h + 1, z, Blocks.AIR);
            return;
        }
        for (House house : houses) {
            int[] s = house.doorstep();
            if (Mth.segDist(x, z, cx, cz, s[0], s[1], null) <= 1.0) {
                if (buf.get(x, h, z) == Blocks.GRASS) {
                    buf.set(x, h, z, Blocks.DIRT_PATH);
                    buf.set(x, h + 1, z, Blocks.AIR);
                }
                return;
            }
        }
        if (dc < radius && Hash.unit(Hash.of(seed, x, z)) < 0.004) {
            buf.set(x, h + 1, z, Blocks.HAY);
        }
    }
}
