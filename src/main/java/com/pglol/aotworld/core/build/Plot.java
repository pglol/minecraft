package com.pglol.aotworld.core.build;

import com.pglol.aotworld.core.Blocks;
import com.pglol.aotworld.core.ChunkBuffer;
import com.pglol.aotworld.core.Column;
import com.pglol.aotworld.core.Feature;

/**
 * An empty, fenced and levelled property plot waiting for a player house. The
 * fence rectangle is (x0,z0)-(x1,z1) inclusive; the buildable interior is one
 * block in from the fence. A gate and a numbered sign face the driveway.
 */
public final class Plot extends Feature {
    public enum Kind { TOWN, VILLAGE, MEADOW, FOREST, LAKESIDE, COAST, HILLTOP, MOUNTAIN, TREEHOUSE }

    public int id;
    public String region = "";
    public final Kind kind;
    public final int x0, z0, x1, z1, y;
    /** Side of the gate: Style.N / S / W / E. */
    public int gate;

    private static final int FENCE_X = Blocks.id("oak_fence[east=true,west=true]");
    private static final int FENCE_Z = Blocks.id("oak_fence[north=true,south=true]");
    private static final int[] GATE = {
        Blocks.id("oak_fence_gate[facing=north]"), Blocks.id("oak_fence_gate[facing=south]"),
        Blocks.id("oak_fence_gate[facing=west]"), Blocks.id("oak_fence_gate[facing=east]")
    };
    private static final int[] SIGN_ROT = {8, 0, 4, 12};

    public Plot(Kind kind, int x0, int z0, int x1, int z1, int y, int gate) {
        super(Math.min(x0, x1) - 3, Math.min(z0, z1) - 3, Math.max(x0, x1) + 3, Math.max(z0, z1) + 3);
        this.kind = kind;
        this.x0 = Math.min(x0, x1);
        this.z0 = Math.min(z0, z1);
        this.x1 = Math.max(x0, x1);
        this.z1 = Math.max(z0, z1);
        this.y = y;
        this.gate = gate;
    }

    public int width() {
        return x1 - x0 - 1;
    }

    public int depth() {
        return z1 - z0 - 1;
    }

    public int cx() {
        return (x0 + x1) / 2;
    }

    public int cz() {
        return (z0 + z1) / 2;
    }

    /** The block just outside the gate. */
    public int[] gateOut() {
        switch (gate) {
            case Style.N: return new int[] {cx(), z0 - 2};
            case Style.S: return new int[] {cx(), z1 + 2};
            case Style.W: return new int[] {x0 - 2, cz()};
            default: return new int[] {x1 + 2, cz()};
        }
    }

    public String sizeName() {
        int s = Math.max(width(), depth());
        return s <= 14 ? "Small" : s <= 20 ? "Medium" : s <= 28 ? "Large" : "Estate";
    }

    @Override
    public int layer() {
        return kind == Kind.TOWN ? 12 : 6;
    }

    @Override
    public boolean occupies(int x, int z) {
        return x >= x0 - 2 && x <= x1 + 2 && z >= z0 - 2 && z <= z1 + 2;
    }

    @Override
    public void column(ChunkBuffer buf, int x, int z, Column col) {
        if (kind == Kind.TREEHOUSE) return;
        int[] out = gateOut();
        int sx = out[0], sz = out[1];
        // Sign beside the gate, facing the visitor.
        int lx = (gate == Style.N || gate == Style.S) ? 2 : 0, lz = (gate == Style.W || gate == Style.E) ? 2 : 0;
        if (x == sx + lx && z == sz + lz && !col.underwater()) {
            buf.sign(x, col.height + 1, z, SIGN_ROT[gate], "Property", "#" + id, width() + " x " + depth(), sizeName());
        }
        if (x < x0 || x > x1 || z < z0 || z > z1 || col.underwater()) return;
        int h = col.height;
        buf.fill(x, h + 1, h + 4, z, Blocks.AIR);
        int top = buf.get(x, h, z);
        if (top != Blocks.GRASS && top != Blocks.SNOW_BLOCK) buf.set(x, h, z, Blocks.GRASS);
        boolean ex = x == x0 || x == x1, ez = z == z0 || z == z1;
        if (ex && ez) {
            buf.set(x, h + 1, z, Blocks.COBBLE_WALL);
            buf.set(x, h + 2, z, Blocks.LANTERN);
            return;
        }
        if (!ex && !ez) return;
        boolean onGateSide = (gate == Style.N && z == z0) || (gate == Style.S && z == z1)
            || (gate == Style.W && x == x0) || (gate == Style.E && x == x1);
        int along = ez ? x - cx() : z - cz();
        if (onGateSide && Math.abs(along) <= 1) {
            buf.set(x, h + 1, z, along == 0 ? GATE[gate] : Blocks.AIR);
            if (along == 0) buf.set(x, h, z, Blocks.DIRT_PATH);
            return;
        }
        buf.set(x, h + 1, z, ez ? FENCE_X : FENCE_Z);
    }
}
