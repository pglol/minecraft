package com.pglol.aotrpg;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Repairs houses from worlds generated before the staircase fix: their flights had three steps
 * and then a full-block climb onto the landing. The landing block becomes a fourth step.
 */
final class HouseFix {
    private HouseFix() {}

    static int stairs(ServerWorld w, BlockPos a, BlockPos b) {
        int n = 0;
        for (BlockPos pos : BlockPos.iterate(a, b)) {
            BlockState s = w.getBlockState(pos);
            if (!(s.getBlock() instanceof StairsBlock) || s.get(StairsBlock.HALF) != BlockHalf.BOTTOM) continue;
            Direction f = s.get(StairsBlock.FACING);
            BlockPos ahead = pos.offset(f);
            // The top step of an old flight: steps below and behind it, air ahead of it,
            // and a floor block one up and ahead (the landing) with room above it.
            BlockPos behind = pos.offset(f.getOpposite()).down();
            if (!(w.getBlockState(behind).getBlock() instanceof StairsBlock)) continue;
            if (!(w.getBlockState(behind.offset(f.getOpposite()).down()).getBlock() instanceof StairsBlock)) continue;
            if (!w.getBlockState(ahead).isAir() || !w.getBlockState(pos.up()).isAir()) continue;
            BlockPos landing = ahead.up();
            BlockState l = w.getBlockState(landing);
            if (l.getBlock() instanceof StairsBlock || !l.isFullCube(w, landing) || !w.getBlockState(landing.up()).isAir()) continue;
            w.setBlockState(landing, s, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            n++;
        }
        return n;
    }
}
