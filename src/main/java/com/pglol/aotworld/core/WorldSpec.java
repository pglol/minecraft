package com.pglol.aotworld.core;

/** Global constants and the horizontal scale of the map. */
public final class WorldSpec {
    public static final int SEA = 63;
    /** Ground level the three Walls (and their districts) are built on. */
    public static final int WALL_BASE = 72;
    /** Canon wall height is 50 m; vertical scale is always 1:1. */
    public static final int WALL_HEIGHT = 50;
    public static final int WALL_TOP = WALL_BASE + WALL_HEIGHT;
    /** Wall half-thickness: walls are 9 blocks thick. */
    public static final int WALL_HALF = 4;
    public static final int GATE_HALF = 6;

    public final long seed;
    /** Horizontal blocks per canon kilometre. 20 = 1:50, 10 = 1:100. */
    public final double blocksPerKm;
    /** Radial squash applied to the land outside Wall Maria (1 = as large as on the reference map). */
    public final double islandScale;

    public WorldSpec(long seed, double blocksPerKm, double islandScale) {
        this.seed = seed;
        this.blocksPerKm = blocksPerKm;
        this.islandScale = islandScale;
    }

    public double km(double km) {
        return km * blocksPerKm;
    }
}
