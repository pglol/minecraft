package com.pglol.aotworld.core.build;

import com.pglol.aotworld.core.AotWorld;
import com.pglol.aotworld.core.Blocks;
import com.pglol.aotworld.core.ChunkBuffer;
import com.pglol.aotworld.core.Column;
import com.pglol.aotworld.core.Feature;
import com.pglol.aotworld.core.Hash;
import com.pglol.aotworld.core.Mth;
import com.pglol.aotworld.core.Pad;
import com.pglol.aotworld.core.WorldSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * A village: cottages on levelled pads around a well, footpaths to each door,
 * and extras by type (fields, terraced gardens, log piles, fishing docks).
 */
public final class Village extends Feature implements StableOwner {
    public enum Type { FARM, HILL, FOREST, FISHING, HIDDEN, MARLEY }

    public final String name;
    public final Type type;
    public final int cx, cz, radius, centerY;
    private final List<House> houses = new ArrayList<>();
    private final List<int[]> fields = new ArrayList<>(); // x0, z0, x1, z1, crop, alongX
    private final List<Pad> pads = new ArrayList<>();
    /** Animal pens: x0, z0, x1, z1, kind (0 cattle and sheep, 1 pigs, 2 chickens, 3 horses). */
    private final List<int[]> pens = new ArrayList<>();
    private final long seed;
    /** Fishing villages: direction (unit vector) towards the sea, and where the dock starts. */
    private double seaX, seaZ;
    private static final int[] CROPS = {Blocks.WHEAT, Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS};

    public Village(AotWorld world, String name, Type type, int cx, int cz, int radius, long seed) {
        super(cx - radius - 90, cz - radius - 90, cx + radius + 90, cz + radius + 90);
        this.name = name;
        this.type = type;
        this.cx = cx;
        this.cz = cz;
        this.radius = radius;
        this.seed = seed;
        this.centerY = world.terrain.naturalHeight(cx, cz);
        pads.add(Pad.circle(cx, cz, 7, centerY, 8));

        if (type == Type.FISHING) {
            double bx = 0, bz = 0;
            for (int i = 0; i < 16; i++) {
                double a = i * Math.PI / 8;
                double sd = world.atlas.landSD(cx + Math.cos(a) * 200, cz + Math.sin(a) * 200);
                bx -= Math.cos(a) * sd;
                bz -= Math.sin(a) * sd;
            }
            double l = Math.hypot(bx, bz);
            seaX = l == 0 ? 1 : bx / l;
            seaZ = l == 0 ? 0 : bz / l;
        }

        Style[] styles;
        switch (type) {
            case MARLEY: styles = new Style[] {Style.LIBERIO[2], Style.RURAL[0]}; break;
            case HILL: styles = new Style[] {Style.HILL[0], Style.HILL[1], Style.RURAL[0]}; break;
            case FOREST: case HIDDEN: styles = new Style[] {Style.CABIN[0], Style.CABIN[1]}; break;
            case FISHING: styles = new Style[] {Style.RURAL[0], Style.RURAL[2], Style.PARADIS[1]}; break;
            default: styles = Style.RURAL;
        }
        int want = name != null ? 13 : Hash.range(seed, 6, 11);
        for (int i = 0; i < 90 && houses.size() < want; i++) {
            long h = Hash.of(seed, i);
            double ang = Hash.unit(h) * Math.PI * 2;
            double dist = 14 + Hash.unit(Hash.mix(h + 1)) * (radius - 18);
            int hx = cx + (int) (Math.cos(ang) * dist), hz = cz + (int) (Math.sin(ang) * dist);
            if (type == Type.FISHING && world.atlas.landSD(hx, hz) < 18) continue;
            int w = Hash.range(Hash.mix(h + 2), 7, 9), d = Hash.range(Hash.mix(h + 3), 6, 8);
            int dx = hx - cx, dz = hz - cz;
            boolean doorOnX = Math.abs(dx) > Math.abs(dz);
            int x0, z0, x1, z1;
            if (doorOnX) { x0 = hx - d / 2; x1 = x0 + d - 1; z0 = hz - w / 2; z1 = z0 + w - 1; }
            else { x0 = hx - w / 2; x1 = x0 + w - 1; z0 = hz - d / 2; z1 = z0 + d - 1; }
            if (overlaps(x0 - 4, z0 - 4, x1 + 4, z1 + 4)) continue;
            int hmin = Integer.MAX_VALUE, hmax = Integer.MIN_VALUE;
            for (int[] c : new int[][] {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}, {hx, hz}}) {
                int hh = world.terrain.naturalHeight(c[0], c[1]);
                hmin = Math.min(hmin, hh);
                hmax = Math.max(hmax, hh);
            }
            if (hmax - hmin > 12 || hmin <= WorldSpec.SEA) continue;
            int base = world.terrain.naturalHeight(hx, hz);
            int doorSide = doorOnX ? (dx > 0 ? -1 : 1) : (dz > 0 ? -1 : 1);
            int floors = Hash.unit(Hash.mix(h + 4)) < (type == Type.HILL ? 0.5 : 0.7) ? 1 : 2;
            Style st = styles[Hash.range(Hash.mix(h + 5), 0, styles.length - 1)];
            houses.add(new House(x0, z0, x1, z1, !doorOnX, base, floors, st, doorSide, Hash.unit(Hash.mix(h + 6)) < 0.5));
            pads.add(Pad.rect(x0 - 2, z0 - 2, x1 + 2, z1 + 2, base, 7));
        }

        if (type == Type.FARM || type == Type.MARLEY || (type == Type.HILL)) {
            int nf = type == Type.HILL ? Hash.range(Hash.mix(seed + 9), 3, 5) : Hash.range(Hash.mix(seed + 9), 3, 5) + (name != null ? 2 : 0);
            for (int i = 0; i < 40 && fields.size() < nf; i++) {
                long h = Hash.of(seed, 1000 + i);
                double ang = Hash.unit(h) * Math.PI * 2;
                double dist = type == Type.HILL ? radius * 0.6 + Hash.unit(Hash.mix(h + 1)) * 20
                                                : radius + 16 + Hash.unit(Hash.mix(h + 1)) * 40;
                int fx = cx + (int) (Math.cos(ang) * dist), fz = cz + (int) (Math.sin(ang) * dist);
                int w = type == Type.HILL ? Hash.range(Hash.mix(h + 2), 8, 12) : Hash.range(Hash.mix(h + 2), 16, 28);
                int d = type == Type.HILL ? Hash.range(Hash.mix(h + 3), 6, 9) : Hash.range(Hash.mix(h + 3), 12, 20);
                int[] f = {fx - w / 2, fz - d / 2, fx + w / 2, fz + d / 2, CROPS[Hash.range(Hash.mix(h + 4), 0, 4)],
                    Hash.unit(Hash.mix(h + 5)) < 0.5 ? 1 : 0};
                boolean clash = overlaps(f[0] - 2, f[1] - 2, f[2] + 2, f[3] + 2);
                for (int[] o : fields) {
                    if (f[2] + 3 >= o[0] && f[0] - 3 <= o[2] && f[3] + 3 >= o[1] && f[1] - 3 <= o[3]) clash = true;
                }
                if (clash) continue;
                fields.add(f);
                if (type == Type.HILL) {
                    // Terraced garden: level each little field.
                    pads.add(Pad.rect(f[0] - 1, f[1] - 1, f[2] + 1, f[3] + 1, world.terrain.naturalHeight(fx, fz), 6));
                }
            }
        }
        addPens(world);
    }

    private void addPens(AotWorld world) {
        int want = type == Type.FARM ? 3 : (type == Type.HILL || type == Type.MARLEY ? 2 : (type == Type.FISHING ? 1 : 1));
        for (int i = 0; i < 30 && pens.size() < want; i++) {
            long h = Hash.of(seed, 2000 + i);
            double ang = Hash.unit(h) * Math.PI * 2;
            int kind = pens.isEmpty() && type == Type.FARM ? 3 : Hash.range(Hash.mix(h + 4), 0, 2);
            if (type == Type.FOREST || type == Type.HIDDEN) kind = 2;
            int w = kind == 2 ? 7 : Hash.range(Hash.mix(h + 2), 11, 15), d = kind == 2 ? 6 : Hash.range(Hash.mix(h + 3), 9, 12);
            double dist = radius - 6 + Hash.unit(Hash.mix(h + 1)) * 14;
            int px = cx + (int) (Math.cos(ang) * dist), pz = cz + (int) (Math.sin(ang) * dist);
            int[] pen = {px - w / 2, pz - d / 2, px - w / 2 + w - 1, pz - d / 2 + d - 1, kind};
            if (overlaps(pen[0] - 3, pen[1] - 3, pen[2] + 3, pen[3] + 3)) continue;
            boolean clash = false;
            for (int[] o : fields) if (pen[2] + 3 >= o[0] && pen[0] - 3 <= o[2] && pen[3] + 3 >= o[1] && pen[1] - 3 <= o[3]) clash = true;
            for (int[] o : pens) if (pen[2] + 3 >= o[0] && pen[0] - 3 <= o[2] && pen[3] + 3 >= o[1] && pen[1] - 3 <= o[3]) clash = true;
            if (clash || world.atlas.landSD(px, pz) < 12) continue;
            pens.add(pen);
            pads.add(Pad.rect(pen[0] - 1, pen[1] - 1, pen[2] + 1, pen[3] + 1, world.terrain.naturalHeight(px, pz), 6));
        }
    }

    private static final String[][] PEN_ANIMALS = {
        {"cow", "sheep", "cow", "sheep", "sheep"}, {"pig", "pig", "pig"}, {"chicken", "chicken", "chicken", "chicken"}, {"horse", "horse", "horse"}
    };

    /** Draws a pen and its animals; returns true when the column belongs to one. */
    private boolean pen(ChunkBuffer buf, int x, int z, int h) {
        for (int[] p : pens) {
            if (x < p[0] || x > p[2] || z < p[1] || z > p[3]) continue;
            boolean ex = x == p[0] || x == p[2], ez = z == p[1] || z == p[3];
            buf.fill(x, h + 1, h + 3, z, Blocks.AIR);
            int top = buf.get(x, h, z);
            if (top != Blocks.GRASS) buf.set(x, h, z, Blocks.GRASS);
            if (ex || ez) {
                int midX = (p[0] + p[2]) / 2;
                boolean gate = ez && z == p[1] && x == midX;
                buf.set(x, h + 1, z, gate ? Blocks.id("oak_fence_gate[facing=north]")
                    : (ex && ez) ? Blocks.OAK_FENCE : (ez ? Blocks.id("oak_fence[east=true,west=true]") : Blocks.id("oak_fence[north=true,south=true]")));
                return true;
            }
            int ix = x - p[0] - 1, iz = z - p[1] - 1;
            String[] animals = PEN_ANIMALS[p[4]];
            if (iz == 1 && ix >= 1 && ix % 2 == 1 && ix / 2 < animals.length) buf.mob(x, h + 1, z, animals[ix / 2]);
            if (p[4] == 3 && ix == 0 && iz == 0) buf.set(x, h + 1, z, Blocks.HAY);
            if (p[4] == 3 && ix == 1 && iz == 3 && type == Type.FARM) buf.mob(x, h + 1, z, "aot:stable_master");
            if (p[4] != 2 && x == p[2] - 1 && z > p[1] + 1 && z < p[1] + 4) buf.set(x, h, z, Blocks.WATER);
            if (p[4] == 2 && x == p[0] + 1 && z == p[1] + 1) buf.set(x, h + 1, z, Blocks.HAY);
            return true;
        }
        return false;
    }

    @Override
    public int[] stableSpot() {
        if (type != Type.FARM) return null;
        for (int[] p : pens) if (p[4] == 3) return new int[] {p[0] + 2, p[1] + 4};
        return null;
    }

    public List<Pad> pads() {
        return pads;
    }

    public List<House> houses() {
        return houses;
    }

    private boolean overlaps(int x0, int z0, int x1, int z1) {
        if (Math.abs((x0 + x1) / 2 - cx) < 9 && Math.abs((z0 + z1) / 2 - cz) < 9) return true;
        for (House o : houses) {
            if (x1 >= o.x0 && x0 <= o.x1 && z1 >= o.z0 && z0 <= o.z1) return true;
        }
        return false;
    }

    @Override
    public int layer() {
        return 5;
    }

    @Override
    public boolean occupies(int x, int z) {
        if (Math.hypot(x - cx, z - cz) < radius + 6) return true;
        for (int[] p : pens) if (x >= p[0] - 2 && x <= p[2] + 2 && z >= p[1] - 2 && z <= p[3] + 2) return true;
        for (int[] f : fields) if (x >= f[0] - 1 && x <= f[2] + 1 && z >= f[1] - 1 && z <= f[3] + 1) return true;
        return false;
    }

    @Override
    public void column(ChunkBuffer buf, int x, int z, Column col) {
        if (type == Type.FISHING) dock(buf, x, z, col);
        if (col.underwater()) return;
        int h = col.height;
        for (int[] f : fields) {
            if (x < f[0] || x > f[2] || z < f[1] || z > f[3]) continue;
            if (col.road >= 0 || col.slope > 2) return;
            int row = f[5] == 1 ? z - f[1] : x - f[0];
            buf.set(x, h + 1, z, Blocks.AIR);
            if (x == f[0] || x == f[2] || z == f[1] || z == f[3]) {
                buf.set(x, h, z, type == Type.HILL ? Blocks.COBBLE : Blocks.DIRT_PATH);
                if (type == Type.HILL) buf.set(x, h + 1, z, Blocks.COBBLE_WALL);
            } else if (row % 9 == 4) {
                buf.set(x, h, z, Blocks.WATER);
            } else {
                buf.set(x, h, z, Blocks.FARMLAND);
                buf.set(x, h + 1, z, f[4]);
            }
            return;
        }
        if (pen(buf, x, z, h)) return;
        for (House house : houses) {
            if (house.covers(x, z)) {
                house.column(buf, x, z, h);
                return;
            }
        }
        double dc = Math.hypot(x - cx, z - cz);
        int ground = type == Type.HIDDEN || type == Type.FOREST ? Blocks.id("moss_block") : Blocks.DIRT_PATH;
        if (dc <= 2.2) {
            if (dc <= 0.8) {
                buf.fill(x, h - 4, h, z, Blocks.WATER);
            } else {
                buf.set(x, h, z, Blocks.COBBLE);
                buf.set(x, h + 1, z, Blocks.COBBLE_WALL);
            }
            return;
        }
        if (dc <= 6) {
            buf.set(x, h, z, dc > 5.3 && type == Type.HILL ? Blocks.COBBLE : ground);
            buf.set(x, h + 1, z, Blocks.AIR);
            return;
        }
        for (House house : houses) {
            int[] s = house.doorstep();
            if (Mth.segDist(x, z, cx, cz, s[0], s[1], null) <= 1.0) {
                int top = buf.get(x, h, z);
                if (top == Blocks.GRASS || top == Blocks.SAND) {
                    buf.set(x, h, z, ground);
                    buf.set(x, h + 1, z, Blocks.AIR);
                }
                return;
            }
        }
        long hh = Hash.of(seed, x, z);
        if (dc < radius) {
            double u = Hash.unit(hh);
            if (type == Type.FOREST && u < 0.006) {
                buf.set(x, h + 1, z, Blocks.OAK_LOG_X);
                buf.set(x, h + 2, z, Blocks.OAK_LOG_X);
            } else if (u < 0.004) {
                buf.set(x, h + 1, z, Blocks.HAY);
            }
        }
    }

    /** A pier from the shore straight out to sea. */
    private void dock(ChunkBuffer buf, int x, int z, Column col) {
        double rx = x - cx, rz = z - cz;
        double along = rx * seaX + rz * seaZ, across = -rx * seaZ + rz * seaX;
        if (along < radius * 0.5 || along > radius + 160 || Math.abs(across) > 2) return;
        if (!col.underwater() || col.lake || col.river) return;
        int deck = WorldSpec.SEA + 2;
        if (col.water - col.height > 12) return; // stop where it gets deep
        buf.set(x, deck, z, Blocks.SPRUCE_PLANKS);
        buf.fill(x, col.water + 1, deck - 1, z, Blocks.AIR);
        if (Math.abs(across) >= 1.5 && Math.floorMod((int) along, 5) == 0) {
            buf.fill(x, col.height, deck - 1, z, Blocks.SPRUCE_LOG);
            buf.set(x, deck + 1, z, Blocks.SPRUCE_FENCE);
            if (Math.floorMod((int) along, 15) == 0) buf.set(x, deck + 2, z, Blocks.LANTERN);
        }
    }
}
