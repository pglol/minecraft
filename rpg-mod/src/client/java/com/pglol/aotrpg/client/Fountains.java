package com.pglol.aotrpg.client;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Town fountains, running. The squares have a stone column with a lantern in a round pool; here
 * water pours from four spouts under the lantern into the pool, splashes and ripples where it
 * lands, and you hear it as you walk past. All drawn on your side: nothing in the world changes.
 */
public final class Fountains {
    private Fountains() {}

    private static final List<BlockPos> found = new ArrayList<>();
    private static ClientWorld seenIn;
    private static int ticks;
    private static final int[][] SPOUTS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public static void tick(MinecraftClient mc) {
        ClientWorld w = mc.world;
        if (w == null || mc.player == null) {
            found.clear();
            seenIn = null;
            return;
        }
        if (w != seenIn) {
            found.clear();
            seenIn = w;
        }
        ticks++;
        if (ticks % 100 == 1) scan(w, mc.player.getBlockPos());
        if (mc.isPaused()) return;
        Random r = w.random;
        for (BlockPos lantern : found) {
            double dist = mc.player.getPos().squaredDistanceTo(lantern.getX() + 0.5, lantern.getY(), lantern.getZ() + 0.5);
            if (dist > 48 * 48) continue;
            double cx = lantern.getX() + 0.5, cz = lantern.getZ() + 0.5;
            int base = lantern.getY() - 4;
            double mouthY = base + 3.35, pool = base + 0.95;
            for (int[] s : SPOUTS) {
                // A stream pouring off the column's side into the pool.
                double mx = cx + s[0] * 1.62, mz = cz + s[1] * 1.62;
                for (int i = 0; i < 3; i++) {
                    w.addParticle(ParticleTypes.FALLING_WATER, mx + (r.nextDouble() - 0.5) * 0.14,
                        mouthY - r.nextDouble() * 0.3, mz + (r.nextDouble() - 0.5) * 0.14, 0, 0, 0);
                }
                // The spout's lip: water welling over it.
                if (r.nextInt(3) == 0) w.addParticle(ParticleTypes.DRIPPING_WATER, cx + s[0] * 1.35, mouthY + 0.1, cz + s[1] * 1.35, 0, 0, 0);
                // Where it lands: splashes and rings on the water.
                w.addParticle(ParticleTypes.SPLASH, mx + (r.nextDouble() - 0.5) * 0.5, pool, mz + (r.nextDouble() - 0.5) * 0.5,
                    (r.nextDouble() - 0.5) * 0.1, 0.05, (r.nextDouble() - 0.5) * 0.1);
                if (r.nextInt(4) == 0) w.addParticle(ParticleTypes.FISHING, mx + (r.nextDouble() - 0.5) * 0.9, pool, mz + (r.nextDouble() - 0.5) * 0.9, 0, 0, 0);
            }
            if (dist < 24 * 24 && ticks % 40 == (lantern.hashCode() & 31)) {
                w.playSound(cx, base + 1, cz, SoundEvents.BLOCK_WATER_AMBIENT, SoundCategory.AMBIENT, 0.55f, 0.9f + r.nextFloat() * 0.2f, false);
            }
        }
    }

    /** Looks for the square's fountain: a lantern on three stone bricks, standing in a pool. */
    private static void scan(ClientWorld w, BlockPos at) {
        found.removeIf(p -> p.getSquaredDistance(at) > 128 * 128);
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = at.getX() - 56; x <= at.getX() + 56; x++) {
            for (int z = at.getZ() - 56; z <= at.getZ() + 56; z++) {
                if (!w.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;
                int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) - 1;
                m.set(x, top, z);
                if (!w.getBlockState(m).isOf(Blocks.LANTERN)) continue;
                boolean column = true;
                for (int k = 1; k <= 3; k++) if (!w.getBlockState(m.set(x, top - k, z)).isOf(Blocks.STONE_BRICKS)) column = false;
                // The column is a plus of five, each with a lantern; only the middle one has bricks all round.
                for (int[] s : SPOUTS) if (!w.getBlockState(m.set(x + s[0], top - 1, z + s[1])).isOf(Blocks.STONE_BRICKS)) column = false;
                if (!column) continue;
                int wet = 0;
                for (int[] s : SPOUTS) if (w.getFluidState(m.set(x + s[0] * 3, top - 4, z + s[1] * 3)).isIn(FluidTags.WATER)) wet++;
                if (wet < 3) continue;
                BlockPos p = new BlockPos(x, top, z);
                if (!found.contains(p)) found.add(p);
            }
        }
    }
}
