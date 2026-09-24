package com.pglol.aotworld.core.build;

import com.pglol.aotworld.core.Atlas;
import com.pglol.aotworld.core.Blocks;
import com.pglol.aotworld.core.ChunkBuffer;
import com.pglol.aotworld.core.Column;
import com.pglol.aotworld.core.Feature;
import com.pglol.aotworld.core.Hash;

/** The hand-designed landmark sites, each rendered column by column. */
public final class Landmarks {
    private Landmarks() {}

    public static Feature create(Atlas.Site s, long seed) {
        switch (s.kind) {
            case GIANT_FOREST: return new GiantForestFloor(s, seed);
            case UTGARD: return new Utgard(s, seed);
            case REISS_CHAPEL: return new ReissChapel(s, seed);
            case SURVEY_HQ: return new SurveyHQ(s, seed);
            case TRAINING_CAMP: return new TrainingCamp(s, seed);
            case PARADIS_PORT: return new Port(s, seed, false);
            case MARLEY_PORT: return new Port(s, seed, true);
            case LIBERIO: return new Liberio(s, seed);
            case MILITARY_BASE: return new MilitaryBase(s, seed);
            default: return null;
        }
    }

    static int stoneMix(long seed, int x, int y, int z) {
        double u = Hash.unit(Hash.of(seed, x, y, z));
        if (u < 0.5) return Blocks.STONE_BRICKS;
        if (u < 0.7) return Blocks.CRACKED_STONE_BRICKS;
        if (u < 0.88) return Blocks.MOSSY_STONE_BRICKS;
        return Blocks.COBBLE;
    }

    private abstract static class SiteFeature extends Feature {
        final Atlas.Site site;
        final long seed;
        final int base;

        SiteFeature(Atlas.Site s, long seed, int reach) {
            super(s.x - reach, s.z - reach, s.x + reach, s.z + reach);
            this.site = s;
            this.seed = Hash.of(seed, s.x, s.z);
            this.base = (int) Math.round(Double.isNaN(s.target) ? 72 : s.target);
        }

        @Override
        public int layer() {
            return 8;
        }

        @Override
        public boolean occupies(int x, int z) {
            return Math.hypot(x - site.x, z - site.z) < site.radius;
        }
    }

    /** Mossy forest floor; the giant trees themselves are placed by the tree pass. */
    static final class GiantForestFloor extends SiteFeature {
        GiantForestFloor(Atlas.Site s, long seed) {
            super(s, seed, s.radius);
        }

        @Override
        public boolean occupies(int x, int z) {
            return false;
        }

        @Override
        public void column(ChunkBuffer buf, int x, int z, Column col) {
            if (col.underwater() || col.road >= 0) return;
            if (Math.hypot(x - site.x, z - site.z) > site.radius) return;
            if (buf.get(x, col.height, z) != Blocks.GRASS) return;
            double u = Hash.unit(Hash.of(seed, x >> 2, z >> 2));
            buf.set(x, col.height, z, u < 0.3 ? Blocks.PODZOL : (u < 0.55 ? Blocks.MOSS : (u < 0.62 ? Blocks.COARSE_DIRT : Blocks.GRASS)));
        }
    }

    /** Utgard Castle: a ruined keep inside Wall Rose. */
    static final class Utgard extends SiteFeature {
        Utgard(Atlas.Site s, long seed) {
            super(s, seed, 30);
        }

        @Override
        public void column(ChunkBuffer buf, int x, int z, Column col) {
            int dx = x - site.x, dz = z - site.z;
            int cheb = Math.max(Math.abs(dx), Math.abs(dz));
            double dmg = Hash.unit(Hash.of(seed, x >> 1, z >> 1));
            if (cheb <= 9) {
                buf.fill(x, base + 1, base + 34, z, Blocks.AIR);
                if (cheb >= 8) {
                    int top = base + 30 - (int) (dmg * 9);
                    if (dmg < 0.12) top = base + 3 + (int) (dmg * 30);
                    for (int y = base; y <= top; y++) {
                        boolean slit = cheb == 9 && y % 6 == 3 && Math.floorMod(dx + dz, 5) == 2;
                        boolean door = dz == 9 && Math.abs(dx) <= 1 && y <= base + 3 && y > base;
                        buf.set(x, y, z, slit || door ? Blocks.AIR : stoneMix(seed, x, y, z));
                    }
                    if (cheb == 9 && top >= base + 26 && Math.floorMod(dx + dz, 2) == 0) buf.set(x, top + 1, z, stoneMix(seed, x, top + 1, z));
                } else {
                    buf.set(x, base, z, Blocks.COBBLE);
                    for (int y = base + 6; y < base + 30; y += 6) {
                        if (Hash.unit(Hash.of(seed, x, y, z)) < 0.72) buf.set(x, y, z, Blocks.SPRUCE_PLANKS);
                    }
                    if (dx == -7 && dz == -7) buf.fill(x, base + 1, base + 26, z, Blocks.id("ladder[facing=south]"));
                    if (dx == -7 && dz == -8) buf.fill(x, base + 1, base + 26, z, Blocks.STONE_BRICKS);
                }
            } else if (cheb >= 23 && cheb <= 24) {
                boolean gate = dz > 0 && Math.abs(dx) <= 2;
                if (gate) return;
                int top = base + 8 - (int) (dmg * 4);
                if (dmg < 0.2) top = base + 1;
                for (int y = base; y <= top; y++) buf.set(x, y, z, stoneMix(seed, x, y, z));
            } else if (cheb < 23 && Hash.unit(Hash.of(seed, x, z)) < 0.03) {
                buf.set(x, base + 1, z, Blocks.MOSSY_COBBLE);
            }
        }
    }

    /** The Reiss family chapel, with the crystal cavern hidden beneath it. */
    static final class ReissChapel extends SiteFeature {
        private final House chapel;
        private static final int CAVE_Y = 18, CAVE_RH = 40, CAVE_RV = 22;

        ReissChapel(Atlas.Site s, long seed) {
            super(s, seed, 44);
            chapel = new House(s.x - 6, s.z - 11, s.x + 5, s.z + 10, false, base, 2, Style.CHAPEL, 1, false);
        }

        @Override
        public void column(ChunkBuffer buf, int x, int z, Column col) {
            int dx = x - site.x, dz = z - site.z;
            double d = Math.hypot(dx, dz);
            if (d < CAVE_RH) {
                double vh = CAVE_RV * Math.sqrt(1 - (d / CAVE_RH) * (d / CAVE_RH));
                int floor = Math.max((int) (CAVE_Y - vh), 6), ceil = (int) (CAVE_Y + vh);
                if (ceil > floor + 2) {
                    long h = Hash.of(seed, x, z);
                    double u = Hash.unit(h);
                    buf.set(x, floor, z, u < 0.5 ? Blocks.CALCITE : Blocks.SMOOTH_QUARTZ);
                    buf.fill(x, floor + 1, ceil - 1, z, Blocks.AIR);
                    int shell = u < 0.1 ? Blocks.SEA_LANTERN : (u < 0.45 ? Blocks.AMETHYST : Blocks.CALCITE);
                    buf.fill(x, ceil, ceil + 1, z, shell);
                    if (u > 0.985) buf.fill(x, floor + 1, ceil - 1, z, Hash.unit(h >>> 5) < 0.5 ? Blocks.AMETHYST : Blocks.CALCITE);
                    if (d < 1.5) {
                        buf.fill(x, floor + 1, floor + 3, z, Blocks.QUARTZ_PILLAR);
                        buf.set(x, floor + 4, z, Blocks.SEA_LANTERN);
                    }
                    if (dx == 3 && dz == 1) buf.fill(x, floor + 1, ceil, z, Blocks.CALCITE);
                    if (dx == 3 && dz == 0) buf.fill(x, floor + 1, base, z, Blocks.id("ladder[facing=north]"));
                }
            }
            if (chapel.covers(x, z)) {
                chapel.column(buf, x, z, col.height);
                if (dx == 3 && dz == 0) buf.set(x, base, z, Blocks.id("ladder[facing=north]"));
                if (dx == 3 && dz == 1) buf.set(x, base, z, Blocks.STONE_BRICKS);
            }
            if (dx >= -2 && dx <= 2 && dz >= 12 && dz <= 16) {
                int top = base + 22;
                boolean edge = Math.abs(dx) == 2 || dz == 12 || dz == 16;
                if (col.height < base) buf.fill(x, col.height, base - 1, z, Blocks.COBBLE);
                buf.fill(x, base + 1, top + 4, z, Blocks.AIR);
                if (edge) {
                    buf.fill(x, base, top, z, Blocks.STONE_BRICKS);
                    boolean open = (dx == 0 || dz == 14);
                    if (open) buf.fill(x, top - 4, top - 2, z, Blocks.AIR);
                    if (dz == 16 && dx == 0) buf.fill(x, base + 1, base + 2, z, Blocks.AIR);
                } else {
                    buf.set(x, base, z, Blocks.COBBLE);
                    buf.set(x, top - 5, z, Blocks.SPRUCE_PLANKS);
                    if (dx == 0 && dz == 14) buf.set(x, top - 1, z, Blocks.id("bell[attachment=ceiling,facing=north]"));
                }
                int e = Math.min(2 - Math.abs(dx), Math.min(dz - 12, 16 - dz));
                buf.fill(x, top + 1, top + 1 + e, z, Blocks.id("deepslate_tiles"));
            }
        }
    }

    /** The old Survey Corps headquarters castle. */
    static final class SurveyHQ extends SiteFeature {
        private final House keep, stable;

        SurveyHQ(Atlas.Site s, long seed) {
            super(s, seed, 34);
            keep = new House(s.x - 12, s.z - 22, s.x + 11, s.z - 7, true, base, 3, Style.CASTLE, 1, true);
            stable = new House(s.x + 2, s.z + 8, s.x + 23, s.z + 15, true, base, 1, Style.RURAL[0], -1, false);
        }

        @Override
        public void column(ChunkBuffer buf, int x, int z, Column col) {
            int dx = x - site.x, dz = z - site.z;
            int cheb = Math.max(Math.abs(dx), Math.abs(dz));
            if (cheb <= 31) {
                if (col.height < base) buf.fill(x, col.height, base - 1, z, Blocks.DIRT);
                buf.fill(x, base + 1, Math.max(col.height, base + 1), z, Blocks.AIR);
                buf.set(x, base, z, cheb >= 29 ? Blocks.COBBLE : (Math.abs(dx) <= 1 ? Blocks.GRAVEL : Blocks.GRASS));
            }
            if (cheb >= 29 && cheb <= 30) {
                boolean gate = dz > 0 && Math.abs(dx) <= 2;
                if (!gate) {
                    buf.fill(x, base, base + 8, z, Blocks.STONE_BRICKS);
                    if (cheb == 30 && Math.floorMod(dx + dz, 2) == 0) buf.set(x, base + 9, z, Blocks.STONE_BRICKS);
                }
                return;
            }
            if (keep.covers(x, z)) keep.column(buf, x, z, base);
            if (stable.covers(x, z)) stable.column(buf, x, z, base);
            if (Tower.covers(x, z, site.x - 20, site.z - 20, 5)) {
                Tower.column(buf, x, z, site.x - 20, site.z - 20, 5, base, 34, Blocks.STONE_BRICKS, Blocks.id("deepslate_tiles"), base);
            }
            if (dz >= 0 && dz <= 4 && dx <= -6 && dx >= -24 && Math.floorMod(dx, 4) == 0) {
                if (dz == 2) {
                    buf.fill(x, base + 1, base + 2, z, Blocks.SPRUCE_FENCE);
                    buf.set(x, base + 3, z, Blocks.HAY);
                }
            }
        }
    }

    /** The 104th Cadet Corps training camp: barracks, mess hall and a field of ODM posts. */
    static final class TrainingCamp extends SiteFeature {
        private final House[] buildings;

        TrainingCamp(Atlas.Site s, long seed) {
            super(s, seed, 80);
            int x = s.x, z = s.z;
            buildings = new House[] {
                new House(x - 62, z - 62, x - 39, z - 54, true, base, 1, Style.RURAL[0], 1, true),
                new House(x - 32, z - 62, x - 9, z - 54, true, base, 1, Style.RURAL[0], 1, true),
                new House(x - 62, z - 44, x - 39, z - 36, true, base, 1, Style.RURAL[1], 1, true),
                new House(x - 32, z - 44, x - 9, z - 36, true, base, 1, Style.RURAL[1], 1, true),
                new House(x + 10, z - 62, x + 33, z - 47, true, base, 2, Style.RURAL[2], 1, true),
                new House(x + 42, z - 62, x + 57, z - 49, true, base, 2, Style.PARADIS[0], 1, true),
            };
        }

        @Override
        public void column(ChunkBuffer buf, int x, int z, Column col) {
            int dx = x - site.x, dz = z - site.z;
            int cheb = Math.max(Math.abs(dx), Math.abs(dz));
            if (cheb > 76) return;
            if (col.height < base) buf.fill(x, col.height, base - 1, z, Blocks.DIRT);
            buf.fill(x, base + 1, Math.max(col.height, base + 1), z, Blocks.AIR);
            boolean field = dz >= -20 && dz <= 70 && Math.abs(dx) <= 70;
            buf.set(x, base, z, field ? (Hash.unit(Hash.of(seed, x, z)) < 0.6 ? Blocks.COARSE_DIRT : Blocks.GRASS) : Blocks.GRASS);
            if (cheb == 76) {
                boolean gate = dz > 0 && Math.abs(dx) <= 3;
                if (!gate) buf.set(x, base + 1, z, Math.abs(dx) == 76 ? Blocks.SPRUCE_FENCE_Z : Blocks.SPRUCE_FENCE_X);
                return;
            }
            for (House h : buildings) if (h.covers(x, z)) { h.column(buf, x, z, base); return; }
            if (Math.abs(dx) <= 1 && dz > -30) buf.set(x, base, z, Blocks.DIRT_PATH);
            if (field && Math.floorMod(dx, 12) == 6 && Math.floorMod(dz, 12) == 6) {
                long h = Hash.of(seed, dx, dz);
                int top = base + Hash.range(h, 8, 16);
                buf.fill(x, base + 1, top, z, Blocks.SPRUCE_LOG);
                buf.set(x, top + 1, z, Blocks.HAY);
            }
        }
    }

    /** Harbour town: Paradis Port or Marley's port city with the military headquarters. */
    static final class Port extends SiteFeature {
        private final TownGrid grid;
        private final boolean marley;
        private final House hq;
        private final int pierEnd;

        Port(Atlas.Site s, long seed, boolean marley) {
            super(s, seed, s.radius + 90);
            this.marley = marley;
            int r = s.radius - 12;
            grid = new TownGrid(s.x, s.z, s.seaDir, base, this.seed,
                (x, z) -> Math.hypot(x - s.x, z - s.z) < r, marley ? Style.MARLEY : Style.PARADIS,
                marley ? 3 : 2, marley ? 5 : 3, marley ? 32 : 30,
                marley ? new int[] {Blocks.SMOOTH_STONE, Blocks.STONE_BRICKS, Blocks.POLISHED_ANDESITE}
                       : new int[] {Blocks.COBBLE, Blocks.COBBLE, Blocks.ANDESITE, Blocks.STONE, Blocks.MOSSY_COBBLE});
            if (marley) {
                grid.plaza(-s.radius * 0.45, 0, 48);
                int px = grid.worldX((int) (-s.radius * 0.45), 0), pz = grid.worldZ((int) (-s.radius * 0.45), 0);
                hq = new House(px - 24, pz - 15, px + 24, pz + 15, true, base, 5, Style.MARLEY_GRAND, 1, false);
            } else {
                grid.plaza(-s.radius * 0.35, 0, 16);
                hq = null;
            }
            pierEnd = s.radius + 60;
        }

        @Override
        public boolean occupies(int x, int z) {
            return Math.hypot(x - site.x, z - site.z) < site.radius + 70;
        }

        @Override
        public void column(ChunkBuffer buf, int x, int z, Column col) {
            int a = grid.localA(x, z), b = grid.localB(x, z);
            if (!col.underwater()) {
                if (col.sd > 4 && grid.shape.inside(x, z)) grid.column(buf, x, z, col.height);
                if (hq != null && hq.covers(x, z)) hq.column(buf, x, z, base);
                return;
            }
            // Piers reaching out to sea.
            if (a < 0 || a > pierEnd) return;
            int pb = Math.floorMod(b + 2, 60) - 2;
            if (Math.abs(b) > site.radius - 20) return;
            int deck = 66;
            if (Math.abs(pb) <= 2) {
                buf.set(x, deck, z, Blocks.SPRUCE_PLANKS);
                if (Math.abs(pb) == 2 && Math.floorMod(a, 4) == 0) {
                    buf.fill(x, col.height, deck - 1, z, Blocks.SPRUCE_LOG);
                    buf.set(x, deck + 1, z, Blocks.SPRUCE_FENCE);
                    if (Math.floorMod(a, 16) == 0) buf.set(x, deck + 2, z, Blocks.LANTERN);
                }
            }
            if (!marley) {
                double d = Math.hypot(a - (pierEnd + 6), b);
                if (d <= 4.5) {
                    int top = deck + 28;
                    buf.fill(x, col.height, deck, z, Blocks.STONE_BRICKS);
                    if (d > 3.3) {
                        for (int y = deck + 1; y <= top; y++) buf.set(x, y, z, ((y - deck) / 4) % 2 == 0 ? Blocks.WHITE_CONCRETE : Blocks.RED_CONCRETE);
                    }
                    buf.set(x, top, z, Blocks.STONE_BRICKS);
                    if (d <= 2.5) buf.fill(x, top + 1, top + 3, z, Blocks.GLOWSTONE);
                    else if (d <= 3.5) buf.set(x, top + 1, z, Blocks.IRON_BARS_X);
                    buf.set(x, top + 4, z, Blocks.id("deepslate_tiles"));
                } else if (Math.abs(b) <= 2 && a > pierEnd && a < pierEnd + 3) {
                    buf.set(x, deck, z, Blocks.SPRUCE_PLANKS);
                }
            }
        }
    }

    /** Liberio: the walled Eldian internment zone. */
    static final class Liberio extends SiteFeature {
        private static final int HX = 220, HZ = 170;
        private final TownGrid grid;

        Liberio(Atlas.Site s, long seed) {
            super(s, seed, 240);
            grid = new TownGrid(s.x, s.z, Atlas.EAST, base, this.seed,
                (x, z) -> Math.abs(x - s.x) < HX - 6 && Math.abs(z - s.z) < HZ - 6, Style.LIBERIO, 2, 4, 24,
                new int[] {Blocks.COBBLE, Blocks.GRAVEL, Blocks.STONE_BRICKS, Blocks.ANDESITE})
                .plaza(0, 0, 18);
        }

        @Override
        public boolean occupies(int x, int z) {
            return Math.abs(x - site.x) <= HX + 4 && Math.abs(z - site.z) <= HZ + 4;
        }

        @Override
        public void column(ChunkBuffer buf, int x, int z, Column col) {
            int dx = x - site.x, dz = z - site.z;
            int ex = HX - Math.abs(dx), ez = HZ - Math.abs(dz);
            if (ex < 0 || ez < 0 || col.underwater()) return;
            if (Math.min(ex, ez) <= 1) {
                boolean gate = (Math.abs(dx) <= 3 && ez <= 1) || (Math.abs(dz) <= 3 && ex <= 1);
                if (col.height < base) buf.fill(x, col.height, base - 1, z, Blocks.STONE_BRICKS);
                if (gate) {
                    buf.set(x, base, z, Blocks.STONE_BRICKS);
                    buf.fill(x, base + 1, base + 6, z, Blocks.AIR);
                    buf.fill(x, base + 7, base + 12, z, Blocks.BRICKS);
                } else {
                    buf.fill(x, base, base + 12, z, Math.floorMod(dx + dz, 7) == 0 ? Blocks.STONE_BRICKS : Blocks.BRICKS);
                }
                return;
            }
            if (grid.shape.inside(x, z)) grid.column(buf, x, z, col.height);
            else buf.set(x, col.height, z, Blocks.GRAVEL);
        }
    }

    /** The Marleyan military base: barracks, headquarters, parade ground, depots and a training field. */
    static final class MilitaryBase extends SiteFeature {
        private static final int HX = 180, HZ = 130;
        private final java.util.List<House> buildings = new java.util.ArrayList<>();
        private final int[][] towers;

        MilitaryBase(Atlas.Site s, long seed) {
            super(s, seed, 190);
            int x = s.x, z = s.z;
            buildings.add(new House(x - 30, z - 118, x + 29, z - 93, true, base, 4, Style.MARLEY_GRAND, 1, false));
            for (int i = 0; i < 6; i++) {
                int bz = z - 70 + i * 26;
                buildings.add(new House(x - 165, bz, x - 126, bz + 13, true, base, 2, Style.MARLEY[i % Style.MARLEY.length], 1, false));
                buildings.add(new House(x - 112, bz, x - 73, bz + 13, true, base, 2, Style.MARLEY[(i + 1) % Style.MARLEY.length], 1, false));
            }
            for (int i = 0; i < 3; i++) {
                int bx = x - 40 + i * 42;
                buildings.add(new House(bx, z + 85, bx + 33, z + 116, false, base, 2, Style.MARLEY[3], -1, false));
            }
            towers = new int[][] {{x - HX + 4, z - HZ + 4}, {x + HX - 4, z - HZ + 4}, {x - HX + 4, z + HZ - 4}, {x + HX - 4, z + HZ - 4}};
        }

        @Override
        public boolean occupies(int x, int z) {
            return Math.abs(x - site.x) <= HX + 4 && Math.abs(z - site.z) <= HZ + 4;
        }

        @Override
        public void column(ChunkBuffer buf, int x, int z, Column col) {
            int dx = x - site.x, dz = z - site.z;
            int ex = HX - Math.abs(dx), ez = HZ - Math.abs(dz);
            if (ex < 0 || ez < 0 || col.underwater()) return;
            if (col.height < base) buf.fill(x, col.height, base - 1, z, Blocks.DIRT);
            buf.fill(x, base + 1, Math.max(col.height, base + 1), z, Blocks.AIR);
            long h = Hash.of(seed, x, z);
            if (Math.min(ex, ez) <= 1) {
                boolean gate = (Math.abs(dx) <= 4 && dz < 0) || (Math.abs(dz) <= 4 && dx > 0);
                buf.set(x, base, z, Blocks.STONE_BRICKS);
                if (!gate) {
                    buf.fill(x, base + 1, base + 8, z, Blocks.STONE_BRICKS);
                    buf.set(x, base + 9, z, Math.min(ex, ez) == 0 ? Blocks.IRON_BARS_X : Blocks.STONE_BRICKS);
                }
                return;
            }
            boolean parade = Math.abs(dx + 5) <= 55 && dz >= -80 && dz <= 60;
            boolean field = dx >= 70 && dx <= HX - 8 && dz >= -110 && dz <= 70;
            if (parade) {
                buf.set(x, base, z, (Math.floorMod(dx, 10) == 0 || Math.floorMod(dz, 10) == 0) ? Blocks.STONE_BRICKS : Blocks.SMOOTH_STONE);
                if (dx == -5 && dz == -20) {
                    buf.fill(x, base + 1, base + 14, z, Blocks.id("iron_bars"));
                    buf.fill(x, base + 12, base + 14, z + 1, Blocks.RED_WOOL);
                }
            } else if (field) {
                buf.set(x, base, z, Hash.unit(h) < 0.6 ? Blocks.COARSE_DIRT : Blocks.GRASS);
                if (Math.floorMod(dx, 14) == 0 && Math.floorMod(dz, 20) == 0) {
                    buf.fill(x, base + 1, base + 1, z, Blocks.SPRUCE_FENCE);
                    buf.set(x, base + 2, z, Blocks.HAY);
                }
                if (Math.floorMod(dz, 20) == 10 && Math.floorMod(dx, 4) != 0) buf.set(x, base + 1, z, Blocks.id("sandstone_wall"));
            } else {
                buf.set(x, base, z, Hash.unit(h) < 0.7 ? Blocks.GRAVEL : Blocks.ANDESITE);
            }
            for (House b : buildings) if (b.covers(x, z)) { b.column(buf, x, z, base); return; }
            for (int[] t : towers) {
                if (Tower.covers(x, z, t[0], t[1], 3)) {
                    Tower.column(buf, x, z, t[0], t[1], 3, base, 16, Blocks.STONE_BRICKS, Blocks.id("dark_oak_planks"), base);
                }
            }
        }
    }
}
