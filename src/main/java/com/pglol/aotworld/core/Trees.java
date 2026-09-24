package com.pglol.aotworld.core;

import com.pglol.aotworld.core.build.GiantForest;

/** Ordinary trees plus the 80-block giants of the Forest of Giant Trees. */
final class Trees {
    private static final int CELL = 8, MARGIN = 5;
    private static final int BIG_CELL = 17, BIG_REACH = 14;
    private final Noise groveNoise;
    private static final int GIANT_REACH = GiantForest.REACH;
    private static final int GIANT_BARK = Blocks.id("spruce_wood[axis=y]");
    private static final int GIANT_BRANCH_X = Blocks.id("spruce_wood[axis=x]");
    private static final int GIANT_BRANCH_Z = Blocks.id("spruce_wood[axis=z]");

    private final AotWorld world;
    private final long seed;
    private final Atlas.Site giant;
    private final GiantForest forest;

    Trees(AotWorld world) {
        this.world = world;
        this.seed = Hash.of(world.spec.seed, 0x7EE5);
        this.giant = world.atlas.site(Atlas.Kind.GIANT_FOREST);
        this.forest = world.giantForest;
        this.groveNoise = new Noise(Hash.of(world.spec.seed, 0x6207E));
    }

    void place(ChunkBuffer buf, java.util.List<Feature> features, Column scratch) {
        int x0 = buf.x0(), z0 = buf.z0();
        // Ordinary trees.
        for (int gx = Math.floorDiv(x0 - MARGIN, CELL); gx <= Math.floorDiv(x0 + 15 + MARGIN, CELL); gx++) {
            for (int gz = Math.floorDiv(z0 - MARGIN, CELL); gz <= Math.floorDiv(z0 + 15 + MARGIN, CELL); gz++) {
                long h = Hash.of(seed, gx, gz);
                double roll = Hash.unit(Hash.mix(h + 7));
                if (roll > 0.8) continue;
                int tx = gx * CELL + Hash.range(h, 0, CELL - 1);
                int tz = gz * CELL + Hash.range(Hash.mix(h + 1), 0, CELL - 1);
                if (giant != null && Math.hypot(tx - giant.x, tz - giant.z) < giant.radius) continue;
                double grove = grove(tx, tz);
                world.terrain.sample(tx, tz, scratch);
                if (!treeSpot(scratch, 5)) continue;
                double density = 0.025 + scratch.forest * 0.7;
                if (scratch.height > 150 || scratch.mountain > 0.6) density *= 0.3;
                density *= 1 - 0.85 * grove;
                if (roll >= density) continue;
                if (occupied(features, tx, tz)) continue;
                tree(buf, tx, scratch.height, tz, scratch, Hash.mix(h + 3));
            }
        }
        // Big, widely spaced trees: open groves for ODM gear, and lone giants in the fields.
        for (int gx = Math.floorDiv(x0 - BIG_REACH, BIG_CELL); gx <= Math.floorDiv(x0 + 15 + BIG_REACH, BIG_CELL); gx++) {
            for (int gz = Math.floorDiv(z0 - BIG_REACH, BIG_CELL); gz <= Math.floorDiv(z0 + 15 + BIG_REACH, BIG_CELL); gz++) {
                long h = Hash.of(seed ^ 0xB16, gx, gz);
                int tx = gx * BIG_CELL + Hash.range(h, 2, BIG_CELL - 3);
                int tz = gz * BIG_CELL + Hash.range(Hash.mix(h + 1), 2, BIG_CELL - 3);
                if (giant != null && Math.hypot(tx - giant.x, tz - giant.z) < giant.radius + 20) continue;
                double chance = 0.75 * grove(tx, tz) + 0.04;
                if (Hash.unit(Hash.mix(h + 2)) >= chance) continue;
                world.terrain.sample(tx, tz, scratch);
                if (!treeSpot(scratch, 3) || scratch.slope > 3 || scratch.height > 140) continue;
                if (occupied(features, tx, tz) || occupied(features, tx + 8, tz) || occupied(features, tx - 8, tz)
                    || occupied(features, tx, tz + 8) || occupied(features, tx, tz - 8)) continue;
                bigTree(buf, tx, scratch.height, tz, Hash.mix(h + 3));
            }
        }
        if (giant != null && Math.abs(x0 + 8 - giant.x) < giant.radius + 60 && Math.abs(z0 + 8 - giant.z) < giant.radius + 60) {
            giants(buf, x0, z0);
        }
    }

    /** Dry grass, away from roads by at least `clear` blocks from the road edge. */
    private static boolean treeSpot(Column c, double clear) {
        if (c.underwater() || c.river || c.lake || c.road >= 0 || c.surface != Blocks.GRASS) return false;
        return c.nearRoad == null || c.nearEdge >= clear;
    }

    /** 0..1: open groves of tall, well-spaced trees. */
    private double grove(double x, double z) {
        return Mth.smoothstep(0.2, 0.45, groveNoise.fbm(x / 900.0, z / 900.0, 2));
    }

    private boolean occupied(java.util.List<Feature> features, int x, int z) {
        for (Feature f : features) if (f.inBox(x, z) && f.occupies(x, z)) return true;
        return false;
    }

    private void tree(ChunkBuffer buf, int x, int ground, int z, Column c, long h) {
        double u = Hash.unit(h);
        int type; // 0 oak, 1 birch, 2 spruce, 3 dark oak
        if (c.biome == Terrain.B_TAIGA || c.height > 120) type = 2;
        else if (c.biome == Terrain.B_BIRCH) type = u < 0.8 ? 1 : 0;
        else if (c.biome == Terrain.B_DARK_FOREST) type = u < 0.7 ? 3 : 0;
        else type = u < 0.68 ? 0 : (u < 0.88 ? 1 : 2);
        if (type == 2) {
            spruce(buf, x, ground, z, h);
            return;
        }
        int log = type == 1 ? Blocks.BIRCH_LOG : (type == 3 ? Blocks.DARK_OAK_LOG : Blocks.OAK_LOG);
        int leaves = type == 1 ? Blocks.BIRCH_LEAVES : (type == 3 ? Blocks.DARK_OAK_LEAVES : Blocks.OAK_LEAVES);
        int height = Hash.range(Hash.mix(h + 1), type == 1 ? 8 : 7, type == 1 ? 11 : 10) + (type == 3 ? 1 : 0);
        int top = ground + height;
        for (int dy = -4; dy <= 1; dy++) {
            int r = dy >= 0 ? 1 : (dy <= -3 ? 3 : 2);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int md = Math.abs(dx) + Math.abs(dz);
                    if (md > r + 1) continue;
                    boolean corner = Math.abs(dx) == r && Math.abs(dz) == r;
                    if (corner && (dy >= 0 || Hash.unit(Hash.of(h, dx, dy, dz)) < 0.6)) continue;
                    buf.setIfAir(x + dx, top + dy, z + dz, leaves);
                }
            }
        }
        for (int y = ground + 1; y < top; y++) buf.set(x, y, z, log);
        buf.set(x, ground, z, Blocks.DIRT);
    }

    private void spruce(ChunkBuffer buf, int x, int ground, int z, long h) {
        int height = Hash.range(Hash.mix(h + 1), 11, 18);
        int top = ground + height;
        for (int y = ground + 4; y <= top + 1; y++) {
            int fromTop = top + 1 - y;
            int r = fromTop <= 1 ? 0 : (fromTop % 2 == 0 ? Math.min(3, 1 + fromTop / 3) : Math.max(1, fromTop / 3));
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > r + (r > 1 ? 1 : 0)) continue;
                    buf.setIfAir(x + dx, y, z + dz, Blocks.SPRUCE_LEAVES);
                }
            }
        }
        for (int y = ground + 1; y <= top; y++) buf.set(x, y, z, Blocks.SPRUCE_LOG);
        buf.set(x, ground, z, Blocks.DIRT);
    }

    /** A 2x2 trunk tree, 20-30 tall, with high branches and a broad crown: open underneath for ODM gear. */
    private void bigTree(ChunkBuffer buf, int x, int ground, int z, long h) {
        boolean dark = Hash.unit(h) < 0.35;
        int log = dark ? Blocks.DARK_OAK_LOG : Blocks.OAK_LOG;
        int logX = dark ? Blocks.id("dark_oak_log[axis=x]") : Blocks.OAK_LOG_X;
        int logZ = dark ? Blocks.id("dark_oak_log[axis=z]") : Blocks.OAK_LOG_Z;
        int leaves = dark ? Blocks.DARK_OAK_LEAVES : Blocks.OAK_LEAVES;
        int height = Hash.range(Hash.mix(h + 1), 20, 30);
        int top = ground + height;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                buf.fill(x + dx, ground - 1, top - 2, z + dz, log);
            }
        }
        int arms = Hash.range(Hash.mix(h + 2), 3, 5);
        for (int i = 0; i < arms; i++) {
            long ah = Hash.of(h, i);
            double ang = (i + Hash.unit(ah) * 0.6) * 2 * Math.PI / arms;
            int ay = ground + (int) (height * (0.55 + 0.25 * Hash.unit(Hash.mix(ah + 1))));
            int len = Hash.range(Hash.mix(ah + 2), 4, 7);
            double dx = Math.cos(ang), dz = Math.sin(ang);
            int ex = 0, ez = 0, ey = ay;
            for (int s = 1; s <= len; s++) {
                ex = x + (int) Math.round(dx * (s + 0.5));
                ez = z + (int) Math.round(dz * (s + 0.5));
                ey = ay + s / 3;
                buf.set(ex, ey, ez, Math.abs(dx) > Math.abs(dz) ? logX : logZ);
            }
            crown(buf, ex, ey + 1, ez, 3.5, leaves, ah);
        }
        crown(buf, x, top, z, 6, leaves, h);
    }

    private static void crown(ChunkBuffer buf, int cx, int cy, int cz, double r, int leaves, long h) {
        int ir = (int) Math.ceil(r);
        for (int dx = -ir; dx <= ir + 1; dx++) {
            for (int dz = -ir; dz <= ir + 1; dz++) {
                double d = Math.hypot(dx - 0.5, dz - 0.5);
                if (d > r) continue;
                double vh = Math.sqrt(r * r - d * d) * 0.6;
                for (int y = (int) Math.floor(cy - vh * 0.7); y <= (int) Math.ceil(cy + vh); y++) {
                    if (Hash.unit(Hash.of(h, cx + dx, y, cz + dz)) < 0.1) continue;
                    buf.setIfAir(cx + dx, y, cz + dz, leaves);
                }
            }
        }
    }

    // ---- Giant trees -------------------------------------------------------------------

    private void giants(ChunkBuffer buf, int x0, int z0) {
        java.util.Set<GiantForest.Tree> done = new java.util.HashSet<>();
        for (int cx = x0; cx <= x0 + 15; cx += 15) {
            for (int cz = z0; cz <= z0 + 15; cz += 15) {
                for (GiantForest.Tree t : forest.index.at(cx, cz)) {
                    if (done.add(t)) giant(buf, t);
                }
            }
        }
    }

    private void giant(ChunkBuffer buf, GiantForest.Tree tree) {
        int tx = tree.x, tz = tree.z, ground = tree.ground;
        long h = tree.seed;
        double radius = tree.radius;
        int height = tree.height;
        int top = ground + height;
        int x0 = buf.x0(), z0 = buf.z0();

        int nb = Hash.range(Hash.mix(h + 2), 5, 7);
        java.util.List<double[]> branches = new java.util.ArrayList<>();
        for (int i = 0; i < nb; i++) {
            long bh = Hash.of(h, i);
            double ang = Hash.unit(bh) * Math.PI * 2;
            int y = ground + 22 + (int) (Hash.unit(Hash.mix(bh + 1)) * (height - 38));
            double len = 9 + Hash.unit(Hash.mix(bh + 2)) * 8;
            if (y + len * 0.35 + 8 >= tree.noBranchLo && y - 2 <= tree.noBranchHi) continue;
            branches.add(new double[] {Math.cos(ang), Math.sin(ang), y, len});
        }

        for (int x = Math.max(x0, tx - GIANT_REACH); x <= Math.min(x0 + 15, tx + GIANT_REACH); x++) {
            for (int z = Math.max(z0, tz - GIANT_REACH); z <= Math.min(z0 + 15, tz + GIANT_REACH); z++) {
                double dx = x - tx, dz = z - tz;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d <= radius + 3.5) {
                    for (int y = ground - 2; y <= top; y++) {
                        double flare = Math.max(0, 1 - (y - ground) / 9.0);
                        double r = radius + 3.5 * flare * flare;
                        if (d <= r) buf.set(x, y, z, GIANT_BARK);
                    }
                }
                for (double[] b : branches) {
                    double ex = tx + b[0] * (radius + b[3]), ez = tz + b[1] * (radius + b[3]);
                    double[] t = new double[1];
                    double bd = Mth.segDist(x, z, tx, tz, ex, ez, t);
                    double along = t[0] * (radius + b[3]);
                    if (bd <= 1.0 && along >= radius - 0.5) {
                        int by = (int) (b[2] + along * 0.35);
                        int id = Math.abs(b[0]) > Math.abs(b[1]) ? GIANT_BRANCH_X : GIANT_BRANCH_Z;
                        buf.set(x, by, z, id);
                        if (along < radius + 4) buf.set(x, by + 1, z, id);
                    }
                    double cy = b[2] + (radius + b[3]) * 0.35 + 2;
                    blob(buf, x, z, Mth.dist(x, z, ex, ez), cy, 5.5, h);
                }
                blob(buf, x, z, d, top + 2, 10, h);
            }
        }
    }

    private static void blob(ChunkBuffer buf, int x, int z, double d, double cy, double r, long h) {
        if (d > r) return;
        double vh = Math.sqrt(r * r - d * d) * 0.55;
        for (int y = (int) Math.floor(cy - vh); y <= (int) Math.ceil(cy + vh); y++) {
            if (Hash.unit(Hash.of(h, x, y, z)) < 0.12) continue;
            buf.setIfAir(x, y, z, Blocks.OAK_LEAVES);
        }
    }
}
