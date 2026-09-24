package com.pglol.aotworld.core.build;

import com.pglol.aotworld.core.Atlas;
import com.pglol.aotworld.core.Blocks;
import com.pglol.aotworld.core.ChunkBuffer;
import com.pglol.aotworld.core.Column;
import com.pglol.aotworld.core.Feature;
import com.pglol.aotworld.core.Hash;

import static com.pglol.aotworld.core.WorldSpec.GATE_HALF;
import static com.pglol.aotworld.core.WorldSpec.WALL_BASE;
import static com.pglol.aotworld.core.WorldSpec.WALL_HALF;
import static com.pglol.aotworld.core.WorldSpec.WALL_TOP;

/**
 * One of the three great Walls: a 50-block ring with a walkway, crenellations,
 * cannons, gates at each district, scaffolding lift shafts and water gates
 * where rivers pass underneath. Each district bulges outward with its own
 * semicircular wall and outer gate.
 */
public final class WallFeature extends Feature {
    private final Atlas.Wall wall;
    private final long seed;
    private static final int SHAFT_SPACING = 640;

    public WallFeature(Atlas.Wall wall, long seed) {
        super(-(int) (wall.radius + wall.districts.get(0).radius + 10), -(int) (wall.radius + wall.districts.get(0).radius + 10),
            (int) (wall.radius + wall.districts.get(0).radius + 10), (int) (wall.radius + wall.districts.get(0).radius + 10));
        this.wall = wall;
        this.seed = seed;
    }

    @Override
    public int layer() {
        return 20;
    }

    @Override
    public boolean occupies(int x, int z) {
        double r = Math.sqrt((double) x * x + (double) z * z);
        if (Math.abs(r - wall.radius) <= WALL_HALF + 3) return true;
        for (Atlas.District d : wall.districts) {
            double dd = Math.hypot(x - d.cx, z - d.cz);
            if (Math.abs(dd - d.radius) <= WALL_HALF + 3 && r > wall.radius) return true;
        }
        return false;
    }

    @Override
    public void column(ChunkBuffer buf, int x, int z, Column col) {
        double r = Math.sqrt((double) x * x + (double) z * z);
        double t;
        int s;
        boolean main;
        Atlas.District gateDistrict = null;
        if (Math.abs(r - wall.radius) <= WALL_HALF + 0.5) {
            t = r - wall.radius;
            s = (int) Math.round(Math.atan2(z, x) * wall.radius);
            main = true;
        } else {
            Atlas.District hit = null;
            double dd = 0;
            for (Atlas.District d : wall.districts) {
                dd = Math.hypot(x - d.cx, z - d.cz);
                if (Math.abs(dd - d.radius) <= WALL_HALF + 0.5 && r > wall.radius) {
                    hit = d;
                    break;
                }
            }
            if (hit == null) return;
            t = dd - hit.radius;
            s = (int) Math.round(Math.atan2(z - hit.cz, x - hit.cx) * hit.radius);
            main = false;
            gateDistrict = hit;
        }
        int ti = (int) Math.max(-WALL_HALF, Math.min(WALL_HALF, Math.round(t)));

        // Gate: on the axis through a district, both in the main ring and the district's outer arc.
        int gateB = Integer.MAX_VALUE;
        for (Atlas.District d : wall.districts) {
            if (!main && d != gateDistrict) continue;
            double a = x * d.ux + z * d.uz;
            double b = -x * d.uz + z * d.ux;
            if (a > 0 && Math.abs(b) <= GATE_HALF + 4) {
                gateB = (int) Math.round(b);
                break;
            }
        }

        int bottom = Math.min(col.height, WALL_BASE) - 3;
        boolean face = Math.abs(ti) == WALL_HALF;
        for (int y = bottom; y < WALL_TOP; y++) {
            buf.set(x, y, z, face ? faceBlock(x, y, z) : Blocks.STONE);
        }
        buf.set(x, WALL_TOP, z, face ? Blocks.STONE_BRICKS : (Math.floorMod(s, 8) == 0 ? Blocks.STONE_BRICKS : Blocks.POLISHED_ANDESITE));

        // Parapet (outer) and railing (inner).
        if (ti == WALL_HALF) {
            buf.set(x, WALL_TOP + 1, z, Blocks.STONE_BRICKS);
            if (((s >> 1) & 1) == 0) buf.set(x, WALL_TOP + 2, z, Blocks.STONE_BRICKS);
        } else if (ti == -WALL_HALF) {
            buf.set(x, WALL_TOP + 1, z, Blocks.STONE_BRICK_WALL);
            if (Math.floorMod(s, 24) == 0) buf.set(x, WALL_TOP + 2, z, Blocks.LANTERN);
        }

        // Cannons every 96 blocks: a black barrel on a spruce carriage pointing outward.
        int cm = Math.floorMod(s, 96);
        if (cm <= 2 && ti >= 0 && ti < WALL_HALF) {
            if (ti <= 2) buf.set(x, WALL_TOP + 1, z, Blocks.SPRUCE_PLANKS);
            if (cm == 1) buf.set(x, WALL_TOP + 2, z, Blocks.POLISHED_BLACKSTONE);
        }
        if (cm == 1 && ti == WALL_HALF) buf.set(x, WALL_TOP + 2, z, Blocks.POLISHED_BLACKSTONE);

        // Rivers pass underneath through an arched water gate.
        if (col.river && col.water != Column.NO_WATER) {
            buf.fill(x, col.height + 1, col.water, z, Blocks.WATER);
            buf.fill(x, col.water + 1, col.water + 6, z, Blocks.AIR);
            if (face) buf.set(x, col.water + 7, z, Blocks.CHISELED_STONE_BRICKS);
        }

        // Gate arch.
        if (gateB != Integer.MAX_VALUE) {
            int ab = Math.abs(gateB);
            if (ab <= GATE_HALF) {
                int top = WALL_BASE + 12 + (int) Math.round(Math.sqrt(Math.max(0, 49 - gateB * gateB)));
                buf.fill(x, WALL_BASE + 1, top, z, Blocks.AIR);
                buf.set(x, WALL_BASE, z, Blocks.STONE_BRICKS);
                if (ti == 0) {
                    boolean xRun = Math.abs(z) > Math.abs(x);
                    buf.fill(x, top - 2, top, z, xRun ? Blocks.IRON_BARS_X : Blocks.IRON_BARS_Z);
                }
                if (face) buf.set(x, top + 1, z, Blocks.CHISELED_STONE_BRICKS);
            } else if (ab == GATE_HALF + 1 && face) {
                buf.fill(x, WALL_BASE + 1, WALL_BASE + 16, z, Blocks.CHISELED_STONE_BRICKS);
            } else if (ab == GATE_HALF + 3) {
                shaft(buf, x, z, ti);
            }
        } else if (Math.floorMod(s, SHAFT_SPACING) == 0) {
            shaft(buf, x, z, ti);
        }
    }

    /** Scaffolding lift up the inside of the wall with doorways on both faces. */
    private void shaft(ChunkBuffer buf, int x, int z, int ti) {
        if (Math.abs(ti) == WALL_HALF - 1) {
            buf.fill(x, WALL_BASE + 1, WALL_TOP, z, Blocks.SCAFFOLDING);
        } else if (Math.abs(ti) == WALL_HALF) {
            buf.fill(x, WALL_BASE + 1, WALL_BASE + 2, z, Blocks.AIR);
            buf.set(x, WALL_BASE, z, Blocks.STONE_BRICKS);
        }
    }

    private int faceBlock(int x, int y, int z) {
        double u = Hash.unit(Hash.of(seed, x, y, z));
        if (y < WALL_BASE + 6 && u < 0.25) return Blocks.MOSSY_STONE_BRICKS;
        if (y % 12 == 0) return u < 0.8 ? Blocks.STONE_BRICKS : Blocks.CRACKED_STONE_BRICKS;
        if (u < 0.42) return Blocks.STONE;
        if (u < 0.72) return Blocks.STONE_BRICKS;
        if (u < 0.86) return Blocks.ANDESITE;
        if (u < 0.95) return Blocks.CRACKED_STONE_BRICKS;
        return Blocks.TUFF;
    }
}
