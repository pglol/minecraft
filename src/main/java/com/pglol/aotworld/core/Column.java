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
    public boolean river;
    /** Distance from the road centre line, or -1 when not on a road. */
    public double road = -1;
    public int landmass;
    public double sd;
    public double forest;
    public double mountain;
    public int slope;

    public boolean underwater() {
        return water != NO_WATER && water > height;
    }
}
