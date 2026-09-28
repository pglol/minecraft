package com.pglol.aotrpg;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Town houses with holes in them: walls knocked out (by titans, fire, old damage from before the
 * world kept track) leave the timber frame standing over an open room. Houses near players are
 * looked over and their walls filled back in from what's left of them: the same plaster, the
 * same beams, windows where the house has windows, and the doorway left open.
 */
public final class TownRepair {
    /** When each house was last looked over (game ticks), by its index in the town house list. */
    private final Map<Integer, Long> checked = new HashMap<>();
    private static final long RECHECK = 20L * 60 * 20;

    public void tick(ServerWorld w, int ticks) {
        if (ticks % 40 != 13 || AotRpg.PLACES.homes.isEmpty()) return;
        int budget = 3;
        for (ServerPlayerEntity p : new ArrayList<>(w.getPlayers())) {
            for (int i = 0; i < AotRpg.PLACES.homes.size() && budget > 0; i++) {
                int[] h = AotRpg.PLACES.homes.get(i);
                double cx = (h[0] + h[2]) / 2.0, cz = (h[1] + h[3]) / 2.0;
                double dx = p.getX() - cx, dz = p.getZ() - cz;
                if (dx * dx + dz * dz > 96 * 96) continue;
                Long at = checked.get(i);
                if (at != null && ticks - at < RECHECK && ticks >= at) continue;
                if (!w.isChunkLoaded(h[0] >> 4, h[1] >> 4) || !w.isChunkLoaded(h[2] >> 4, h[3] >> 4)) continue;
                checked.put(i, (long) ticks);
                budget--;
                int n = repair(w, h);
                if (n > 0) AotRpg.LOG.info("[town] Repaired {} wall blocks in the house at {} {}", n, h[0], h[1]);
            }
        }
    }

    /** Fills the holes in one house's outer walls. Returns how many blocks were put back. */
    static int repair(ServerWorld w, int[] h) {
        int x0 = h[0], z0 = h[1], x1 = h[2], z1 = h[3], base = h[4];
        // The wall top: as high as the tallest corner post still stands (any one may be knocked out)...
        int top = base;
        for (int[] c : new int[][] {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) {
            int t = base;
            while (t < h[5] && !w.getBlockState(new BlockPos(c[0], t + 1, c[1])).isAir()) t++;
            top = Math.max(top, t);
        }
        // ...or, with every corner gone, as high as most of what's left of the walls.
        if (top - base < 3) {
            List<Integer> hs = new ArrayList<>();
            for (int x = x0; x <= x1; x += Math.max(1, (x1 - x0) / 4)) {
                for (int zz : new int[] {z0, z1}) {
                    int t = base;
                    while (t < h[5] && !w.getBlockState(new BlockPos(x, t + 1, zz)).isAir()) t++;
                    hs.add(t);
                }
            }
            hs.sort(null);
            top = hs.isEmpty() ? base : hs.get(hs.size() * 3 / 4);
        }
        if (top - base < 3) return 0;
        // The doorway: the wall block next to the doorstep, two high.
        int dx = Math.max(x0, Math.min(x1, h[6])), dz = Math.max(z0, Math.min(z1, h[7]));
        List<BlockPos> ring = new ArrayList<>();
        for (int x = x0 + 1; x < x1; x++) {
            ring.add(new BlockPos(x, 0, z0));
            ring.add(new BlockPos(x, 0, z1));
        }
        for (int z = z0 + 1; z < z1; z++) {
            ring.add(new BlockPos(x0, 0, z));
            ring.add(new BlockPos(x1, 0, z));
        }
        // What this house is made of, level by level: plaster, beams, panes.
        Map<Integer, Map<BlockState, Integer>> byLevel = new HashMap<>();
        Map<BlockState, Integer> plaster = new HashMap<>();
        BlockState pane = null;
        int holes = 0;
        for (BlockPos c : ring) {
            for (int y = base + 1; y < top; y++) {
                BlockState s = w.getBlockState(c.withY(y));
                if (s.isAir() || s.isOf(Blocks.FIRE)) {
                    holes++;
                    continue;
                }
                if (s.getBlock() instanceof PaneBlock) {
                    pane = s.getBlock().getDefaultState();
                    continue;
                }
                if (s.getBlock() instanceof DoorBlock || !s.isFullCube(w, c.withY(y))) continue;
                byLevel.computeIfAbsent(y, k -> new HashMap<>()).merge(s, 1, Integer::sum);
                if (!(s.getBlock() instanceof PillarBlock)) plaster.merge(s, 1, Integer::sum);
            }
        }
        if (holes == 0) return 0;
        BlockState fill = best(plaster);
        if (fill == null) fill = Blocks.WHITE_TERRACOTTA.getDefaultState();
        int put = 0;
        WorldCare.quiet(true);
        try {
            for (BlockPos c : ring) {
                boolean alongX = c.getZ() == z0 || c.getZ() == z1;
                int p = alongX ? c.getX() - x0 : c.getZ() - z0, len = alongX ? x1 - x0 : z1 - z0;
                boolean door = Math.abs(c.getX() - dx) + Math.abs(c.getZ() - dz) == 0;
                for (int y = base + 1; y < top; y++) {
                    BlockPos at = c.withY(y);
                    BlockState s = w.getBlockState(at);
                    if (!s.isAir() && !s.isOf(Blocks.FIRE)) continue;
                    int yy = y - base, fl = yy % 4;
                    if (door && (yy == 1 || yy == 2)) continue;
                    BlockState put1;
                    if (fl == 0) {
                        // A floor beam: the same beam this level has elsewhere, turned along the wall.
                        BlockState beam = best(byLevel.getOrDefault(y, Map.of()));
                        put1 = beam == null ? fill : beam;
                        if (put1.getBlock() instanceof PillarBlock) put1 = put1.with(PillarBlock.AXIS, alongX ? net.minecraft.util.math.Direction.Axis.X : net.minecraft.util.math.Direction.Axis.Z);
                    } else if (pane != null && fl >= 2 && p % 3 == 2 && p >= 2 && p <= len - 2) {
                        put1 = pane;
                    } else {
                        put1 = fill;
                    }
                    w.setBlockState(at, put1, Block.NOTIFY_ALL);
                    if (put1.getBlock() instanceof PaneBlock) w.setBlockState(at, Block.postProcessState(put1, w, at), Block.NOTIFY_ALL);
                    put++;
                }
            }
        } finally {
            WorldCare.quiet(false);
        }
        return put;
    }

    private static BlockState best(Map<BlockState, Integer> m) {
        BlockState b = null;
        int n = -1;
        for (var e : m.entrySet()) if (e.getValue() > n) {
            n = e.getValue();
            b = e.getKey();
        }
        return b;
    }
}
