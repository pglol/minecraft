package com.pglol.aotworld.core;

/**
 * Terrain heights, water, surface materials and biomes. Pure functions of
 * (x, z) so any chunk can be generated in isolation.
 */
public final class Terrain {
    public static final String[] BIOMES = {
        "ocean", "deep_ocean", "beach", "river", "plains", "forest", "birch_forest", "dark_forest",
        "taiga", "meadow", "windswept_hills", "snowy_slopes", "stony_shore", "sunflower_plains", "old_growth_birch_forest", "desert"
    };
    public static final int B_OCEAN = 0, B_DEEP_OCEAN = 1, B_BEACH = 2, B_RIVER = 3, B_PLAINS = 4, B_FOREST = 5,
        B_BIRCH = 6, B_DARK_FOREST = 7, B_TAIGA = 8, B_MEADOW = 9, B_HILLS = 10, B_SNOWY = 11, B_STONY_SHORE = 12,
        B_SUNFLOWER = 13, B_OLD_BIRCH = 14, B_DESERT = 15;

    private final Atlas atlas;
    private final Noise rolling, hills, ridges, seabed, forestN, surfaceN;
    private Villages villages;

    public Terrain(Atlas atlas) {
        this.atlas = atlas;
        long s = atlas.spec.seed;
        rolling = new Noise(Hash.of(s, 21));
        hills = new Noise(Hash.of(s, 22));
        ridges = new Noise(Hash.of(s, 23));
        seabed = new Noise(Hash.of(s, 24));
        forestN = new Noise(Hash.of(s, 25));
        surfaceN = new Noise(Hash.of(s, 26));
        for (Atlas.Site site : atlas.sites) {
            if (site.flatten > 0 && Double.isNaN(site.target)) {
                site.target = Math.max(WorldSpec.SEA + 3, heightNoFlatten(site.x, site.z));
            }
        }
    }

    void setVillages(Villages villages) {
        this.villages = villages;
    }

    /** Domain warp so ranges and barrens get ragged edges instead of ellipses. */
    private double warpX(double x, double z) {
        return x + surfaceN.fbm(x / 2200.0 + 13, z / 2200.0, 3) * 900;
    }

    private double warpZ(double x, double z) {
        return z + surfaceN.fbm(x / 2200.0, z / 2200.0 - 29, 3) * 900;
    }

    public double mountainMask(double x, double z) {
        double wx = warpX(x, z), wz = warpZ(x, z);
        double m = 0;
        for (Atlas.Range r : atlas.ranges) m = Math.max(m, r.mask(wx, wz));
        return m;
    }

    private double rawLand(double x, double z) {
        double roll = rolling.fbm(x / 280.0, z / 280.0, 3);
        double hill = hills.fbm(x / 1100.0, z / 1100.0, 4);
        double h = 71 + roll * 4 + Math.max(0, hill - 0.05) * 45;
        double wx = warpX(x, z), wz = warpZ(x, z);
        for (Atlas.Range r : atlas.ranges) {
            double m = r.mask(wx, wz);
            if (m <= 0) continue;
            double ridge = ridges.ridged(x / 700.0, z / 700.0, 5);
            h += m * r.peak * (0.3 + 0.7 * ridge);
        }
        return h;
    }

    private double heightNoFlatten(double x, double z) {
        double sd = atlas.landSD(x, z);
        if (sd < 0) return oceanFloor(x, z, sd);
        double t = Mth.smoothstep(0, 320, sd);
        return 63.5 + (rawLand(x, z) - 63.5) * t;
    }

    private double oceanFloor(double x, double z, double sd) {
        return 62 - Math.min(28, -sd / 9.0) + seabed.sample(x / 90.0, z / 90.0) * 2;
    }

    /** Height before river carving, including all flattening. */
    private double baseHeight(double x, double z, double sd) {
        if (sd < 0) return oceanFloor(x, z, sd);
        double t = Mth.smoothstep(0, 320, sd);
        double h = 63.5 + (rawLand(x, z) - 63.5) * t;
        double f = atlas.wallFlatten(x, z);
        if (f > 0) h = Mth.lerp(f, h, WorldSpec.WALL_BASE);
        for (Atlas.Site s : atlas.sites) {
            if (s.flatten <= 0) continue;
            double d = Mth.dist(x, z, s.x, s.z) - s.flatten;
            if (d > 140) continue;
            double w = 1 - Mth.smoothstep(0, 140, d);
            h = Mth.lerp(w, h, s.target);
        }
        return h;
    }

    private final ThreadLocal<double[]> tmp = ThreadLocal.withInitial(() -> new double[2]);

    /** Final terrain height (top solid block), rivers included. */
    public int height(double x, double z) {
        double sd = atlas.landSD(x, z);
        double h = baseHeight(x, z, sd);
        double[] out = tmp.get();
        double d = atlas.riverIndex.query(x, z, out);
        if (d < River.Index.INFLUENCE) h = carveRiver(h, d, out[0]);
        return (int) Math.floor(h + 0.5);
    }

    private static double carveRiver(double h, double d, double level) {
        double hw = 7, valley = 48;
        if (d < hw) {
            double bed = level - 1 - 4 * (1 - (d / hw) * (d / hw));
            return Math.min(h, bed);
        }
        double target = level + 2;
        double t = Mth.smoothstep(0, valley, d - hw);
        if (h < target) return Math.max(h, level + 1);
        return target + (h - target) * t;
    }

    /** Fills a column description. */
    public void sample(int x, int z, Column c) {
        c.x = x;
        c.z = z;
        double sdP = atlas.paradisSD(x, z), sdM = atlas.marleySD(x, z);
        double sd = Math.max(sdP, sdM);
        c.sd = sd;
        c.landmass = sd < 0 ? Column.OCEAN : (sdP >= sdM ? Column.PARADIS : Column.MARLEY);
        double h = baseHeight(x, z, sd);
        double[] out = tmp.get();
        double rd = atlas.riverIndex.query(x, z, out);
        c.river = false;
        c.water = Column.NO_WATER;
        if (rd < River.Index.INFLUENCE) {
            h = carveRiver(h, rd, out[0]);
            if (rd < 7) {
                c.river = true;
                c.water = (int) Math.floor(out[0]);
            }
        }
        int hi = (int) Math.floor(h + 0.5);
        c.height = hi;
        if (hi < WorldSpec.SEA && c.water == Column.NO_WATER) c.water = WorldSpec.SEA;
        if (c.water != Column.NO_WATER && c.water <= hi) c.water = Column.NO_WATER;

        c.mountain = mountainMask(x, z);
        double fn = forestN.fbm(x / 1100.0, z / 1100.0, 3) + forestN.sample(x / 160.0, z / 160.0) * 0.15;
        int ring = atlas.ring(x, z);
        double forestBias = c.landmass == Column.MARLEY ? 0.0 : (ring == 3 ? 0.15 : (ring == 2 ? 0.05 : -0.05));
        c.forest = Mth.smoothstep(0.12, 0.35, fn + forestBias);

        c.road = -1;
        if (c.landmass != Column.OCEAN && !c.underwater() || c.river) {
            c.road = atlas.roadDistance(x, z, c.landmass);
            if (c.road < 0 && villages != null) c.road = villages.roadDistance(x, z);
        }

        // Surface.
        double sn = surfaceN.sample(x / 24.0, z / 24.0);
        if (c.underwater()) {
            int depth = c.water - hi;
            if (c.river) c.surface = sn > 0.2 ? Blocks.GRAVEL : (sn < -0.4 ? Blocks.CLAY : Blocks.SAND);
            else if (depth < 5) c.surface = Blocks.SAND;
            else c.surface = sn > 0.35 ? Blocks.CLAY : (sn > -0.2 ? Blocks.SAND : Blocks.GRAVEL);
            c.sub = c.surface == Blocks.CLAY ? Blocks.CLAY : Blocks.SAND;
        } else if (sd < 26 && hi <= 66 && !c.river) {
            c.surface = Blocks.SAND;
            c.sub = Blocks.SANDSTONE;
        } else if (atlas.desert(warpX(x, z), warpZ(x, z)) > 0.5 && !c.river && c.landmass == Column.PARADIS) {
            c.surface = Blocks.SAND;
            c.sub = Blocks.SANDSTONE;
        } else if (hi > 172) {
            c.surface = Blocks.SNOW_BLOCK;
            c.sub = Blocks.STONE;
        } else {
            c.surface = Blocks.GRASS;
            c.sub = Blocks.DIRT;
        }

        // Biome.
        if (c.underwater() && !c.river) {
            c.biome = c.water - hi > 22 ? B_DEEP_OCEAN : B_OCEAN;
        } else if (c.river) {
            c.biome = B_RIVER;
        } else if (c.surface == Blocks.SAND && sd >= 26) {
            c.biome = B_DESERT;
        } else if (c.surface == Blocks.SAND) {
            c.biome = B_BEACH;
        } else if (hi > 165) {
            c.biome = B_SNOWY;
        } else if (c.mountain > 0.45 && hi > 110) {
            c.biome = B_HILLS;
        } else if (c.forest > 0.5) {
            double v = forestN.sample(x / 700.0 + 40, z / 700.0);
            if (c.landmass == Column.PARADIS && z < -atlas.maria.radius * 0.95) c.biome = B_TAIGA;
            else c.biome = v > 0.35 ? B_BIRCH : (v < -0.45 ? B_DARK_FOREST : B_FOREST);
        } else if (hi > 95) {
            c.biome = B_MEADOW;
        } else {
            c.biome = sn > 0.55 ? B_SUNFLOWER : B_PLAINS;
        }
    }

    /** Cheap biome lookup for the biome provider (no roads, rougher surface logic). */
    public int biome(int x, int z, Column scratch) {
        sample(x, z, scratch);
        return scratch.biome;
    }
}
