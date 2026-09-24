package com.pglol.aotworld.core.build;

import com.pglol.aotworld.core.AotWorld;
import com.pglol.aotworld.core.Blocks;
import com.pglol.aotworld.core.ChunkBuffer;
import com.pglol.aotworld.core.Column;
import com.pglol.aotworld.core.Feature;
import com.pglol.aotworld.core.Hash;
import com.pglol.aotworld.core.Mth;
import com.pglol.aotworld.core.Pad;

/**
 * Points of interest scattered through the wilds: titan caves (dungeons),
 * Survey Corps expedition camps, ruined watchtowers, campsites, stone
 * shrines, hermit cabins and shipwrecks.
 */
public final class Poi extends Feature {
    public enum Kind {
        TITAN_CAVE(95), EXPEDITION_CAMP(42), WATCHTOWER(8), CAMPSITE(14), SHRINE(10), HERMIT(14), SHIPWRECK(12);

        public final int reach;

        Kind(int reach) {
            this.reach = reach;
        }
    }

    public final Kind kind;
    public final String name;
    public final int x, z, y;
    private final long seed;
    private final int dirX, dirZ;
    /** Angle of the camp's palisade opening; set to face its trail. */
    public double openAngle;

    // Titan cave geometry.
    private double[][] chambers; // cx, cy, cz, rx, ry, rz
    private double[][] tunnels;  // ax, ay, az, bx, by, bz, r
    private int[][] chests;      // x, y, z, lootIndex
    private House cabin;

    private static final String[] CAVE_LOOT = {
        "minecraft:chests/simple_dungeon", "minecraft:chests/abandoned_mineshaft", "minecraft:chests/desert_pyramid"
    };
    private static final int BONE = Blocks.id("bone_block[axis=y]");
    private static final int SOUL_LANTERN = Blocks.id("soul_lantern[hanging=true]");

    public Poi(AotWorld world, Kind kind, String name, int x, int z, long seed) {
        super(x - kind.reach, z - kind.reach, x + kind.reach, z + kind.reach);
        this.kind = kind;
        this.name = name;
        this.x = x;
        this.z = z;
        this.seed = seed;
        this.y = world.terrain.naturalHeight(x, z);
        // Face downhill (or towards the sea for wrecks).
        int bestDx = 1, bestDz = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            int dx = Math.round((float) Math.cos(i * Math.PI / 4)), dz = Math.round((float) Math.sin(i * Math.PI / 4));
            double v = kind == Kind.SHIPWRECK ? world.atlas.landSD(x + dx * 60, z + dz * 60)
                                              : world.terrain.naturalHeight(x + dx * 60, z + dz * 60);
            // Never dig the mouth into a Wall, a town or the sea.
            if (kind == Kind.TITAN_CAVE && (world.atlas.wallFlatten(x + dx * 95, z + dz * 95) > 0
                || world.atlas.landSD(x + dx * 95, z + dz * 95) < 20)) v += 10000;
            if (v < best) {
                best = v;
                bestDx = dx;
                bestDz = dz;
            }
        }
        dirX = bestDx;
        dirZ = bestDz;
        openAngle = Math.atan2(dirZ, dirX);
        if (kind == Kind.TITAN_CAVE) buildCave(world);
        if (kind == Kind.HERMIT) {
            cabin = new House(x - 3, z - 3, x + 3, z + 2, true, y, 1, Style.CABIN[Hash.range(seed, 0, 1)], 1, true);
        }
    }

    /** Levelled ground this POI needs, or null. */
    public Pad pad() {
        switch (kind) {
            case EXPEDITION_CAMP: return Pad.circle(x, z, 34, y, 16);
            case HERMIT: return Pad.circle(x, z, 10, y, 8);
            case CAMPSITE: return Pad.circle(x, z, 9, y, 6);
            case SHRINE: return Pad.circle(x, z, 8, y, 6);
            case WATCHTOWER: return Pad.circle(x, z, 5, y, 5);
            default: return null;
        }
    }

    /** Where a trail should arrive: cave mouth, cabin door, tower doorway. */
    public int[] entrance() {
        switch (kind) {
            case TITAN_CAVE: return new int[] {(int) tunnels[0][0], (int) tunnels[0][2]};
            case HERMIT: return new int[] {x, z + 4};
            case WATCHTOWER: return new int[] {x + dirX * 5, z + dirZ * 5};
            case CAMPSITE: return new int[] {x, z + 6};
            default: return new int[] {x, z};
        }
    }

    @Override
    public int layer() {
        return 7;
    }

    @Override
    public boolean occupies(int px, int pz) {
        double r = kind == Kind.TITAN_CAVE ? 12 : kind.reach;
        int[] e = entrance();
        return Math.hypot(px - x, pz - z) < r || Math.hypot(px - e[0], pz - e[1]) < 9;
    }

    @Override
    public void column(ChunkBuffer b, int px, int pz, Column col) {
        switch (kind) {
            case TITAN_CAVE: cave(b, px, pz, col); break;
            case EXPEDITION_CAMP: camp(b, px, pz, col); break;
            case WATCHTOWER: watchtower(b, px, pz, col); break;
            case CAMPSITE: campsite(b, px, pz, col); break;
            case SHRINE: shrine(b, px, pz, col); break;
            case HERMIT: hermit(b, px, pz, col); break;
            default: shipwreck(b, px, pz, col); break;
        }
    }

    // ---- Titan cave ----------------------------------------------------------------------

    private void buildCave(AotWorld world) {
        double cy = y - 24;
        double ax = Math.atan2(dirZ, dirX);
        chambers = new double[3][];
        chambers[0] = new double[] {x, cy, z, 26, 15, 21};
        double[][] side = new double[2][];
        for (int s = 0; s < 2; s++) {
            double a = ax + Math.PI + (s == 0 ? 1.9 : -1.9);
            double sx = x + Math.cos(a) * 38, sz = z + Math.sin(a) * 38;
            chambers[s + 1] = new double[] {sx, cy - 2, sz, 13, 10, 13};
            side[s] = chambers[s + 1];
        }
        // Entrance: from the hillside down into the main hall.
        double drop = 0, run = 0;
        double ex = x, ez = z, ey = y;
        for (int d = 40; d <= 90; d += 5) {
            ex = x + dirX * d / Math.hypot(dirX, dirZ);
            ez = z + dirZ * d / Math.hypot(dirX, dirZ);
            ey = world.terrain.naturalHeight(ex, ez);
            run = d - 18;
            drop = ey - cy;
            if (drop <= run * 0.8) break;
        }
        double ix = x + dirX * 18 / Math.hypot(dirX, dirZ), iz = z + dirZ * 18 / Math.hypot(dirX, dirZ);
        tunnels = new double[][] {
            {ex, ey + 3, ez, ix, cy, iz, 6.5},
            {x, cy - 1, z, side[0][0], side[0][1], side[0][2], 4.5},
            {x, cy - 1, z, side[1][0], side[1][1], side[1][2], 4.5},
        };
        double backX = x - dirX * 17 / Math.hypot(dirX, dirZ), backZ = z - dirZ * 17 / Math.hypot(dirX, dirZ);
        chests = new int[][] {
            {(int) Math.round(backX), (int) cy, (int) Math.round(backZ), 2},
            {(int) Math.round(side[0][0]), (int) side[0][1], (int) Math.round(side[0][2]), 0},
            {(int) Math.round(side[1][0]), (int) side[1][1], (int) Math.round(side[1][2]), 1},
        };
    }

    private void cave(ChunkBuffer b, int px, int pz, Column col) {
        int floorMin = Integer.MAX_VALUE, topMax = Integer.MIN_VALUE;
        boolean main = false;
        for (int i = 0; i < chambers.length; i++) {
            double[] c = chambers[i];
            double dx = (px - c[0]) / c[3], dz = (pz - c[2]) / c[5];
            double d2 = dx * dx + dz * dz;
            if (d2 >= 1) continue;
            double vh = c[4] * Math.sqrt(1 - d2);
            int floor = (int) Math.round(c[1] - Math.min(vh, c[4] * 0.45));
            int top = (int) Math.round(c[1] + vh);
            floorMin = Math.min(floorMin, floor);
            topMax = Math.max(topMax, top);
            if (i == 0) main = true;
        }
        double[] t = new double[1];
        for (double[] tn : tunnels) {
            double hd = Mth.segDist(px, pz, tn[0], tn[2], tn[3], tn[5], t);
            if (hd >= tn[6]) continue;
            double cy = Mth.lerp(t[0], tn[1], tn[4]);
            double vh = Math.sqrt(tn[6] * tn[6] - hd * hd);
            int floor = (int) Math.round(cy - Math.min(vh, tn[6] * 0.55));
            int top = (int) Math.round(cy + vh);
            floorMin = Math.min(floorMin, floor);
            topMax = Math.max(topMax, top);
        }
        if (floorMin == Integer.MAX_VALUE) return;
        long h = Hash.of(seed, px, pz);
        double u = Hash.unit(h);
        b.fill(px, floorMin + 1, topMax, pz, Blocks.AIR);
        int floorBlock = u < 0.45 ? Blocks.GRAVEL : (u < 0.8 ? Blocks.COARSE_DIRT : (u < 0.9 ? Blocks.MOSSY_COBBLE : BONE));
        if (floorMin < col.height - 1) b.set(px, floorMin, pz, floorBlock);
        if (topMax < col.height - 2) {
            double v = Hash.unit(h >>> 7);
            if (v < 0.004) b.set(px, topMax, pz, SOUL_LANTERN);
            else if (v < 0.03) b.set(px, topMax, pz, Blocks.id("cobweb"));
        }
        if (Hash.unit(h >>> 13) < 0.02 && floorMin < col.height - 2) {
            b.fill(px, floorMin + 1, floorMin + Hash.range(h, 1, 2), pz, BONE);
        }
        // A titan's ribcage in the main hall.
        if (main) {
            double rx = px - x, rz = pz - z;
            double len = Math.hypot(dirX, dirZ);
            double along = (rx * dirX + rz * dirZ) / len, across = (-rx * dirZ + rz * dirX) / len;
            int base = (int) (chambers[0][1] - chambers[0][4] * 0.45);
            if (Math.abs(along) <= 10 && Math.abs(Math.round(along) % 4) == 0) {
                double ac = Math.abs(across);
                int ry = -1;
                if (Math.abs(ac - 7) < 0.6) ry = 1;
                else if (Math.abs(ac - 6.5) < 0.6) ry = 6;
                else if (Math.abs(ac - 5) < 0.6) ry = 9;
                else if (Math.abs(ac - 3) < 0.6) ry = 11;
                if (ry == 1) b.fill(px, base + 1, base + 5, pz, BONE);
                else if (ry > 0) b.set(px, base + ry, pz, BONE);
            }
            if (Math.abs(across) < 0.6 && Math.abs(along) <= 12) b.set(px, base + 1, pz, BONE);
        }
        for (int[] c : chests) {
            if (c[0] == px && c[2] == pz) {
                int fy = floorMin;
                b.lootChest(px, fy + 1, pz, "north", CAVE_LOOT[c[3]]);
                b.set(px, fy + 1, pz + 1, Blocks.SOUL_LANTERN_FLOOR);
            }
        }
        // Rubble and bones at the mouth.
        double[] tn = tunnels[0];
        if (Math.hypot(px - tn[0], pz - tn[2]) < 10 && b.get(px, col.height, pz) != Blocks.AIR && Hash.unit(h >>> 21) < 0.08) {
            b.set(px, col.height + 1, pz, Hash.unit(h >>> 25) < 0.5 ? Blocks.MOSSY_COBBLE : BONE);
        }
    }

    // ---- Survey Corps expedition camp ----------------------------------------------------

    private void camp(ChunkBuffer b, int px, int pz, Column col) {
        int dx = px - x, dz = pz - z;
        double d = Math.hypot(dx, dz);
        if (d > 36 || col.underwater()) return;
        int h = col.height;
        long hh = Hash.of(seed, px, pz);
        b.fill(px, h + 1, h + 3, pz, Blocks.AIR);
        if (d < 30) {
            double u = Hash.unit(hh);
            b.set(px, h, pz, u < 0.35 ? Blocks.COARSE_DIRT : (u < 0.55 ? Blocks.DIRT_PATH : Blocks.GRASS));
        }
        // Palisade of fence posts with an opening facing downhill.
        if (d > 33.5 && d <= 34.6) {
            double ang = Math.atan2(dz, dx) - openAngle;
            ang = Math.atan2(Math.sin(ang), Math.cos(ang));
            if (Math.abs(ang) > 0.25) {
                b.set(px, h + 1, pz, Blocks.SPRUCE_FENCE);
                b.set(px, h + 2, pz, Blocks.SPRUCE_FENCE);
                if (Hash.unit(hh >>> 9) < 0.06) b.set(px, h + 3, pz, Blocks.TORCH);
            }
            return;
        }
        // Central fire with log benches.
        if (dx == 0 && dz == 0) { b.set(px, h + 1, pz, Blocks.CAMPFIRE); return; }
        if ((Math.abs(dx) == 3 && Math.abs(dz) <= 1)) { b.set(px, h + 1, pz, Blocks.OAK_LOG_Z); return; }
        if ((Math.abs(dz) == 3 && Math.abs(dx) <= 1)) { b.set(px, h + 1, pz, Blocks.OAK_LOG_X); return; }
        // Tents around the fire.
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3 + 0.3;
            int tx = x + (int) Math.round(Math.cos(a) * 17), tz = z + (int) Math.round(Math.sin(a) * 17);
            int lx = px - tx, lz = pz - tz;
            if (Math.abs(lx) > 2 || Math.abs(lz) > 3) continue;
            int wool = i % 2 == 0 ? Blocks.GREEN_WOOL : Blocks.WHITE_WOOL;
            int ax = Math.abs(lx);
            if (lz == -3 && ax < 2) return; // open front
            b.set(px, h + 3 - ax, pz, wool);
            if (ax == 2) b.set(px, h + 1, pz, wool);
            if (lx == 0 && lz == 2) b.set(px, h + 1, pz, Blocks.BARREL);
            return;
        }
        // Supply wagon with a loot chest.
        int wx = x + 16, wz = z - 16;
        if (Math.abs(px - wx) <= 1 && Math.abs(pz - wz) <= 3) {
            b.set(px, h + 1, pz, Blocks.SPRUCE_PLANKS);
            if (Math.abs(pz - wz) == 3 && Math.abs(px - wx) == 1) b.set(px, h + 1, pz, Blocks.id("dark_oak_fence"));
            if (px == wx && pz == wz) b.lootChest(px, h + 2, pz, "east", "minecraft:chests/village/village_weaponsmith");
            else if (Math.abs(pz - wz) <= 2 && Hash.unit(hh >>> 3) < 0.5) b.set(px, h + 2, pz, Blocks.BARREL);
            return;
        }
        // Horse corral with a trough.
        int cxr = x - 20, czr = z + 8;
        if (Math.abs(px - cxr) <= 7 && Math.abs(pz - czr) <= 7) {
            boolean edge = Math.abs(px - cxr) == 7 || Math.abs(pz - czr) == 7;
            if (edge) b.set(px, h + 1, pz, Math.abs(px - cxr) == 7 ? Blocks.SPRUCE_FENCE_Z : Blocks.SPRUCE_FENCE_X);
            else if (pz == czr - 5 && Math.abs(px - cxr) <= 2) b.set(px, h, pz, Blocks.WATER);
            else if (Hash.unit(hh >>> 5) < 0.04) b.set(px, h + 1, pz, Blocks.HAY);
            return;
        }
        // Watchtower with scaffolding lift and a green flag.
        int ox = x + 20, oz = z + 18;
        int lx = px - ox, lz = pz - oz;
        if (Math.abs(lx) <= 2 && Math.abs(lz) <= 2) {
            int top = h + 10;
            boolean corner = Math.abs(lx) == 2 && Math.abs(lz) == 2;
            if (corner) b.fill(px, h + 1, top + 2, pz, Blocks.SPRUCE_LOG);
            if (lx == 0 && lz == 0) b.fill(px, h + 1, top, pz, Blocks.SCAFFOLDING);
            else b.set(px, top, pz, Blocks.SPRUCE_PLANKS);
            if (!corner && (Math.abs(lx) == 2 || Math.abs(lz) == 2)) b.set(px, top + 1, pz, Blocks.SPRUCE_FENCE);
            if (corner && lx == 2 && lz == 2) {
                b.fill(px, top + 3, top + 5, pz, Blocks.SPRUCE_FENCE);
                b.set(px, top + 5, pz, Blocks.SPRUCE_LOG);
            }
            return;
        }
        if (px == ox + 3 && pz == oz + 2) b.fill(px, h + 13, h + 14, pz, Blocks.GREEN_WOOL);
        if (px == ox + 4 && pz == oz + 2) b.fill(px, h + 13, h + 14, pz, Blocks.WHITE_WOOL);
    }

    // ---- Small points of interest --------------------------------------------------------

    private void watchtower(ChunkBuffer b, int px, int pz, Column col) {
        double d = Math.hypot(px - x, pz - z);
        if (d > 3.6) return;
        int h = col.height;
        long hh = Hash.of(seed, px, pz);
        int height = 12 + Hash.range(seed, 0, 8);
        if (d > 2.4) {
            int top = h + height - (int) (Hash.unit(Hash.of(seed, px >> 1, pz >> 1)) * 9);
            for (int yy = h; yy <= top; yy++) b.set(px, yy, pz, Landmarks.stoneMix(seed, px, yy, pz));
            if (Math.round(px - x) == dirX * 3 && Math.round(pz - z) == dirZ * 3) b.fill(px, h + 1, h + 2, pz, Blocks.AIR);
        } else {
            b.fill(px, h + 1, h + height, pz, Blocks.AIR);
            b.set(px, h, pz, Blocks.COBBLE);
            if (Hash.unit(hh) < 0.5) b.set(px, h + 6, pz, Blocks.SPRUCE_PLANKS);
            if (px == x && pz == z) b.lootChest(px, h + 1, pz, "south", "minecraft:chests/village/village_toolsmith");
        }
    }

    private void campsite(ChunkBuffer b, int px, int pz, Column col) {
        int dx = px - x, dz = pz - z;
        if (Math.abs(dx) > 9 || Math.abs(dz) > 9 || col.underwater()) return;
        int h = col.height;
        b.fill(px, h + 1, h + 3, pz, Blocks.AIR);
        if (dx == 0 && dz == 0) { b.set(px, h + 1, pz, Blocks.CAMPFIRE); return; }
        if (Math.abs(dx) == 2 && dz == 0) { b.set(px, h + 1, pz, Blocks.OAK_LOG_Z); return; }
        for (int s = -1; s <= 1; s += 2) {
            int tx = x + s * 5, tz = z - 3;
            int lx = px - tx, lz = pz - tz;
            if (Math.abs(lx) > 1 || Math.abs(lz) > 2) continue;
            int wool = s < 0 ? Blocks.RED_WOOL : Blocks.BLUE_WOOL;
            if (lz == -2 && lx == 0) return;
            b.set(px, h + 2 - Math.abs(lx), pz, wool);
            return;
        }
        if (dx == 4 && dz == 4) b.lootChest(px, h + 1, pz, "west", "minecraft:chests/village/village_plains_house");
    }

    private void shrine(ChunkBuffer b, int px, int pz, Column col) {
        double d = Math.hypot(px - x, pz - z);
        if (d > 8 || col.underwater()) return;
        int h = col.height;
        long hh = Hash.of(seed, px, pz);
        b.fill(px, h + 1, h + 6, pz, Blocks.AIR);
        if (d > 5.5 && d < 6.5) {
            double a = Math.atan2(pz - z, px - x);
            double k = (a + Math.PI) / (Math.PI / 4);
            if (Math.abs(k - Math.round(k)) < 0.12) {
                int top = h + 3 + Hash.range(Hash.of(seed, Math.round(k)), 0, 2);
                for (int yy = h + 1; yy < top; yy++) b.set(px, yy, pz, Hash.unit(Hash.of(seed, px, yy, pz)) < 0.5 ? Blocks.MOSSY_STONE_BRICKS : Blocks.STONE_BRICKS);
                b.set(px, top, pz, Blocks.CHISELED_STONE_BRICKS);
            } else {
                b.set(px, h, pz, Blocks.GRAVEL);
            }
        } else if (d < 1) {
            b.set(px, h + 1, pz, Blocks.CHISELED_STONE_BRICKS);
            b.set(px, h + 2, pz, Blocks.LANTERN);
        } else if (d < 2.3) {
            b.set(px, h, pz, Blocks.MOSSY_STONE_BRICKS);
        } else if (Hash.unit(hh) < 0.15) {
            b.set(px, h + 1, pz, Hash.unit(hh >>> 4) < 0.5 ? Blocks.POPPY : Blocks.AZURE_BLUET);
        }
    }

    private void hermit(ChunkBuffer b, int px, int pz, Column col) {
        if (col.underwater()) return;
        int h = col.height;
        if (cabin.covers(px, pz)) { cabin.column(b, px, pz, h); return; }
        int dx = px - x, dz = pz - z;
        if (dz >= 5 && dz <= 10 && Math.abs(dx) <= 4) {
            boolean edge = dz == 5 || dz == 10 || Math.abs(dx) == 4;
            if (edge) b.set(px, h + 1, pz, Math.abs(dx) == 4 ? Blocks.SPRUCE_FENCE_Z : Blocks.SPRUCE_FENCE_X);
            else {
                b.set(px, h, pz, Blocks.FARMLAND);
                b.set(px, h + 1, pz, dz % 2 == 0 ? Blocks.CARROTS : Blocks.POTATOES);
            }
        }
        if (dx == 6 && dz == 0) b.set(px, h + 1, pz, Blocks.CAMPFIRE);
    }

    private void shipwreck(ChunkBuffer b, int px, int pz, Column col) {
        double rx = px - x, rz = pz - z;
        double len = Math.hypot(dirX, dirZ);
        double a = (rx * dirX + rz * dirZ) / len, c = (-rx * dirZ + rz * dirX) / len;
        double e = (a / 9) * (a / 9) + (c / 3.4) * (c / 3.4);
        if (e > 1) return;
        int h = Math.max(col.height, 60);
        long hh = Hash.of(seed, px, pz);
        boolean shell = e > 0.55;
        int broken = (int) (Hash.unit(Hash.of(seed, px >> 1, pz >> 1)) * 3);
        if (shell) {
            for (int yy = col.height + 1; yy <= h + 3 - broken; yy++) b.set(px, yy, pz, Blocks.SPRUCE_PLANKS);
        } else {
            b.set(px, col.height + 1, pz, Blocks.SPRUCE_PLANKS);
            if (Hash.unit(hh) < 0.5) b.set(px, h + 3, pz, Blocks.id("spruce_slab[type=bottom]"));
        }
        if (Math.abs(a) < 0.6 && Math.abs(c) < 0.6) b.fill(px, col.height + 2, h + 6, pz, Blocks.SPRUCE_LOG);
        if (Math.abs(a + 5) < 0.6 && Math.abs(c) < 0.6) b.lootChest(px, col.height + 2, pz, "north", "minecraft:chests/shipwreck_treasure");
    }
}
