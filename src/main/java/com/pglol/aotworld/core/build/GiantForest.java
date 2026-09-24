package com.pglol.aotworld.core.build;

import com.pglol.aotworld.core.AotWorld;
import com.pglol.aotworld.core.Atlas;
import com.pglol.aotworld.core.Blocks;
import com.pglol.aotworld.core.ChunkBuffer;
import com.pglol.aotworld.core.Column;
import com.pglol.aotworld.core.Feature;
import com.pglol.aotworld.core.Hash;
import com.pglol.aotworld.core.Mth;
import com.pglol.aotworld.core.River;
import com.pglol.aotworld.core.Road;
import com.pglol.aotworld.core.RoadNetwork;
import com.pglol.aotworld.core.SpatialIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Forest of Giant Trees: the list of giant trees, a hidden treehouse
 * hideout high in the canopy joined by rope bridges, and a secluded village in
 * a clearing.
 */
public final class GiantForest {
    public static final class Tree {
        public final int x, z, ground, height;
        public final double radius;
        public final long seed;
        public int noBranchLo = Integer.MAX_VALUE, noBranchHi = Integer.MIN_VALUE;

        Tree(int x, int z, int ground, double radius, int height, long seed) {
            this.x = x;
            this.z = z;
            this.ground = ground;
            this.radius = radius;
            this.height = height;
            this.seed = seed;
        }
    }

    public static final int CELL = 22, REACH = 28;
    public final Atlas.Site site;
    public final List<Tree> trees = new ArrayList<>();
    public final SpatialIndex<Tree> index = new SpatialIndex<>(64);
    public final int groveX, groveZ, groveR = 46;
    public final Village grove;
    public final Hideout hideout;
    public final List<Plot> plots = new ArrayList<>();

    public GiantForest(AotWorld world, RoadNetwork roads, long seed) {
        site = world.atlas.site(Atlas.Kind.GIANT_FOREST);
        double len = Math.hypot(site.x, site.z);
        double ux = site.x / len, uz = site.z / len;
        groveX = (int) (site.x - ux * site.radius * 0.5);
        groveZ = (int) (site.z - uz * site.radius * 0.5);
        int hx = (int) (site.x + ux * site.radius * 0.5), hz = (int) (site.z + uz * site.radius * 0.5);

        double[] tmp = new double[1];
        for (int gx = Math.floorDiv(site.x - site.radius, CELL); gx <= Math.floorDiv(site.x + site.radius, CELL); gx++) {
            for (int gz = Math.floorDiv(site.z - site.radius, CELL); gz <= Math.floorDiv(site.z + site.radius, CELL); gz++) {
                long h = Hash.of(seed ^ 0x61A7, gx, gz);
                int tx = gx * CELL + Hash.range(h, 3, CELL - 4);
                int tz = gz * CELL + Hash.range(Hash.mix(h + 1), 3, CELL - 4);
                if (Math.hypot(tx - site.x, tz - site.z) > site.radius - 12) continue;
                if (Math.hypot(tx - groveX, tz - groveZ) < groveR + 8) continue;
                if (world.atlas.riverIndex.query(tx, tz, tmp) < 16) continue;
                if (roads.clearance(tx, tz) < 10) continue;
                double radius = 2.5 + Hash.unit(Hash.mix(h + 2)) * 1.4;
                int height = Hash.range(Hash.mix(h + 3), 62, 88);
                trees.add(new Tree(tx, tz, world.terrain.naturalHeight(tx, tz), radius, height, Hash.mix(h + 4)));
            }
        }

        grove = new Village(world, "Hidden Grove", Village.Type.HIDDEN, groveX, groveZ, 40, Hash.of(seed, 91));

        // Hideout: the six giants nearest its centre, decks at one common height.
        List<Tree> near = new ArrayList<>(trees);
        near.sort(Comparator.comparingDouble(t -> Math.hypot(t.x - hx, t.z - hz)));
        List<Tree> deckTrees = new ArrayList<>(near.subList(0, Math.min(6, near.size())));
        int deckY = 0;
        for (Tree t : deckTrees) deckY = Math.max(deckY, t.ground + 28);
        for (Tree t : deckTrees) {
            t.noBranchLo = deckY - 5;
            t.noBranchHi = deckY + 10;
        }
        deckTrees.sort(Comparator.comparingDouble(t -> Math.atan2(t.z - hz, t.x - hx)));
        hideout = new Hideout(deckTrees, deckY, seed);
        for (int i = 0; i < deckTrees.size(); i++) {
            if (i % 2 == 0) continue;
            Tree t = deckTrees.get(i);
            Plot p = new Plot(Plot.Kind.TREEHOUSE, t.x - 7, t.z - 7, t.x + 7, t.z + 7, deckY, Style.S);
            plots.add(p);
        }
    }

    /** Hidden paths: grove to hideout, and grove to the nearest road. Trees in the way are removed. */
    public void addPaths(RoadNetwork roads, long seed) {
        List<Road> added = new ArrayList<>();
        Tree first = hideout.trees.get(0);
        added.add(Road.curve(Road.Type.PATH, groveX, groveZ, first.x + 9, first.z + 9, Hash.mix(seed + 5)));
        double[] p = roads.nearestPoint(groveX, groveZ, false, 1500);
        if (p != null) added.add(Road.curve(Road.Type.PATH, groveX, groveZ, p[0], p[1], Hash.mix(seed + 6)));
        for (Road r : added) roads.add(r);
        trees.removeIf(t -> !hideout.trees.contains(t) && nearAny(added, t));
        for (Tree t : trees) index.add(t, t.x - REACH, t.z - REACH, t.x + REACH, t.z + REACH);
    }

    private static boolean nearAny(List<Road> roads, Tree t) {
        for (Road r : roads) {
            for (int i = 0; i + 1 < r.xs.length; i++) {
                if (Mth.segDist(t.x, t.z, r.xs[i], r.zs[i], r.xs[i + 1], r.zs[i + 1], null) < t.radius + 6) return true;
            }
        }
        return false;
    }

    public boolean inClearing(int x, int z) {
        return Math.hypot(x - groveX, z - groveZ) < groveR;
    }

    /** Decks, cabins built around the trunks, and rope bridges between them. */
    public static final class Hideout extends Feature {
        public final List<Tree> trees;
        public final int deckY;
        private final House[] cabins;

        Hideout(List<Tree> trees, int deckY, long seed) {
            super(bx(trees, true) - 10, bz(trees, true) - 10, bx(trees, false) + 10, bz(trees, false) + 10);
            this.trees = trees;
            this.deckY = deckY;
            cabins = new House[trees.size()];
            for (int i = 0; i < trees.size(); i += 2) {
                Tree t = trees.get(i);
                cabins[i] = new House(t.x - 5, t.z - 5, t.x + 5, t.z + 5, true, deckY, 1, Style.CABIN[(i / 2) % 2], 1, false);
            }
        }

        private static int bx(List<Tree> ts, boolean min) {
            int v = min ? Integer.MAX_VALUE : Integer.MIN_VALUE;
            for (Tree t : ts) v = min ? Math.min(v, t.x) : Math.max(v, t.x);
            return v;
        }

        private static int bz(List<Tree> ts, boolean min) {
            int v = min ? Integer.MAX_VALUE : Integer.MIN_VALUE;
            for (Tree t : ts) v = min ? Math.min(v, t.z) : Math.max(v, t.z);
            return v;
        }

        @Override
        public int layer() {
            return 9;
        }

        @Override
        public boolean occupies(int x, int z) {
            return false;
        }

        private boolean onBridge(int x, int z, double maxD) {
            for (int i = 0; i < trees.size(); i++) {
                Tree a = trees.get(i), b = trees.get((i + 1) % trees.size());
                if (trees.size() < 3 && i == trees.size() - 1) break;
                if (Mth.segDist(x, z, a.x, a.z, b.x, b.z, null) <= maxD) return true;
            }
            return false;
        }

        @Override
        public void column(ChunkBuffer buf, int x, int z, Column col) {
            for (int i = 0; i < trees.size(); i++) {
                Tree t = trees.get(i);
                int lx = x - t.x, lz = z - t.z;
                if (Math.abs(lx) > 7 || Math.abs(lz) > 7) continue;
                buf.set(x, deckY, z, Blocks.SPRUCE_PLANKS);
                boolean edge = Math.abs(lx) == 7 || Math.abs(lz) == 7;
                if (edge && !onBridge(x, z, 1.6)) {
                    buf.set(x, deckY + 1, z, Blocks.SPRUCE_FENCE);
                    if (Math.abs(lx) == 7 && Math.abs(lz) == 7) buf.set(x, deckY + 2, z, Blocks.LANTERN);
                }
                if (lx == 6 && lz == 6) buf.fill(x, col.height + 1, deckY, z, Blocks.SCAFFOLDING);
                if (cabins[i] != null && cabins[i].covers(x, z)) cabins[i].column(buf, x, z, deckY);
                if (cabins[i] != null && lx == -6 && lz == 6) buf.lootChest(x, deckY + 1, z, "north", "minecraft:chests/village/village_fletcher");
                return;
            }
            if (onBridge(x, z, 1.5)) {
                buf.set(x, deckY, z, Blocks.SPRUCE_PLANKS);
            } else if (onBridge(x, z, 2.5)) {
                buf.set(x, deckY, z, Blocks.id("spruce_slab[type=top]"));
                buf.set(x, deckY + 1, z, Blocks.SPRUCE_FENCE);
            }
        }
    }
}
