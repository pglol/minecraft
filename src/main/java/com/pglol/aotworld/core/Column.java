package com.pglol.aotworld.core;

/** Per-column terrain information produced by {@link Terrain#sample}. Mutable and reused. */
public final class Column {
    public static final int NO_WATER = Integer.MIN_VALUE;
    public static final int OCEAN = 0, PARADIS = 1, MARLEY = 2;

    public int x, z;
    public int height;
    public int water = NO_WATER;
    public int surface, sub;
    public int biome;
    public boolean river, lake;
    /** Distance from the road centre line, or -1 when not on a road. */
    public double road = -1;
    public Road.Type roadType;
    public double roadAlong, roadSide;
    /** Nearest road within grading range (even when not on it): type and distance from its edge. */
    public Road.Type nearRoad;
    public double nearEdge;
    public int landmass;
    public double sd;
    public double forest;
    public double mountain;
    public int slope;

    public boolean underwater() {
        return water != NO_WATER && water > height;
    }
}
