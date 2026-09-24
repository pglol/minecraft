package com.pglol.aotworld.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Generates one chunk: terrain, water, roads, structures, vegetation and ores. */
public final class ChunkComposer {
    private final AotWorld world;
    private final Trees trees;

    private static final class Scratch {
        final Column[] cols = new Column[256];
        final int[] heights = new int[18 * 18];
        final Column tmp = new Column();

        Scratch() {
            for (int i = 0; i < 256; i++) cols[i] = new Column();
        }
    }

    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);

    ChunkComposer(AotWorld world) {
        this.world = world;
        this.trees = new Trees(world);
    }

    public void compose(int chunkX, int chunkZ, ChunkBuffer buf) {
        buf.reset(chunkX, chunkZ);
        Scratch s = scratch.get();
        int x0 = chunkX << 4, z0 = chunkZ << 4;
        Terrain terrain = world.terrain;
        long seed = world.spec.seed;

        for (int dz = 0; dz < 18; dz++) {
            for (int dx = 0; dx < 18; dx++) {
                if (dx >= 1 && dx <= 16 && dz >= 1 && dz <= 16) continue;
                s.heights[dz * 18 + dx] = terrain.height(x0 + dx - 1, z0 + dz - 1);
            }
        }
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                Column c = s.cols[lz * 16 + lx];
                terrain.sample(x0 + lx, z0 + lz, c);
                s.heights[(lz + 1) * 18 + lx + 1] = c.height;
            }
        }

        Atlas.Site gf = world.atlas.site(Atlas.Kind.GIANT_FOREST);
        byte[] biomes = buf.biomes();
        for (int q = 0; q < 16; q++) {
            Column c = s.cols[((q >> 2) * 4 + 1) * 16 + (q & 3) * 4 + 1];
            int b = c.biome;
            if (gf != null && Math.hypot(c.x - gf.x, c.z - gf.z) < gf.radius && !c.underwater()) b = Terrain.B_DARK_FOREST;
            biomes[q] = (byte) b;
        }

        // Terrain columns.
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                Column c = s.cols[lz * 16 + lx];
                int i = (lz + 1) * 18 + lx + 1;
                int h = c.height;
                int slope = Math.max(Math.max(Math.abs(s.heights[i - 1] - h), Math.abs(s.heights[i + 1] - h)),
                    Math.max(Math.abs(s.heights[i - 18] - h), Math.abs(s.heights[i + 18] - h)));
                c.slope = slope;
                int x = c.x, z = c.z;
                long hh = Hash.of(seed, x, z, 1);
                int surface = c.surface, sub = c.sub;
                if (!c.underwater() && surface == Blocks.GRASS && h > 88 && slope >= 3) {
                    surface = slope >= 5 || h > 130 ? (Hash.unit(hh) < 0.7 ? Blocks.STONE : Blocks.ANDESITE) : Blocks.COARSE_DIRT;
                    sub = Blocks.STONE;
                }
                boolean bridge = c.road >= 0 && c.underwater() && c.river;
                if (c.road >= 0 && !c.underwater()) {
                    double u = Hash.unit(hh >>> 8);
                    surface = u < 0.8 ? Blocks.DIRT_PATH : (u < 0.9 ? Blocks.COARSE_DIRT : Blocks.GRAVEL);
                    sub = Blocks.DIRT;
                }
                buf.set(x, ChunkBuffer.MIN_Y, z, Blocks.BEDROCK);
                int deep = Hash.range(hh, -3, 0);
                int stoneTop = h - 4;
                buf.fill(x, ChunkBuffer.MIN_Y + 1, Math.min(deep, stoneTop), z, Blocks.DEEPSLATE);
                buf.fill(x, deep + 1, stoneTop, z, Blocks.STONE);
                buf.fill(x, Math.max(stoneTop + 1, ChunkBuffer.MIN_Y + 1), h - 1, z, sub);
                buf.set(x, h, z, surface);
                if (c.underwater()) {
                    buf.fill(x, h + 1, c.water, z, Blocks.WATER);
                    if (!c.river && c.water - h >= 2 && Hash.unit(hh >>> 16) < 0.06) buf.set(x, h + 1, z, Blocks.SEAGRASS);
                    if (bridge) {
                        int deck = c.water + 2;
                        buf.set(x, deck, z, Blocks.SPRUCE_PLANKS);
                        if (c.road > 1.5) buf.set(x, deck + 1, z, Blocks.SPRUCE_FENCE);
                        if (c.road > 1.5 && Math.floorMod(x + z, 5) == 0) buf.fill(x, h + 1, deck - 1, z, Blocks.SPRUCE_LOG);
                    }
                }
            }
        }

        // Structures.
        List<Feature> features = world.featuresIn(x0, z0, x0 + 15, z0 + 15);
        for (Feature f : features) {
            int fx0 = Math.max(x0, f.minX), fx1 = Math.min(x0 + 15, f.maxX);
            int fz0 = Math.max(z0, f.minZ), fz1 = Math.min(z0 + 15, f.maxZ);
            for (int z = fz0; z <= fz1; z++) {
                for (int x = fx0; x <= fx1; x++) {
                    f.column(buf, x, z, s.cols[(z - z0) * 16 + (x - x0)]);
                }
            }
        }

        // Ground cover on untouched grass.
        for (Column c : s.cols) {
            int h = c.height;
            if (c.biome == Terrain.B_DESERT && buf.get(c.x, h, c.z) == Blocks.SAND && buf.get(c.x, h + 1, c.z) == Blocks.AIR) {
                double u = Hash.unit(Hash.of(seed, c.x, c.z, 3));
                if (u < 0.008) buf.set(c.x, h + 1, c.z, Blocks.id("dead_bush"));
                else if (u < 0.010) buf.fill(c.x, h + 1, h + 2, c.z, Blocks.id("cactus"));
                continue;
            }
            if (buf.get(c.x, h, c.z) != Blocks.GRASS || buf.get(c.x, h + 1, c.z) != Blocks.AIR) continue;
            double u = Hash.unit(Hash.of(seed, c.x, c.z, 2));
            if (c.forest > 0.5) {
                if (u < 0.10) buf.set(c.x, h + 1, c.z, Blocks.SHORT_GRASS);
                else if (u < 0.16) buf.set(c.x, h + 1, c.z, Blocks.FERN);
            } else if (u < 0.14) {
                buf.set(c.x, h + 1, c.z, Blocks.SHORT_GRASS);
            } else if (u < 0.152) {
                int[] flowers = {Blocks.DANDELION, Blocks.POPPY, Blocks.CORNFLOWER, Blocks.OXEYE, Blocks.AZURE_BLUET};
                buf.set(c.x, h + 1, c.z, flowers[Hash.range(Hash.mix((long) c.x * 31 + c.z), 0, 4)]);
            }
        }

        trees.place(buf, features, s.tmp);
        ores(buf, x0, z0, seed);
    }

    private static void ores(ChunkBuffer buf, int x0, int z0, long seed) {
        long h = Hash.of(seed ^ 0x0E5, x0, z0);
        for (int i = 0; i < 22; i++) {
            long v = Hash.of(h, i);
            int x = x0 + Hash.range(v, 1, 14), z = z0 + Hash.range(Hash.mix(v + 1), 1, 14);
            int y = Hash.range(Hash.mix(v + 2), -58, 70);
            double kind = Hash.unit(Hash.mix(v + 3));
            int ore, deepOre;
            if (kind < 0.38) { if (y < 0) continue; ore = Blocks.COAL_ORE; deepOre = ore; }
            else if (kind < 0.64) { ore = Blocks.IRON_ORE; deepOre = Blocks.DEEPSLATE_IRON_ORE; }
            else if (kind < 0.8) { if (y < 0) continue; ore = Blocks.COPPER_ORE; deepOre = ore; }
            else if (kind < 0.89) { if (y > 30) continue; ore = Blocks.GOLD_ORE; deepOre = Blocks.DEEPSLATE_GOLD_ORE; }
            else if (kind < 0.96) { if (y > 0) continue; ore = Blocks.DEEPSLATE_REDSTONE_ORE; deepOre = ore; }
            else { if (y > -30) continue; ore = Blocks.DEEPSLATE_DIAMOND_ORE; deepOre = ore; }
            int size = Hash.range(Hash.mix(v + 4), 3, 8);
            for (int k = 0; k < size; k++) {
                long w = Hash.of(v, k);
                int bx = x + Hash.range(w, -1, 1), by = y + Hash.range(Hash.mix(w + 1), -1, 1), bz = z + Hash.range(Hash.mix(w + 2), -1, 1);
                int cur = buf.get(bx, by, bz);
                if (cur == Blocks.STONE) buf.set(bx, by, bz, ore);
                else if (cur == Blocks.DEEPSLATE) buf.set(bx, by, bz, deepOre);
            }
        }
    }

    static List<Feature> sortByLayer(List<Feature> in) {
        List<Feature> out = new ArrayList<>(in);
        out.sort(Comparator.comparingInt(Feature::layer));
        return out;
    }
}
