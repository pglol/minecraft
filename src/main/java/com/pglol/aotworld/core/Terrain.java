package com.pglol.aotworld.core;

import java.util.Collections;

/**
 * Terrain heights, water, surface materials and biomes. Pure functions of
 * (x, z) so any chunk can be generated in isolation.
 *
 * Height is built in layers: natural land (coast, hills, mountains, walls
 * levelled), then roads graded into the slope, pads levelled for buildings and
 * plots, lakes and finally rivers.
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
    private final Noise rolling, hills, ridges, seabed, forestN, surfaceN, terraceN, coastN;
    private volatile SpatialIndex<Pad> pads = new SpatialIndex<>(256);
    private volatile SpatialIndex<Lake> lakes = new SpatialIndex<>(256);
    private volatile RoadNetwork roads;

    public Terrain(Atlas atlas) {
        this.atlas = atlas;
        long s = atlas.spec.seed;
        rolling = new Noise(Hash.of(s, 21));
        hills = new Noise(Hash.of(s, 22));
        ridges = new Noise(Hash.of(s, 23));
        seabed = new Noise(Hash.of(s, 24));
        forestN = new Noise(Hash.of(s, 25));
        surfaceN = new Noise(Hash.of(s, 26));
        terraceN = new Noise(Hash.of(s, 27));
        coastN = new Noise(Hash.of(s, 28));
    }

    void setRoads(RoadNetwork roads) {
        this.roads = roads;
    }

    void setPads(SpatialIndex<Pad> pads) {
        this.pads = pads;
    }

    void setLakes(SpatialIndex<Lake> lakes) {
        this.lakes = lakes;
    }

    // ---- Natural land ------------------------------------------------------------------

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

    /** Hills grow taller away from the walls: gentle farmland inside, rugged wilds outside. */
    private double hillAmp(double x, double z) {
        double r = Math.sqrt(x * x + z * z);
        double t = Mth.smoothstep(atlas.maria.radius * 0.6, atlas.maria.radius * 1.3, r);
        return Mth.lerp(t, 34, 72);
    }

    private double rawLand(double x, double z, boolean smooth) {
        double roll = smooth ? 0 : rolling.fbm(x / 280.0, z / 280.0, 3);
        double hill = hills.fbm(x / 1100.0, z / 1100.0, smooth ? 2 : 4);
        double h = 71 + roll * 4 + Math.max(0, hill - 0.05) * hillAmp(x, z);
        double wx = warpX(x, z), wz = warpZ(x, z);
        for (Atlas.Range r : atlas.ranges) {
            double m = r.mask(wx, wz);
            if (m <= 0) continue;
            double ridge = ridges.ridged(x / 700.0, z / 700.0, smooth ? 2 : 5);
            h += m * r.peak * (0.3 + 0.7 * ridge);
        }
        if (!smooth && h > 80) {
            // Stepped hillsides in patches.
            double tm = Mth.smoothstep(0.1, 0.45, terraceN.fbm(x / 1400.0, z / 1400.0, 2));
            if (tm > 0) {
                double step = 6, t = h / step, f = Math.floor(t);
                double hs = (f + Mth.smoothstep(0.3, 0.7, t - f)) * step;
                h = Mth.lerp(tm * 0.85, h, hs);
            }
        }
        return h;
    }

    private double oceanFloor(double x, double z, double sd) {
        return 62 - Math.min(28, -sd / 9.0) + seabed.sample(x / 90.0, z / 90.0) * 2;
    }

    /** 0 = gentle beach, 1 = rocky sea cliff. */
    public double cliff(double x, double z) {
        return Mth.smoothstep(0.25, 0.55, coastN.fbm(x / 1600.0, z / 1600.0, 2));
    }

    /** Beach width in blocks, varying along the shore. */
    private double beachWidth(double x, double z) {
        return 8 + 22 * (0.5 + 0.5 * coastN.sample(x / 260.0 + 31, z / 260.0));
    }

    private double naturalWithSd(double x, double z, double sd, boolean smooth) {
        if (sd < 0) return oceanFloor(x, z, sd);
        double cl = cliff(x, z);
        double ramp = Mth.lerp(cl, 320, 26);
        double t = Mth.smoothstep(0, ramp, sd);
        double h = 63.5 + (rawLand(x, z, smooth) - 63.5) * t;
        if (!smooth && t < 1) {
            // Break up long, even beach contours with small dunes and dips.
            h += (coastN.sample(x / 23.0, z / 23.0) * 0.9 + coastN.sample(x / 7.0, z / 7.0) * 0.35) * (1 - t) * Math.min(1, sd / 6);
        }
        double f = atlas.wallFlatten(x, z);
        if (f > 0) h = Mth.lerp(f, h, WorldSpec.WALL_BASE);
        return h;
    }

    /** Height of the untouched land (no roads, pads, lakes or rivers). */
    public int naturalHeight(double x, double z) {
        return (int) Math.floor(naturalWithSd(x, z, atlas.landSD(x, z), false) + 0.5);
    }

    public double natural(double x, double z) {
        return naturalWithSd(x, z, atlas.landSD(x, z), false);
    }

    // ---- Final height ------------------------------------------------------------------

    private final ThreadLocal<double[]> tmp = ThreadLocal.withInitial(() -> new double[2]);
    private final ThreadLocal<RoadNetwork.Hit> hits = ThreadLocal.withInitial(RoadNetwork.Hit::new);

    /** Returns the height; hit receives the nearest road, lakeOut[0] the lake level or NaN. */
    private double layered(double x, double z, double sd, RoadNetwork.Hit hit, double[] lakeOut) {
        double h = naturalWithSd(x, z, sd, false);
        lakeOut[0] = Double.NaN;
        if (sd >= 0) {
            if (roads != null && roads.query(x, z, hit)) {
                double hw = hit.road.type.halfWidth;
                double w = 1 - Mth.smoothstep(hw + 0.5, hw + RoadNetwork.GRADE, hit.dist);
                if (w > 0) h = Mth.lerp(w, h, naturalWithSd(x, z, sd, true));
            }
            for (Pad p : pads.at(x, z)) h = p.apply(x, z, h);
            for (Lake l : lakes.at(x, z)) {
                double n = l.norm(x, z);
                if (n < 1) {
                    double bed = l.level - 1 - l.depth * (1 - n * n);
                    h = Math.min(h, bed);
                    lakeOut[0] = l.level;
                } else {
                    double d = (n - 1) * l.r;
                    if (d < 16) {
                        if (h < l.level + 1) h = l.level + 1;
                        else h = Mth.lerp(Mth.smoothstep(0, 16, d), l.level + 1, h);
                    }
                }
            }
        } else if (roads != null) {
            roads.query(x, z, hit);
        }
        return h;
    }

    /** Final terrain height (top solid block), everything included. */
    public int height(double x, double z) {
        double sd = atlas.landSD(x, z);
        double[] out = tmp.get();
        double h = layered(x, z, sd, hits.get(), out);
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
        RoadNetwork.Hit hit = hits.get();
        double[] out = tmp.get();
        double h = layered(x, z, sd, hit, out);
        double lake = out[0];
        double rd = atlas.riverIndex.query(x, z, out);
        c.river = false;
        c.lake = false;
        c.water = Column.NO_WATER;
        if (!Double.isNaN(lake)) {
            c.lake = true;
            c.water = (int) lake;
        }
        if (rd < River.Index.INFLUENCE) {
            h = carveRiver(h, rd, out[0]);
            if (rd < 7) {
                c.river = true;
                c.lake = false;
                c.water = (int) Math.floor(out[0]);
            }
        }
        int hi = (int) Math.floor(h + 0.5);
        c.height = hi;
        if (hi < WorldSpec.SEA && c.water == Column.NO_WATER) c.water = WorldSpec.SEA;
        if (c.water != Column.NO_WATER && c.water <= hi) {
            c.water = Column.NO_WATER;
            c.lake = false;
        }

        c.mountain = mountainMask(x, z);
        double fn = forestN.fbm(x / 1100.0, z / 1100.0, 3) + forestN.sample(x / 160.0, z / 160.0) * 0.15;
        int ring = atlas.ring(x, z);
        double forestBias = c.landmass == Column.MARLEY ? 0.0 : (ring == 3 ? 0.15 : (ring == 2 ? 0.05 : -0.05));
        c.forest = Mth.smoothstep(0.12, 0.35, fn + forestBias);

        c.road = -1;
        c.roadType = null;
        c.nearRoad = null;
        boolean fresh = c.river || c.lake;
        if (hit.road != null) {
            c.nearRoad = hit.road.type;
            c.nearEdge = hit.dist - hit.road.type.halfWidth;
            c.roadAlong = hit.along;
            c.roadSide = hit.side;
        }
        if (hit.on() && (c.landmass != Column.OCEAN && !c.underwater() || fresh)) {
            c.road = hit.dist;
            c.roadType = hit.road.type;
            c.roadAlong = hit.along;
            c.roadSide = hit.side;
        }

        // Surface.
        double sn = surfaceN.sample(x / 24.0, z / 24.0);
        if (c.underwater()) {
            int depth = c.water - hi;
            if (fresh) c.surface = sn > 0.2 ? Blocks.GRAVEL : (sn < -0.4 ? Blocks.CLAY : Blocks.SAND);
            else if (depth < 5) c.surface = Blocks.SAND;
            else c.surface = sn > 0.35 ? Blocks.CLAY : (sn > -0.2 ? Blocks.SAND : Blocks.GRAVEL);
            c.sub = c.surface == Blocks.CLAY ? Blocks.CLAY : Blocks.SAND;
        } else if (sd < beachWidth(x, z) + 6 && hi <= 67 && !c.river && cliff(x, z) < 0.35) {
            double sh = coastN.sample(x / 90.0 - 11, z / 90.0);
            if (sh > 0.42) {
                c.surface = sn > 0.1 ? Blocks.GRAVEL : (sn > -0.3 ? Blocks.STONE : Blocks.ANDESITE);
                c.sub = Blocks.STONE;
            } else if (sd > beachWidth(x, z)) {
                c.surface = Blocks.GRASS;
                c.sub = Blocks.DIRT;
            } else {
                c.surface = Blocks.SAND;
                c.sub = Blocks.SANDSTONE;
            }
        } else if (sd < 40 && cliff(x, z) >= 0.35 && hi > 66) {
            c.surface = sn > 0.3 ? Blocks.GRASS : (sn > -0.2 ? Blocks.STONE : Blocks.ANDESITE);
            c.sub = Blocks.STONE;
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
        if (c.underwater() && !fresh) {
            c.biome = c.water - hi > 22 ? B_DEEP_OCEAN : B_OCEAN;
        } else if (c.river) {
            c.biome = B_RIVER;
        } else if (c.surface == Blocks.SAND && sd >= 40) {
            c.biome = B_DESERT;
        } else if (c.surface == Blocks.SAND) {
            c.biome = B_BEACH;
        } else if (sd < 40 && c.sub == Blocks.STONE && !c.underwater()) {
            c.biome = B_STONY_SHORE;
        } else if (hi > 165) {
            c.biome = B_SNOWY;
        } else if (c.mountain > 0.45 && hi > 110) {
            c.biome = B_HILLS;
        } else if (c.forest > 0.5) {
            double v = forestN.sample(x / 700.0 + 40, z / 700.0);
            if (c.landmass == Column.PARADIS && z + forestN.sample(x / 900.0, 7.7) * 900 < -atlas.maria.radius * 1.5) c.biome = B_TAIGA;
            else c.biome = v > 0.35 ? B_BIRCH : (v < -0.45 ? B_DARK_FOREST : B_FOREST);
        } else if (hi > 95) {
            c.biome = B_MEADOW;
        } else {
            c.biome = sn > 0.55 ? B_SUNFLOWER : B_PLAINS;
        }
    }

    static <T> Iterable<T> none() {
        return Collections.emptyList();
    }
}
