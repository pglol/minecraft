package com.pglol.aotworld.core.build;

import com.pglol.aotworld.core.ChunkBuffer;
import com.pglol.aotworld.core.Column;
import com.pglol.aotworld.core.Feature;

/** A feature wrapping a {@link TownGrid}; subclasses add landmarks via {@link #extra}. */
public class TownFeature extends Feature implements StableOwner {
    protected final TownGrid grid;

    public TownFeature(TownGrid grid, int minX, int minZ, int maxX, int maxZ) {
        super(minX, minZ, maxX, maxZ);
        this.grid = grid;
    }

    private int[] stable;

    /** Chooses this town's stable lot; call once after construction. */
    public void guaranteeStable() {
        stable = grid.guaranteeStable();
    }

    @Override
    public int[] stableSpot() {
        return stable;
    }

    public TownGrid grid() {
        return grid;
    }

    @Override
    public int layer() {
        return 10;
    }

    @Override
    public boolean occupies(int x, int z) {
        return grid.shape.inside(x, z);
    }

    @Override
    public void column(ChunkBuffer buf, int x, int z, Column col) {
        if (grid.shape.inside(x, z) && !col.underwater()) grid.column(buf, x, z, col.height);
        extra(buf, x, z, col);
    }

    protected void extra(ChunkBuffer buf, int x, int z, Column col) {}
}
