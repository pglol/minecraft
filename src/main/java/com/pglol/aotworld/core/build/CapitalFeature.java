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
        palace = new House(-40, -26, 40, 26, true, BASE, 5, Style.PALACE, 1, false).use(House.Use.HALL);
        underground = new TownGrid(0, 0, Atlas.EAST, CAVE_FLOOR, Hash.of(seed, 77),
            (x, z) -> x * x + z * z < CAVE_TOWN_R * CAVE_TOWN_R, Style.UNDERGROUND, 1, 2, 26,
            new int[] {Blocks.COBBLE, Blocks.GRAVEL, Blocks.MOSSY_COBBLE, Blocks.ANDESITE}).noStables();
    }

    /** The Underground City's street grid (for the home list). */
    public TownGrid undergroundGrid() {
        return underground;
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
        plaza(buf, x, z);
        underground(buf, x, z);
        tunnel(buf, x, z);
    }

    // ---- Palace plaza: patterned paving, lamps, tree planters, flowers and benches ----------

    private static final int PLAZA_R = 94;
    private static final int[][] LAMPS = ring(88, 24, 0);
    private static final int[][] TREES = ring(72, 18, 0);
    private static final int[][] FLOWERS = ring(72, 18, 10);
    private static final int[][] BENCHES = ring(78, 18, 10);

    private static int[][] ring(double r, int count, double offsetDeg) {
        int[][] out = new int[count][];
        for (int i = 0; i < count; i++) {
            double a = Math.toRadians(offsetDeg + i * 360.0 / count);
            out[i] = new int[] {(int) Math.round(Math.cos(a) * r), (int) Math.round(Math.sin(a) * r)};
        }
        return out;
    }

    /** Keep decoration off the palace, its towers, the garden and the main avenues. */
    private static boolean plazaFree(int x, int z) {
        if (Math.abs(x) <= 50 && Math.abs(z) <= 38) return false;
        if (Math.abs(x) <= 34 && z > 26 && z < 96) return false;
        return Math.abs(x) > 3 && Math.abs(z) > 3;
    }

    private void plaza(ChunkBuffer buf, int x, int z) {
        double d = Math.hypot(x, z);
        if (d > PLAZA_R + 3 || !plazaFree(x, z)) return;
        int y = BASE;
        // Paving pattern: rings and spokes in a lighter stone.
        double ring = d % 14;
        double ang = Math.toDegrees(Math.atan2(z, x));
        double spoke = Math.abs(((ang % 30) + 30) % 30 - 15);
        if (d <= PLAZA_R) {
            if (ring < 1) buf.set(x, y, z, Blocks.SMOOTH_STONE);
            else if (d > 56 && spoke * d / 57.3 > 14.2) buf.set(x, y, z, Blocks.POLISHED_ANDESITE);
            if (d > PLAZA_R - 1.2) buf.set(x, y, z, Blocks.STONE_BRICKS);
            // A lawn band around the square with low hedges and flowers, broken by the avenues.
            if (d >= 67.5 && d <= 76.5 && spoke > 2.2) {
                buf.set(x, y, z, Blocks.GRASS);
                if (d < 68.4 || d > 75.6) buf.set(x, y + 1, z, Blocks.OAK_LEAVES);
                else {
                    double u = Hash.unit(Hash.of(seed, x, z, 9));
                    if (u < 0.10) buf.set(x, y + 1, z, Blocks.POPPY);
                    else if (u < 0.18) buf.set(x, y + 1, z, Blocks.CORNFLOWER);
                    else if (u < 0.24) buf.set(x, y + 1, z, Blocks.DANDELION);
                    else if (u < 0.40) buf.set(x, y + 1, z, Blocks.SHORT_GRASS);
                }
            }
        }
        for (int[] l : LAMPS) {
            if (l[0] == x && l[1] == z && plazaFree(x, z)) {
                buf.fill(x, y + 1, y + 3, z, Blocks.id("dark_oak_fence"));
                buf.set(x, y + 4, z, Blocks.LANTERN);
                return;
            }
        }
        for (int[] t : TREES) {
            if (!plazaFree(t[0], t[1])) continue;
            int dx = x - t[0], dz = z - t[1];
            int ax = Math.abs(dx), az = Math.abs(dz);
            if (ax <= 2 && az <= 2) {
                double r = Math.hypot(dx, dz);
                if (r <= 2.6) {
                    buf.set(x, y + 5, z, Blocks.OAK_LEAVES);
                    buf.set(x, y + 6, z, Blocks.OAK_LEAVES);
                    if (r <= 1.5) buf.set(x, y + 7, z, Blocks.OAK_LEAVES);
                }
                if (ax <= 1 && az <= 1) {
                    buf.set(x, y, z, Blocks.GRASS);
                    if (dx == 0 && dz == 0) buf.fill(x, y + 1, y + 5, z, Blocks.OAK_LOG);
                    else buf.set(x, y + 1, z, Blocks.id("stone_brick_slab[type=bottom]"));
                }
            }
        }
        for (int[] f : FLOWERS) {
            if (!plazaFree(f[0], f[1])) continue;
            if (Math.abs(x - f[0]) <= 1 && Math.abs(z - f[1]) <= 0) {
                buf.set(x, y, z, Blocks.GRASS);
                buf.set(x, y + 1, z, Blocks.id("flowering_azalea"));
            }
        }
        for (int[] b : BENCHES) {
            if (!plazaFree(b[0], b[1])) continue;
            // Two seats side by side along the ring, backs to the outside.
            boolean alongX = Math.abs(b[1]) > Math.abs(b[0]);
            int ox = alongX ? 1 : 0, oz = alongX ? 0 : 1;
            if ((x == b[0] && z == b[1]) || (x == b[0] + ox && z == b[1] + oz)) {
                String facing = alongX ? (b[1] > 0 ? "south" : "north") : (b[0] > 0 ? "east" : "west");
                buf.set(x, y + 1, z, Blocks.id("spruce_stairs[facing=" + facing + ",half=bottom]"));
            }
        }
    }

    /**
     * Cavern roof height. Over the city it stays at least 32 blocks above the floor so no house or
     * chimney ever reaches the rock; past the city edge it slopes down to the cave wall.
     */
    private static int ceiling(double d) {
        int dome = (int) (48 - (d / CAVE_R) * (d / CAVE_R) * 30);
        int town = CAVE_FLOOR + 32;
        if (d <= CAVE_TOWN_R + 8) return Math.max(dome, town);
        double t = Math.min(1, (d - CAVE_TOWN_R - 8) / 24);
        return Math.max(dome, (int) Math.round(town + (dome - town) * t));
    }

    private void underground(ChunkBuffer buf, int x, int z) {
        double d = Math.hypot(x, z);
        if (d >= CAVE_R) return;
        int ceiling = ceiling(d);
        buf.set(x, CAVE_FLOOR, z, Blocks.STONE);
        buf.fill(x, CAVE_FLOOR + 1, ceiling - 1, z, Blocks.AIR);
        long h = Hash.of(seed, x, z, 5);
        if (Hash.unit(h) < 0.02) buf.set(x, ceiling, z, Blocks.GLOWSTONE);
        if (d < CAVE_TOWN_R) underground.column(buf, x, z, CAVE_FLOOR);
        else if (Hash.unit(Hash.mix(h + 3)) < 0.05) buf.fill(x, CAVE_FLOOR + 1, CAVE_FLOOR + Hash.range(h, 1, 3), z, Blocks.COBBLE);
    }

    private void tunnel(ChunkBuffer buf, int x, int z) {
        if (x < TUNNEL_X0 || x > TUNNEL_X1 + 3) return;
        int dz = Math.abs(z - TUNNEL_Z);
        if (dz > 3) return;
        int gate = TUNNEL_X1 - 7; // the gatehouse covers the last stretch of the stairway
        if (x >= gate) {
            gatehouse(buf, x, z, dz);
            if (x > TUNNEL_X1) return;
        }
        int floor = Math.min(BASE, CAVE_FLOOR + (x - TUNNEL_X0));
        boolean under = floor + 6 <= BASE;
        if (dz == 3) {
            // Side walls; below ground they reach the tunnel roof.
            buf.fill(x, floor, under ? floor + 6 : BASE, z, Blocks.STONE_BRICKS);
            return;
        }
        buf.set(x, floor, z, floor >= BASE ? Blocks.STONE_BRICKS : Blocks.id("stone_brick_stairs[facing=east,half=bottom]"));
        if (under) {
            // Enclosed tunnel: air inside, a stone roof, the surface above left intact.
            buf.fill(x, floor + 1, floor + 5, z, Blocks.AIR);
            buf.set(x, floor + 6, z, Blocks.STONE_BRICKS);
            if (dz == 0 && x % 8 == 0) buf.set(x, floor + 5, z, Blocks.LANTERN_HANGING);
        } else {
            buf.fill(x, floor + 1, BASE + 3, z, Blocks.AIR);
        }
    }

    /** A small roofed stone gatehouse over the top of the stairway, with a sign. */
    private void gatehouse(ChunkBuffer buf, int x, int z, int dz) {
        int x1 = TUNNEL_X1 + 2;
        if (x > x1) {
            // Sign just outside the arch, facing the street.
            if (dz == 2 && z > TUNNEL_Z) buf.sign(x, BASE + 1, z, 12, "Underground", "City", "\u2193", "");
            return;
        }
        boolean front = x == x1;
        if (dz == 3) {
            boolean pillar = x == TUNNEL_X1 - 7 || front || x == TUNNEL_X1 - 3;
            buf.fill(x, BASE + 1, BASE + 4, z, pillar ? Blocks.id("chiseled_stone_bricks") : Blocks.STONE_BRICKS);
            if (pillar && x == x1) buf.set(x, BASE + 3, z, Blocks.LANTERN);
        } else if (front) {
            buf.set(x, BASE, z, Blocks.STONE_BRICKS);
            if (dz == 2) buf.fill(x, BASE + 4, BASE + 4, z, Blocks.STONE_BRICKS);
        }
        buf.set(x, BASE + 5, z, Blocks.id("stone_brick_slab[type=bottom]"));
        if (dz <= 2) buf.set(x, BASE + 5, z, Blocks.STONE_BRICKS);
        if (dz == 0 && !front) buf.set(x, BASE + 6, z, Blocks.id("stone_brick_slab[type=bottom]"));
        if (dz == 0 && x == TUNNEL_X1 - 2) buf.set(x, BASE + 4, z, Blocks.LANTERN_HANGING);
    }
}
