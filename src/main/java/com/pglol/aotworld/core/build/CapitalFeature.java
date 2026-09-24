package com.pglol.aotworld.core.build;

import com.pglol.aotworld.core.Atlas;
import com.pglol.aotworld.core.Blocks;
import com.pglol.aotworld.core.ChunkBuffer;
import com.pglol.aotworld.core.Column;
import com.pglol.aotworld.core.Hash;
import com.pglol.aotworld.core.WorldSpec;

/**
 * Mitras, the royal capital at the centre of the walls: noble mansions, the
 * royal palace, and a stairway down to the Underground City.
 */
public final class CapitalFeature extends TownFeature {
    private static final int BASE = WorldSpec.WALL_BASE;
    private static final int CAVE_FLOOR = 10, CAVE_R = 260, CAVE_TOWN_R = 200;
    private static final int TUNNEL_X0 = 258, TUNNEL_X1 = 320, TUNNEL_Z = 20;

    private final House palace;
    private final int[][] towers = {{-44, -30}, {44, -30}, {-44, 30}, {44, 30}};
    private final TownGrid underground;
    private final long seed;

    public CapitalFeature(Atlas atlas, long seed) {
        super(new TownGrid(0, 0, Atlas.EAST, BASE, seed,
                (x, z) -> x * x + z * z < (atlas.capitalRadius - 8) * (atlas.capitalRadius - 8),
                Style.CAPITAL, 3, 5, 34,
                new int[] {Blocks.STONE_BRICKS, Blocks.POLISHED_ANDESITE, Blocks.POLISHED_ANDESITE, Blocks.SMOOTH_STONE})
                .plaza(0, 0, 95)
                .exclude(TUNNEL_X0 - 8, TUNNEL_Z - 8, TUNNEL_X1 + 10, TUNNEL_Z + 8).plots(0.05),
            -(int) atlas.capitalRadius, -(int) atlas.capitalRadius, (int) atlas.capitalRadius, (int) atlas.capitalRadius);
        this.seed = seed;
        palace = new House(-40, -26, 40, 26, true, BASE, 5, Style.PALACE, 1, false);
        underground = new TownGrid(0, 0, Atlas.EAST, CAVE_FLOOR, Hash.of(seed, 77),
            (x, z) -> x * x + z * z < CAVE_TOWN_R * CAVE_TOWN_R, Style.UNDERGROUND, 1, 2, 26,
            new int[] {Blocks.COBBLE, Blocks.GRAVEL, Blocks.MOSSY_COBBLE, Blocks.ANDESITE});
    }

    @Override
    protected void extra(ChunkBuffer buf, int x, int z, Column col) {
        if (palace.covers(x, z)) palace.column(buf, x, z, BASE);
        for (int[] t : towers) {
            if (Tower.covers(x, z, t[0], t[1], 6)) {
                Tower.column(buf, x, z, t[0], t[1], 6, BASE, 44, Blocks.SMOOTH_QUARTZ, Blocks.id("deepslate_tiles"), BASE);
            }
        }
        // Palace garden in front of the south door.
        if (Math.abs(x) <= 30 && z > 30 && z < 90) {
            double d = Math.hypot(x, z - 60);
            if (d <= 1.3) {
                buf.fill(x, BASE + 1, BASE + 4, z, Blocks.QUARTZ_PILLAR);
                buf.set(x, BASE + 5, z, Blocks.SEA_LANTERN);
            } else if (d <= 5.2) {
                buf.set(x, BASE, z, Blocks.WATER);
            } else if (d <= 6.2) {
                buf.set(x, BASE + 1, z, Blocks.SMOOTH_QUARTZ);
            } else if (Math.abs(x) >= 6 && Math.abs(x) <= 28 && z % 6 != 0) {
                buf.set(x, BASE, z, Blocks.GRASS);
                if (Math.abs(x) == 6 || Math.abs(x) == 28) buf.set(x, BASE + 1, z, Blocks.OAK_LEAVES);
                else if (Hash.unit(Hash.of(seed, x, z)) < 0.2) buf.set(x, BASE + 1, z, Blocks.POPPY);
            }
        }
        underground(buf, x, z);
        tunnel(buf, x, z);
    }

    private void underground(ChunkBuffer buf, int x, int z) {
        double d = Math.hypot(x, z);
        if (d >= CAVE_R) return;
        int ceiling = (int) (48 - (d / CAVE_R) * (d / CAVE_R) * 30);
        buf.set(x, CAVE_FLOOR, z, Blocks.STONE);
        buf.fill(x, CAVE_FLOOR + 1, ceiling - 1, z, Blocks.AIR);
        long h = Hash.of(seed, x, z, 5);
        if (Hash.unit(h) < 0.02) buf.set(x, ceiling, z, Blocks.GLOWSTONE);
        if (d < CAVE_TOWN_R) underground.column(buf, x, z, CAVE_FLOOR);
        else if (Hash.unit(h >>> 3) < 0.05) buf.fill(x, CAVE_FLOOR + 1, CAVE_FLOOR + Hash.range(h, 1, 3), z, Blocks.COBBLE);
    }

    private void tunnel(ChunkBuffer buf, int x, int z) {
        if (x < TUNNEL_X0 || x > TUNNEL_X1) return;
        int dz = Math.abs(z - TUNNEL_Z);
        if (dz > 3) return;
        int floor = CAVE_FLOOR + (x - TUNNEL_X0);
        if (dz == 3) {
            if (floor + 6 <= BASE) buf.fill(x, floor, floor + 6, z, Blocks.STONE_BRICKS);
            else {
                buf.fill(x, floor, BASE, z, Blocks.STONE_BRICKS);
                buf.set(x, BASE + 1, z, dz == 3 && z > TUNNEL_Z ? Blocks.SPRUCE_FENCE_X : Blocks.SPRUCE_FENCE_X);
            }
            return;
        }
        buf.set(x, floor, z, Blocks.id("stone_brick_stairs[facing=east,half=bottom]"));
        buf.fill(x, floor + 1, Math.max(floor + 5, BASE + 2), z, Blocks.AIR);
        if (floor + 6 <= BASE) {
            buf.set(x, floor + 6, z, Blocks.STONE_BRICKS);
            if (dz == 0 && x % 8 == 0) buf.set(x, floor + 5, z, Blocks.LANTERN_HANGING);
        }
    }
}
