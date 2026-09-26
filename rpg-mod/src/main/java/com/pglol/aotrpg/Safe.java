package com.pglol.aotrpg;

import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

/**
 * Safe landings. Teleports used to take the highest block at a spot, which is the surface above
 * anything underground (the Underground City, its properties), and stored place heights can sit
 * inside a floor. This finds the nearest spot at the intended height where you can stand: a solid
 * floor with two blocks of air above it.
 */
public final class Safe {
    private Safe() {}

    private static final java.util.Map<java.util.UUID, Integer> stuck = new java.util.HashMap<>();

    /**
     * Called every 10 ticks: someone stuck inside blocks for over a second (a spawn point saved in
     * a floor, a bad landing) is moved to the nearest place they can stand.
     */
    public static void unstick(net.minecraft.server.network.ServerPlayerEntity p) {
        if (p.isSpectator() || p.isCreative() || p.hasVehicle() || !p.isInsideWall()) {
            stuck.remove(p.getUuid());
            return;
        }
        int n = stuck.merge(p.getUuid(), 1, Integer::sum);
        if (n < 3) return;
        stuck.remove(p.getUuid());
        ServerWorld w = p.getServerWorld();
        BlockPos at = near(w, p.getBlockX(), p.getBlockY(), p.getBlockZ());
        if (at == null) at = landing(w, p.getBlockX(), p.getBlockY(), p.getBlockZ());
        p.teleport(w, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, p.getYaw(), p.getPitch());
        // Fix a spawn point saved inside a wall so the next respawn is safe too.
        BlockPos sp = p.getSpawnPointPosition();
        if (sp != null && p.getSpawnPointDimension() == w.getRegistryKey() && sp.isWithinDistance(at, 16) && !standable(w, sp)) {
            p.setSpawnPoint(w.getRegistryKey(), at, 0, true, false);
        }
    }

    public static boolean standable(ServerWorld w, BlockPos p) {
        BlockState floor = w.getBlockState(p.down()), feet = w.getBlockState(p), head = w.getBlockState(p.up());
        if (!floor.isSideSolidFullSquare(w, p.down(), net.minecraft.util.math.Direction.UP) || floor.getFluidState().isIn(FluidTags.LAVA)) return false;
        if (!feet.getCollisionShape(w, p).isEmpty() || !head.getCollisionShape(w, p.up()).isEmpty()) return false;
        return !feet.getFluidState().isIn(FluidTags.LAVA) && !head.getFluidState().isIn(FluidTags.LAVA);
    }

    /** Is this spot well below the surface (a cave or the Underground City)? */
    public static boolean underground(ServerWorld w, int x, int y, int z) {
        w.getChunk(x >> 4, z >> 4);
        return y < w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 8;
    }

    /** The nearest place to stand around (x, y, z) at about that height, or null within 12 blocks. */
    public static BlockPos near(ServerWorld w, int x, int y, int z) {
        for (int r = 0; r <= 12; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = x + dx, cz = z + dz;
                    w.getChunk(cx >> 4, cz >> 4);
                    for (int k = 0; k <= 16; k++) {
                        int dy = (k & 1) == 0 ? k / 2 : -(k + 1) / 2;
                        BlockPos p = new BlockPos(cx, y + dy, cz);
                        if (standable(w, p)) return p;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Where to land for a spot meant at height y: that height (or the nearest standable spot) when
     * it's underground, else the surface.
     */
    public static BlockPos landing(ServerWorld w, int x, int y, int z) {
        if (underground(w, x, y, z)) {
            BlockPos p = near(w, x, y, z);
            if (p != null) return p;
        }
        w.getChunk(x >> 4, z >> 4);
        int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos p = new BlockPos(x, Math.max(top, y), z);
        if (standable(w, p)) return p;
        BlockPos q = near(w, x, top, z);
        return q != null ? q : p;
    }
}
