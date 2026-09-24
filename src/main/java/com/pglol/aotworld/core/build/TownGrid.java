package com.pglol.aotworld.core.build;

import com.pglol.aotworld.core.Blocks;
import com.pglol.aotworld.core.ChunkBuffer;
import com.pglol.aotworld.core.Hash;

import java.util.ArrayList;
import java.util.List;

/**
 * A street grid of city blocks, each split into four lots holding houses,
 * gardens, market stalls or wells. The grid is oriented along a cardinal axis
 * (ux, uz) from an origin; local coordinate "a" runs along that axis and "b"
 * across it, with a main avenue on b = 0.
 */
public final class TownGrid {
    public interface Shape {
        boolean inside(double x, double z);
    }

    public final int ox, oz, ux, uz, baseY;
    public final long seed;
    public final Shape shape;
    private final Style[] styles;
    private final int minFloors, maxFloors;
    private final int block, street, lot;
    private final int[] streetBlocks;
    private double plazaA = Double.NaN, plazaB, plazaR;
    private final List<int[]> exclusions = new ArrayList<>();
    private boolean lamps = true;

    public TownGrid(int ox, int oz, int dir, int baseY, long seed, Shape shape, Style[] styles,
                    int minFloors, int maxFloors, int block, int[] streetBlocks) {
        this.ox = ox;
        this.oz = oz;
        this.ux = com.pglol.aotworld.core.Atlas.DIR_X[dir];
        this.uz = com.pglol.aotworld.core.Atlas.DIR_Z[dir];
        this.baseY = baseY;
        this.seed = seed;
        this.shape = shape;
        this.styles = styles;
        this.minFloors = minFloors;
        this.maxFloors = maxFloors;
        this.block = block;
        this.street = 5;
        this.lot = (block - street - 1) / 2;
        this.streetBlocks = streetBlocks;
    }

    public TownGrid plaza(double a, double b, double r) {
        plazaA = a;
        plazaB = b;
        plazaR = r;
        return this;
    }

    public TownGrid exclude(int x0, int z0, int x1, int z1) {
        exclusions.add(new int[] {Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1)});
        return this;
    }

    public TownGrid noLamps() {
        lamps = false;
        return this;
    }

    public int localA(int x, int z) {
        return (x - ox) * ux + (z - oz) * uz;
    }

    public int localB(int x, int z) {
        return -(x - ox) * uz + (z - oz) * ux;
    }

    public int worldX(int a, int b) {
        return ox + a * ux - b * uz;
    }

    public int worldZ(int a, int b) {
        return oz + a * uz + b * ux;
    }

    public boolean inPlaza(int x, int z) {
        if (Double.isNaN(plazaA)) return false;
        double da = localA(x, z) - plazaA, db = localB(x, z) - plazaB;
        return da * da + db * db <= plazaR * plazaR;
    }

    /** Levels the ground to baseY and draws whatever occupies this column. */
    public void column(ChunkBuffer buf, int x, int z, int groundY) {
        if (groundY < baseY) buf.fill(x, groundY + 1, baseY - 1, z, Blocks.DIRT);
        if (groundY > baseY) buf.fill(x, baseY + 1, groundY, z, Blocks.AIR);

        int a = localA(x, z), b = localB(x, z);
        long h = Hash.of(seed, x, z);

        if (inPlaza(x, z)) {
            plazaColumn(buf, x, z, a, b, h);
            return;
        }

        int shift = street / 2;
        int ma = Math.floorMod(a, block), mb = Math.floorMod(b + shift, block);
        boolean avenue = Math.abs(b) <= 3;
        if (ma < street || mb < street || avenue) {
            buf.set(x, baseY, z, streetBlocks[Hash.range(h, 0, streetBlocks.length - 1)]);
            if (lamps && ma == street - 1 && mb == street - 1 && !avenue) {
                buf.fill(x, baseY + 1, baseY + 3, z, Blocks.SPRUCE_FENCE);
                buf.set(x, baseY + 4, z, Blocks.LANTERN);
            }
            return;
        }

        int ia = Math.floorDiv(a, block), ib = Math.floorDiv(b + shift, block);
        int ra = ma - street, rb = mb - street;
        if (ra == lot || rb == lot) {
            buf.set(x, baseY, z, Hash.unit(h) < 0.5 ? Blocks.GRAVEL : Blocks.COBBLE);
            return;
        }
        int qa = ra < lot ? 0 : 1, qb = rb < lot ? 0 : 1;
        int la0 = ia * block + street + qa * (lot + 1);
        int lb0 = ib * block + street - shift + qb * (lot + 1);
        int la1 = la0 + lot - 1, lb1 = lb0 + lot - 1;

        long lh = Hash.of(seed, ia * 2L + qa, ib * 2L + qb);
        boolean valid = lotValid(la0, lb0, la1, lb1);
        double u = Hash.unit(lh);
        if (!valid) {
            buf.set(x, baseY, z, Blocks.GRASS);
            return;
        }
        if (u < 0.8) {
            House house = house(qa, qb, la0, lb0, la1, lb1, lh);
            buf.set(x, baseY, z, Blocks.COBBLE);
            if (house.covers(x, z)) house.column(buf, x, z, baseY);
        } else if (u < 0.87) {
            garden(buf, x, z, a, b, la0, lb0, la1, lb1, h);
        } else if (u < 0.93) {
            stalls(buf, x, z, a, b, la0, lb0, h);
        } else {
            well(buf, x, z, a, b, la0, lb0, h);
        }
    }

    private boolean lotValid(int la0, int lb0, int la1, int lb1) {
        int[][] corners = {{la0 - 1, lb0 - 1}, {la1 + 1, lb0 - 1}, {la0 - 1, lb1 + 1}, {la1 + 1, lb1 + 1}};
        for (int[] c : corners) {
            if (!shape.inside(worldX(c[0], c[1]), worldZ(c[0], c[1]))) return false;
        }
        if (!Double.isNaN(plazaA)) {
            double ca = Math.max(la0, Math.min(plazaA, la1)), cb = Math.max(lb0, Math.min(plazaB, lb1));
            double da = ca - plazaA, db = cb - plazaB;
            if (da * da + db * db <= (plazaR + 2) * (plazaR + 2)) return false;
        }
        if (!exclusions.isEmpty()) {
            int x0 = Math.min(worldX(la0, lb0), worldX(la1, lb1)), x1 = Math.max(worldX(la0, lb0), worldX(la1, lb1));
            int z0 = Math.min(worldZ(la0, lb0), worldZ(la1, lb1)), z1 = Math.max(worldZ(la0, lb0), worldZ(la1, lb1));
            for (int[] e : exclusions) {
                if (x1 >= e[0] && x0 <= e[2] && z1 >= e[1] && z0 <= e[3]) return false;
            }
        }
        return true;
    }

    private House house(int qa, int qb, int la0, int lb0, int la1, int lb1, long lh) {
        int dA = Hash.range(Hash.mix(lh + 1), 7, lot - 1);
        int wB = Hash.range(Hash.mix(lh + 2), 7, lot - 1);
        int a0 = qa == 0 ? la0 + 1 : la1 - dA;
        int a1 = a0 + dA - 1;
        int b0 = lb0 + (lot - wB) / 2;
        int b1 = b0 + wB - 1;
        int xA = worldX(a0, b0), zA = worldZ(a0, b0), xB = worldX(a1, b1), zB = worldZ(a1, b1);
        boolean alongX = ux == 0; // door faces +-a, so the ridge runs across a
        int sign = (qa == 0 ? -1 : 1) * (ux + uz);
        int floors = Hash.range(Hash.mix(lh + 3), minFloors, maxFloors);
        Style st = styles[Hash.range(Hash.mix(lh + 4), 0, styles.length - 1)];
        boolean chimney = Hash.unit(Hash.mix(lh + 5)) < 0.4;
        return new House(xA, zA, xB, zB, alongX, baseY, floors, st, sign, chimney);
    }

    private void plazaColumn(ChunkBuffer buf, int x, int z, int a, int b, long h) {
        double da = a - plazaA, db = b - plazaB;
        double d = Math.sqrt(da * da + db * db);
        int ring = (int) d;
        buf.set(x, baseY, z, ring % 6 == 0 ? Blocks.STONE_BRICKS : (Hash.unit(h) < 0.7 ? Blocks.POLISHED_ANDESITE : Blocks.ANDESITE));
        if (d <= 1.3) {
            buf.fill(x, baseY + 1, baseY + 3, z, Blocks.STONE_BRICKS);
            buf.set(x, baseY + 4, z, Blocks.LANTERN);
        } else if (d <= 4.2) {
            buf.set(x, baseY, z, Blocks.WATER);
            buf.set(x, baseY - 1, z, Blocks.STONE_BRICKS);
        } else if (d <= 5.2) {
            buf.set(x, baseY + 1, z, Blocks.STONE_BRICKS);
        } else if (d > plazaR - 1.2 && Hash.unit(h) < 0.08) {
            buf.set(x, baseY + 1, z, Blocks.OAK_LEAVES);
        }
    }

    private void garden(ChunkBuffer buf, int x, int z, int a, int b, int la0, int lb0, int la1, int lb1, long h) {
        buf.set(x, baseY, z, Blocks.GRASS);
        boolean edge = a == la0 || a == la1 || b == lb0 || b == lb1;
        int ca = (la0 + la1) / 2, cb = (lb0 + lb1) / 2;
        int da = Math.abs(a - ca), db = Math.abs(b - cb);
        if (edge) {
            if ((a - la0) % 5 != 2 || (b != lb0 && b != lb1)) buf.set(x, baseY + 1, z, Blocks.OAK_LEAVES);
        } else if (da == 0 && db == 0) {
            buf.fill(x, baseY + 1, baseY + 4, z, Blocks.OAK_LOG);
            buf.fill(x, baseY + 5, baseY + 6, z, Blocks.OAK_LEAVES);
        } else if (da <= 2 && db <= 2 && da + db <= 3) {
            buf.fill(x, baseY + 4, baseY + 5, z, Blocks.OAK_LEAVES);
        } else {
            double u = Hash.unit(h);
            if (u < 0.25) buf.set(x, baseY + 1, z, u < 0.08 ? Blocks.POPPY : (u < 0.16 ? Blocks.DANDELION : Blocks.CORNFLOWER));
            else if (u < 0.3) buf.set(x, baseY + 1, z, Blocks.HAY);
        }
    }

    private void stalls(ChunkBuffer buf, int x, int z, int a, int b, int la0, int lb0, long h) {
        buf.set(x, baseY, z, Hash.unit(h) < 0.6 ? Blocks.COBBLE : Blocks.ANDESITE);
        for (int s = 0; s < 2; s++) {
            int sa = la0 + 1 + s * 6, sb = lb0 + 2 + s * 3;
            int pa = a - sa, pb = b - sb;
            if (pa < 0 || pa > 4 || pb < 0 || pb > 4) continue;
            boolean corner = (pa == 0 || pa == 4) && (pb == 0 || pb == 4);
            if (corner) buf.fill(x, baseY + 1, baseY + 3, z, Blocks.SPRUCE_FENCE);
            buf.set(x, baseY + 4, z, (pa + s) % 2 == 0 ? Blocks.RED_WOOL : Blocks.WHITE_WOOL);
            if (pa == 2 && pb >= 1 && pb <= 3) buf.set(x, baseY + 1, z, pb == 2 ? Blocks.HAY : Blocks.BARREL);
        }
    }

    private void well(ChunkBuffer buf, int x, int z, int a, int b, int la0, int lb0, long h) {
        buf.set(x, baseY, z, Hash.unit(h) < 0.5 ? Blocks.GRAVEL : Blocks.COBBLE);
        int ca = la0 + lot / 2, cb = lb0 + lot / 2;
        int da = Math.abs(a - ca), db = Math.abs(b - cb);
        if (da == 0 && db == 0) {
            buf.fill(x, baseY - 3, baseY, z, Blocks.WATER);
        } else if (da <= 1 && db <= 1) {
            buf.set(x, baseY + 1, z, Blocks.STONE_BRICKS);
            if (da == 1 && db == 1) {
                buf.fill(x, baseY + 2, baseY + 3, z, Blocks.SPRUCE_FENCE);
            }
            buf.set(x, baseY + 4, z, Blocks.id("spruce_slab[type=bottom]"));
        }
        if (da == 0 && db == 0) buf.set(x, baseY + 4, z, Blocks.id("spruce_slab[type=bottom]"));
    }
}
