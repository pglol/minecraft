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
    private double plotChance = 0;
    /** The lot (ia*2+qa, ib*2+qb) that always holds a stable with a Stable Master. */
    private long stableA = Long.MIN_VALUE, stableB = Long.MIN_VALUE;
    private int[] vendorSpot;

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

    private boolean stables = true;

    /** No stables here (the Underground City has no horses and no paddocks). */
    public TownGrid noStables() {
        stables = false;
        return this;
    }

    public TownGrid exclude(int x0, int z0, int x1, int z1) {
        exclusions.add(new int[] {Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1)});
        return this;
    }

    /** Fraction of city blocks left as empty fenced property plots. */
    public TownGrid plots(double chance) {
        plotChance = chance;
        return this;
    }

    private boolean plotBlock(int ia, int ib) {
        if (plotChance <= 0) return false;
        if (Hash.unit(Hash.of(seed ^ 0x9107, ia, ib)) >= plotChance) return false;
        int shift = street / 2;
        int a0 = ia * block + street, b0 = ib * block + street - shift;
        int a1 = a0 + 2 * lot, b1 = b0 + 2 * lot;
        return lotValid(a0, b0, a1, b1);
    }

    /** Every house of this grid within a world-space box (exported for the RPG mod's homes). */
    public java.util.List<House> houseList(int minX, int minZ, int maxX, int maxZ) {
        java.util.List<House> out = new java.util.ArrayList<>();
        int[] as = {localA(minX, minZ), localA(maxX, maxZ), localA(minX, maxZ), localA(maxX, minZ)};
        int[] bs = {localB(minX, minZ), localB(maxX, maxZ), localB(minX, maxZ), localB(maxX, minZ)};
        int amin = Math.min(Math.min(as[0], as[1]), Math.min(as[2], as[3])), amax = Math.max(Math.max(as[0], as[1]), Math.max(as[2], as[3]));
        int bmin = Math.min(Math.min(bs[0], bs[1]), Math.min(bs[2], bs[3])), bmax = Math.max(Math.max(bs[0], bs[1]), Math.max(bs[2], bs[3]));
        int shift = street / 2;
        for (int ia = Math.floorDiv(amin, block); ia <= Math.floorDiv(amax, block); ia++) {
            for (int ib = Math.floorDiv(bmin + shift, block); ib <= Math.floorDiv(bmax + shift, block); ib++) {
                if (plotBlock(ia, ib)) continue;
                for (int qa = 0; qa < 2; qa++) {
                    House wide = merged(ia, ib, qa);
                    if (wide != null) {
                        if (shape.inside(wide.x0, wide.z0) && shape.inside(wide.x1, wide.z1)) out.add(wide);
                        continue;
                    }
                    for (int qb = 0; qb < 2; qb++) {
                        int la0 = ia * block + street + qa * (lot + 1);
                        int lb0 = ib * block + street - shift + qb * (lot + 1);
                        int la1 = la0 + lot - 1, lb1 = lb0 + lot - 1;
                        if (!lotValid(la0, lb0, la1, lb1)) continue;
                        if (ia * 2L + qa == stableA && ib * 2L + qb == stableB) continue;
                        long lh = Hash.of(seed, ia * 2L + qa, ib * 2L + qb);
                        double u = Hash.unit(lh);
                        if (u < 0.74 || (u < 0.80 && !stables)) {
                            House h = house(qa, qb, la0, lb0, la1, lb1, lh);
                            // Every column of the house must be inside the grid, as when it is drawn.
                            if (shape.inside(h.x0, h.z0) && shape.inside(h.x1, h.z1)) out.add(h);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Enumerates the plot blocks of this grid within a world-space box. */
    public java.util.List<Plot> plotList(int minX, int minZ, int maxX, int maxZ) {
        java.util.List<Plot> out = new java.util.ArrayList<>();
        if (plotChance <= 0) return out;
        int[] as = {localA(minX, minZ), localA(maxX, maxZ), localA(minX, maxZ), localA(maxX, minZ)};
        int[] bs = {localB(minX, minZ), localB(maxX, maxZ), localB(minX, maxZ), localB(maxX, minZ)};
        int amin = Math.min(Math.min(as[0], as[1]), Math.min(as[2], as[3])), amax = Math.max(Math.max(as[0], as[1]), Math.max(as[2], as[3]));
        int bmin = Math.min(Math.min(bs[0], bs[1]), Math.min(bs[2], bs[3])), bmax = Math.max(Math.max(bs[0], bs[1]), Math.max(bs[2], bs[3]));
        int shift = street / 2;
        int gate = ux == 1 ? Style.W : ux == -1 ? Style.E : uz == 1 ? Style.N : Style.S;
        for (int ia = Math.floorDiv(amin, block); ia <= Math.floorDiv(amax, block); ia++) {
            for (int ib = Math.floorDiv(bmin + shift, block); ib <= Math.floorDiv(bmax + shift, block); ib++) {
                if (!plotBlock(ia, ib)) continue;
                int a0 = ia * block + street, b0 = ib * block + street - shift;
                int a1 = a0 + 2 * lot, b1 = b0 + 2 * lot;
                out.add(new Plot(Plot.Kind.TOWN, worldX(a0, b0), worldZ(a0, b0), worldX(a1, b1), worldZ(a1, b1), baseY, gate));
            }
        }
        return out;
    }

    /**
     * Picks the valid lot closest to the plaza (or the origin) as this town's stable, so every
     * town has a Stable Master. Returns the vendor's world x/z, or null if no lot fits.
     */
    public int[] guaranteeStable() {
        double pa = Double.isNaN(plazaA) ? block * 1.5 : plazaA, pb = Double.isNaN(plazaA) ? 0 : plazaB;
        int shift = street / 2;
        int ca = Math.floorDiv((int) pa, block), cb = Math.floorDiv((int) pb + shift, block);
        double best = Double.MAX_VALUE;
        for (int ia = ca - 6; ia <= ca + 6; ia++) {
            for (int ib = cb - 6; ib <= cb + 6; ib++) {
                if (plotBlock(ia, ib)) continue;
                for (int qa = 0; qa < 2; qa++) {
                    for (int qb = 0; qb < 2; qb++) {
                        int la0 = ia * block + street + qa * (lot + 1);
                        int lb0 = ib * block + street - shift + qb * (lot + 1);
                        if (!lotValid(la0, lb0, la0 + lot - 1, lb0 + lot - 1)) continue;
                        double d = Math.hypot(la0 + lot / 2.0 - pa, lb0 + lot / 2.0 - pb);
                        if (d < best) {
                            best = d;
                            stableA = ia * 2L + qa;
                            stableB = ib * 2L + qb;
                            vendorSpot = new int[] {worldX(la0 + 8, lb0 + 2), worldZ(la0 + 8, lb0 + 2)};
                        }
                    }
                }
            }
        }
        return vendorSpot;
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
        if (plotBlock(ia, ib)) {
            buf.set(x, baseY, z, Blocks.GRASS);
            return;
        }
        int ra = ma - street, rb = mb - street;
        if (ra != lot) {
            House wide = merged(ia, ib, ra < lot ? 0 : 1);
            if (wide != null) {
                buf.set(x, baseY, z, Blocks.COBBLE);
                if (wide.covers(x, z)) wide.column(buf, x, z, baseY);
                return;
            }
        }
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
        boolean forced = ia * 2L + qa == stableA && ib * 2L + qb == stableB;
        if (forced) {
            stable(buf, x, z, a, b, la0, lb0, la1, lb1, h, true);
        } else if (u < 0.74) {
            House house = house(qa, qb, la0, lb0, la1, lb1, lh);
            buf.set(x, baseY, z, Blocks.COBBLE);
            if (house.covers(x, z)) house.column(buf, x, z, baseY);
        } else if (u < 0.80 && stables) {
            stable(buf, x, z, a, b, la0, lb0, la1, lb1, h, false);
        } else if (u < 0.80) {
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
        // The whole outline (with the roof overhang) must be inside the town: never clip a building.
        for (int a = la0 - 1; a <= la1 + 1; a += 2) {
            if (!shape.inside(worldX(a, lb0 - 1), worldZ(a, lb0 - 1)) || !shape.inside(worldX(a, lb1 + 1), worldZ(a, lb1 + 1))) return false;
        }
        for (int b = lb0 - 1; b <= lb1 + 1; b += 2) {
            if (!shape.inside(worldX(la0 - 1, b), worldZ(la0 - 1, b)) || !shape.inside(worldX(la1 + 1, b), worldZ(la1 + 1, b))) return false;
        }
        if (!shape.inside(worldX(la1 + 1, lb1 + 1), worldZ(la1 + 1, lb1 + 1))) return false;
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

    /** Fraction of lot pairs joined into one wide house (a long hall or manor across both lots). */
    private double wideChance = 0.16;

    public TownGrid wideChance(double c) {
        wideChance = c;
        return this;
    }

    /** Hizuru styles for the houses on one side of the avenue (b < 0): a Japanese quarter. */
    private Style[] quarter;

    public TownGrid quarter(Style[] styles) {
        quarter = styles;
        return this;
    }

    /**
     * The wide house joining lots (qa, 0) and (qa, 1) of a city block, or null. Its hash is the
     * first lot's, so both lots draw the same building.
     */
    private House merged(int ia, int ib, int qa) {
        if (wideChance <= 0 || lot < 9 || Hash.unit(Hash.of(seed ^ 0x3E6DL, ia * 2L + qa, ib)) >= wideChance) return null;
        if (plotBlock(ia, ib)) return null;
        int shift = street / 2;
        int la0 = ia * block + street + qa * (lot + 1);
        int lb0 = ib * block + street - shift;
        int la1 = la0 + lot - 1, lb1 = lb0 + 2 * lot; // both lots and the strip between them
        for (int qb = 0; qb < 2; qb++) if (ia * 2L + qa == stableA && ib * 2L + qb == stableB) return null;
        if (!lotValid(la0, lb0, la1, lb1)) return null;
        long lh = Hash.of(seed, ia * 2L + qa, ib * 2L);
        if (Hash.unit(lh) >= 0.8) return null; // gardens, stalls and wells stay
        int dA = Hash.range(Hash.mix(lh + 1), Math.max(7, lot - 4), lot - 1);
        int a0 = qa == 0 ? la0 + 1 : la1 - dA;
        int a1 = a0 + dA - 1;
        int b0 = lb0 + 1, b1 = lb1 - 1;
        int xA = worldX(a0, b0), zA = worldZ(a0, b0), xB = worldX(a1, b1), zB = worldZ(a1, b1);
        boolean alongX = ux == 0;
        int sign = (qa == 0 ? -1 : 1) * (ux + uz);
        // Wide houses: half are long single halls, half are tall manors.
        boolean manor = Hash.unit(Hash.mix(lh + 9)) < 0.5;
        int floors = manor ? Math.min(5, maxFloors + 1) : Math.max(1, minFloors);
        Style st = styleFor(lh, b0);
        return new House(xA, zA, xB, zB, alongX, baseY, floors, st, sign, Hash.unit(Hash.mix(lh + 5)) < 0.6);
    }

    private Style styleFor(long lh, int b) {
        Style[] pool = quarter != null && b < 0 ? quarter : styles;
        return pool[Hash.range(Hash.mix(lh + 4), 0, pool.length - 1)];
    }

    private House house(int qa, int qb, int la0, int lb0, int la1, int lb1, long lh) {
        // Shapes: cottages, tall narrow townhouses, broad houses, and big tall ones.
        int shape = lot >= 9 ? Hash.range(Hash.mix(lh + 7), 0, 3) : 0;
        int dA, wB, floors;
        switch (shape) {
            case 1 -> { // tall and narrow
                dA = Hash.range(Hash.mix(lh + 1), 8, Math.min(9, lot - 1));
                wB = Hash.range(Hash.mix(lh + 2), 8, Math.min(10, lot - 1));
                floors = Math.min(5, maxFloors + 1);
            }
            case 2 -> { // wide and low
                dA = lot - 1;
                wB = lot - 1;
                floors = minFloors;
            }
            case 3 -> { // wide and tall
                dA = Hash.range(Hash.mix(lh + 1), lot - 3, lot - 1);
                wB = lot - 1;
                floors = maxFloors;
            }
            default -> {
                dA = Hash.range(Hash.mix(lh + 1), 7, lot - 1);
                wB = Hash.range(Hash.mix(lh + 2), 7, lot - 1);
                floors = Hash.range(Hash.mix(lh + 3), minFloors, maxFloors);
            }
        }
        int a0 = qa == 0 ? la0 + 1 : la1 - dA;
        int a1 = a0 + dA - 1;
        int b0 = lb0 + (lot - wB) / 2;
        int b1 = b0 + wB - 1;
        int xA = worldX(a0, b0), zA = worldZ(a0, b0), xB = worldX(a1, b1), zB = worldZ(a1, b1);
        boolean alongX = ux == 0; // door faces +-a, so the ridge runs across a
        int sign = (qa == 0 ? -1 : 1) * (ux + uz);
        Style st = styleFor(lh, b0);
        boolean chimney = Hash.unit(Hash.mix(lh + 5)) < 0.4;
        House hs = new House(xA, zA, xB, zB, alongX, baseY, floors, st, sign, chimney);
        if (!st.timber && Hash.unit(Hash.mix(lh + 6)) < 0.3) hs.use(House.Use.HALL);
        return hs;
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

    private static final int[] SIGN_ROT = {4};

    /** A fenced town stable: a roofed shelter, hay, a trough and a couple of horses. */
    private void stable(ChunkBuffer buf, int x, int z, int a, int b, int la0, int lb0, int la1, int lb1, long h, boolean vendor) {
        buf.set(x, baseY, z, Hash.unit(h) < 0.5 ? Blocks.COARSE_DIRT : Blocks.GRASS);
        boolean ea = a == la0 || a == la1, eb = b == lb0 || b == lb1;
        int ga = (la0 + la1) / 2;
        if (ea || eb) {
            boolean gate = a == la0 && Math.abs(b - (lb0 + lb1) / 2) <= 0;
            if (!gate) buf.set(x, baseY + 1, z, (ea && eb) ? Blocks.OAK_FENCE : (ea == (ux != 0) ? Blocks.SPRUCE_FENCE_Z : Blocks.SPRUCE_FENCE_X));
            if (ea && eb) buf.set(x, baseY + 2, z, Blocks.TORCH);
            return;
        }
        int ra = a - la0, rb = b - lb0, rbEnd = lb1 - lb0 - 1;
        if (ra >= 6) {
            // Stable barn along the back of the lot: walls on three sides, open stalls facing the paddock.
            int y = baseY;
            buf.set(x, y, z, Blocks.SPRUCE_PLANKS);
            boolean end = rb == 1 || rb == rbEnd;
            if (ra == 6) {
                // Roof overhang over the stall fronts.
                buf.set(x, y + 4, z, Blocks.id("spruce_slab[type=top]"));
                return;
            }
            boolean back = ra == la1 - la0 - 1;
            boolean front = ra == 7;
            if (back || end) {
                boolean corner = (back || front) && end;
                buf.fill(x, y + 1, y + 3, z, corner ? Blocks.id("spruce_log[axis=y]") : Blocks.SPRUCE_PLANKS);
            } else if (front) {
                if (rb % 3 == 1) buf.fill(x, y + 1, y + 3, z, Blocks.id("spruce_log[axis=y]"));
            } else {
                // Inside: stall dividers, hay at the back, a lantern per stall.
                if (rb % 3 == 1 && ra <= 9) buf.set(x, y + 1, z, (ux != 0) ? Blocks.SPRUCE_FENCE_X : Blocks.SPRUCE_FENCE_Z);
                else if (ra == 9 && rb % 3 == 2) buf.set(x, y + 1, z, Blocks.HAY);
                if (ra == 8 && rb % 3 == 0) buf.set(x, y + 3, z, Blocks.id("lantern[hanging=true]"));
            }
            buf.set(x, y + 4, z, Blocks.SPRUCE_PLANKS);
            if (!front && !back) buf.set(x, y + 5, z, Blocks.id("spruce_slab[type=bottom]"));
            if (ra == 8 && rb == 3) buf.mob(x, y + 1, z, "horse");
            if (vendor && ra == 8 && rb == 2) buf.mob(x, y + 1, z, "aot:stable_master");
            return;
        } else if (ra == 2 && rb >= 4 && rb <= 7) {
            buf.set(x, baseY, z, Blocks.WATER);
        }
        if ((ra == 4 && rb == 3) || (ra == 5 && rb == 8)) buf.mob(x, baseY + 1, z, "horse");
        // Many stables have a Stable Master selling horses (inside the barn, see above).
        if (vendor && ra == 1 && rb == 2) buf.sign(x, baseY + 1, z, SIGN_ROT[0], "Stables", "Horses for sale", "", "");
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
        } else if (da == 3 && db == 3) {
            buf.mob(x, baseY + 1, z, Hash.unit(Hash.mix(h + 3)) < 0.6 ? "chicken" : "cat");
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
        if (da == 3 && db == 0) buf.mob(x, baseY + 1, z, Hash.unit(Hash.mix(h + 3)) < 0.5 ? "chicken" : "cat");
    }
}
