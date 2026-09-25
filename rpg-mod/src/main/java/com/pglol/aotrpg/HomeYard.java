package com.pglol.aotrpg;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * The yard upgrades, each built on its own corner of the home yard so they never overlap the
 * house (which stands in the middle): stable (north-west), forge (north-east), garden
 * (south-west), pond (south-east), storage shed (west side).
 */
final class HomeYard {
    private HomeYard() {}

    private static final int F = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

    static void build(ServerWorld w, Homes.Upgrade u, int x0, int y, int z0, int yard, ServerPlayerEntity owner) {
        if (w == null) return;
        WorldCare.quiet(true);
        try {
            switch (u) {
                case STABLE -> stable(w, x0 + 2, y, z0 + 2, owner);
                case FORGE -> forge(w, x0 + yard - 20, y, z0 + 2);
                case GARDEN -> garden(w, x0 + 2, y, z0 + yard - 20);
                case POND -> pond(w, x0 + yard - 19, y, z0 + yard - 19);
                case STORAGE -> storage(w, x0 + 2, y, z0 + yard / 2 - 6);
            }
        } finally {
            WorldCare.quiet(false);
        }
    }

    private static void set(ServerWorld w, int x, int y, int z, BlockState s) {
        w.setBlockState(new BlockPos(x, y, z), s, F);
    }

    private static void roof(ServerWorld w, int x0, int z0, int x1, int z1, int y, BlockState s) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) set(w, x, y, z, s);
    }

    private static void posts(ServerWorld w, int x0, int z0, int x1, int z1, int y, int h, BlockState s) {
        for (int[] c : new int[][] {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) for (int k = 1; k <= h; k++) set(w, c[0], y + k, c[1], s);
    }

    /** A fenced paddock with a roofed stall, hay and a trough, and a saddled horse. */
    private static void stable(ServerWorld w, int x0, int y, int z0, ServerPlayerEntity owner) {
        int x1 = x0 + 18, z1 = z0 + 18;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                set(w, x, y, z, Blocks.COARSE_DIRT.getDefaultState());
                boolean edge = x == x0 || z == z0 || x == x1 || z == z1;
                if (edge && !(z == z1 && Math.abs(x - (x0 + 9)) <= 1)) set(w, x, y + 1, z, Blocks.OAK_FENCE.getDefaultState());
            }
        }
        set(w, x0 + 9, y + 1, z1, Blocks.OAK_FENCE_GATE.getDefaultState());
        // Stall along the north side.
        posts(w, x0 + 1, z0 + 1, x0 + 17, z0 + 6, y, 4, Blocks.SPRUCE_LOG.getDefaultState());
        roof(w, x0, z0, x1, z0 + 7, y + 5, Blocks.SPRUCE_SLAB.getDefaultState());
        for (int x = x0 + 1; x <= x0 + 17; x++) set(w, x, y + 1, z0 + 1, Blocks.SPRUCE_PLANKS.getDefaultState());
        for (int x = x0 + 3; x <= x0 + 15; x += 4) {
            set(w, x, y + 1, z0 + 2, Blocks.HAY_BLOCK.getDefaultState());
            set(w, x + 1, y + 1, z0 + 2, Blocks.CAULDRON.getDefaultState());
            set(w, x, y + 4, z0 + 4, Blocks.LANTERN.getDefaultState().with(Properties.HANGING, true));
        }
        Homes.horse(w, x0 + 9.5, y + 1, z0 + 11.5, owner);
    }

    /** An open-sided smithy: anvil (opens the forge), smithing table, blast furnace, grindstone, lava trough. */
    private static void forge(ServerWorld w, int x0, int y, int z0) {
        int x1 = x0 + 16, z1 = z0 + 12;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) set(w, x, y, z, Blocks.STONE_BRICKS.getDefaultState());
        posts(w, x0, z0, x1, z1, y, 4, Blocks.DARK_OAK_LOG.getDefaultState());
        roof(w, x0 - 1, z0 - 1, x1 + 1, z1 + 1, y + 5, Blocks.DEEPSLATE_TILES.getDefaultState());
        for (int x = x0; x <= x1; x++) for (int k = 1; k <= 4; k++) set(w, x, y + k, z0, Blocks.COBBLESTONE.getDefaultState());
        set(w, x0 + 8, y + 1, z0 + 6, Blocks.ANVIL.getDefaultState());
        set(w, x0 + 4, y + 1, z0 + 1, Blocks.SMITHING_TABLE.getDefaultState());
        set(w, x0 + 6, y + 1, z0 + 1, Blocks.BLAST_FURNACE.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.SOUTH));
        set(w, x0 + 10, y + 1, z0 + 1, Blocks.GRINDSTONE.getDefaultState());
        set(w, x0 + 12, y + 1, z0 + 1, Blocks.LAVA_CAULDRON.getDefaultState());
        set(w, x0 + 13, y + 1, z0 + 1, Blocks.WATER_CAULDRON.getDefaultState().with(Properties.LEVEL_3, 3));
        set(w, x0 + 8, y + 4, z0 + 6, Blocks.LANTERN.getDefaultState().with(Properties.HANGING, true));
    }

    /** Tilled beds between water channels, with a composter. */
    private static void garden(ServerWorld w, int x0, int y, int z0) {
        for (int x = x0; x <= x0 + 17; x++) {
            for (int z = z0; z <= z0 + 17; z++) {
                boolean border = x == x0 || z == z0 || x == x0 + 17 || z == z0 + 17;
                if (border) {
                    set(w, x, y, z, Blocks.DIRT_PATH.getDefaultState());
                    set(w, x, y + 1, z, (x + z) % 6 == 0 ? Blocks.OAK_FENCE.getDefaultState() : Blocks.AIR.getDefaultState());
                } else if ((x - x0) % 5 == 0) {
                    set(w, x, y, z, Blocks.WATER.getDefaultState());
                } else {
                    set(w, x, y, z, Blocks.FARMLAND.getDefaultState().with(Properties.MOISTURE, 7));
                }
            }
        }
        set(w, x0 + 1, y + 1, z0 + 1, Blocks.COMPOSTER.getDefaultState());
    }

    /** A round pond with reeds and lily pads. */
    private static void pond(ServerWorld w, int x0, int y, int z0) {
        int cx = x0 + 8, cz = z0 + 8;
        for (int x = x0; x <= x0 + 16; x++) {
            for (int z = z0; z <= z0 + 16; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d <= 6.2) {
                    set(w, x, y - 2, z, Blocks.CLAY.getDefaultState());
                    set(w, x, y - 1, z, Blocks.WATER.getDefaultState());
                    set(w, x, y, z, Blocks.WATER.getDefaultState());
                    if (d > 3 && (x * 7 + z * 3) % 11 == 0) set(w, x, y + 1, z, Blocks.LILY_PAD.getDefaultState());
                } else if (d <= 7.4) {
                    set(w, x, y, z, Blocks.SAND.getDefaultState());
                    if ((x + z) % 4 == 0) set(w, x, y + 1, z, Blocks.SUGAR_CANE.getDefaultState());
                }
            }
        }
    }

    /** A small shed with four double chests. */
    private static void storage(ServerWorld w, int x0, int y, int z0) {
        int x1 = x0 + 10, z1 = z0 + 12;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                set(w, x, y, z, Blocks.SPRUCE_PLANKS.getDefaultState());
                boolean wall = x == x0 || z == z0 || z == z1;
                boolean door = x == x1 && Math.abs(z - (z0 + 6)) <= 0;
                for (int k = 1; k <= 3; k++) {
                    if (wall) set(w, x, y + k, z, Blocks.SPRUCE_PLANKS.getDefaultState());
                    else if (x == x1 && !door) set(w, x, y + k, z, Blocks.SPRUCE_PLANKS.getDefaultState());
                    else set(w, x, y + k, z, Blocks.AIR.getDefaultState());
                }
                set(w, x, y + 4, z, Blocks.SPRUCE_SLAB.getDefaultState());
            }
        }
        set(w, x1, y + 1, z0 + 6, Blocks.SPRUCE_DOOR.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.WEST));
        set(w, x1, y + 2, z0 + 6, Blocks.SPRUCE_DOOR.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.WEST)
            .with(Properties.DOUBLE_BLOCK_HALF, net.minecraft.block.enums.DoubleBlockHalf.UPPER));
        // Double chests against the back wall: left/right halves facing east.
        for (int i = 0; i < 4; i++) {
            int z = z0 + 2 + i * 2 + (i >= 2 ? 1 : 0);
            BlockState c = Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.EAST);
            set(w, x0 + 1, y + 1, z, c.with(ChestBlock.CHEST_TYPE, ChestType.RIGHT));
            set(w, x0 + 1, y + 1, z + 1, c.with(ChestBlock.CHEST_TYPE, ChestType.LEFT));
        }
        set(w, x0 + 5, y + 3, z0 + 6, Blocks.LANTERN.getDefaultState().with(Properties.HANGING, true));
    }
}
