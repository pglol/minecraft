package com.pglol.aotworld.core;

/**
 * A placed structure. Features are rendered column by column so that any
 * chunk can be generated independently: {@link #column} is called for every
 * (x, z) inside the bounding box of the chunk being generated.
 */
public abstract class Feature {
    public final int minX, minZ, maxX, maxZ;

    protected Feature(int minX, int minZ, int maxX, int maxZ) {
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
    }

    public final boolean intersects(int x0, int z0, int x1, int z1) {
        return x1 >= minX && x0 <= maxX && z1 >= minZ && z0 <= maxZ;
    }

    public final boolean inBox(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /** Render order; higher layers draw later and win. */
    public int layer() {
        return 0;
    }

    /** True where natural decoration (trees, flowers) must stay away. */
    public abstract boolean occupies(int x, int z);

    public abstract void column(ChunkBuffer buf, int x, int z, Column col);
}
