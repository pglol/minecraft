package com.pglol.aotrpg;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

/**
 * The cellar under a town home (walled-in homes have no yard, so their upgrades go inside and
 * down). A ladder hatch in the ground floor leads to a long stone room with a central aisle and
 * six bays, three along each wall; each interior upgrade fills its own bay:
 *
 *   north: storage vault | forge | armory
 *   south: kitchen       | training room | trophy hall
 *
 * The west end holds the cellar's own chests and barrels, the east end a table and a map.
 */
final class HomeCellar {
    private HomeCellar() {}

    private static final int F = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
    /** Half-length (x) and half-width (z) of the room inside the walls. */
    static final int HX = 12, HZ = 6;
    /** The cellar floor (its ceiling is two under the house floor, so the street outside stays as it is). */
    static int floor(int floorY) { return floorY - 7; }

    private static void set(ServerWorld w, int x, int y, int z, BlockState s) {
        w.setBlockState(new BlockPos(x, y, z), s, F);
    }

    /** Digs and fits out the cellar under the house centred on (cx, cz), with its hatch at (hx, hz). */
    static void dig(ServerWorld w, int cx, int cz, int floorY, int hx, int hz) {
        int fy = floor(floorY);
        WorldCare.quiet(true);
        try {
            for (int x = cx - HX - 1; x <= cx + HX + 1; x++) {
                for (int z = cz - HZ - 1; z <= cz + HZ + 1; z++) {
                    boolean wall = Math.abs(x - cx) == HX + 1 || Math.abs(z - cz) == HZ + 1;
                    set(w, x, fy - 1, z, Blocks.STONE.getDefaultState());
                    // Flagstones, with a darker aisle runner down the middle.
                    set(w, x, fy, z, wall ? Blocks.STONE_BRICKS.getDefaultState()
                        : Math.abs(z - cz) <= 1 ? Blocks.POLISHED_DEEPSLATE.getDefaultState()
                        : (x + z) % 5 == 0 ? Blocks.CRACKED_STONE_BRICKS.getDefaultState() : Blocks.STONE_BRICKS.getDefaultState());
                    for (int y = fy + 1; y <= fy + 4; y++) {
                        BlockState s = Blocks.AIR.getDefaultState();
                        if (wall) {
                            boolean pillar = Math.floorMod(x - cx, 7) == 3 || Math.abs(z - cz) == HZ + 1 && Math.abs(x - cx) == HX + 1;
                            s = pillar ? Blocks.POLISHED_ANDESITE.getDefaultState()
                                : y == fy + 1 ? Blocks.MOSSY_STONE_BRICKS.getDefaultState() : Blocks.STONE_BRICKS.getDefaultState();
                        }
                        set(w, x, y, z, s);
                    }
                    // Oak beams across the ceiling every few blocks, stone between.
                    set(w, x, fy + 5, z, Math.floorMod(x - cx, 4) == 0 ? Blocks.STRIPPED_SPRUCE_LOG.getDefaultState().with(Properties.AXIS, Direction.Axis.Z)
                        : Blocks.STONE_BRICKS.getDefaultState());
                }
            }
            // Lanterns down the aisle.
            for (int x = cx - HX + 2; x <= cx + HX - 2; x += 4) set(w, x, fy + 4, cz, Blocks.LANTERN.getDefaultState().with(Properties.HANGING, true));
            // Sconces on the pillars.
            for (int x = cx - HX; x <= cx + HX; x++) {
                if (Math.floorMod(x - cx, 7) != 3) continue;
                set(w, x, fy + 3, cz - HZ, Blocks.WALL_TORCH.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.SOUTH));
                set(w, x, fy + 3, cz + HZ, Blocks.WALL_TORCH.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.NORTH));
            }
            // West end: the cellar's own stores.
            int wx = cx - HX;
            pair(w, wx, fy + 1, cz - 2, Direction.EAST);
            pair(w, wx, fy + 1, cz + 1, Direction.EAST);
            set(w, wx, fy + 1, cz, Blocks.BARREL.getDefaultState().with(Properties.FACING, Direction.EAST));
            set(w, wx, fy + 2, cz, Blocks.BARREL.getDefaultState().with(Properties.FACING, Direction.EAST));
            // East end: a planning table with a map, and a bench.
            int ex = cx + HX;
            set(w, ex, fy + 1, cz, Blocks.CARTOGRAPHY_TABLE.getDefaultState());
            set(w, ex, fy + 1, cz - 1, Blocks.SPRUCE_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.EAST));
            set(w, ex, fy + 1, cz + 1, Blocks.SPRUCE_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.EAST));
            set(w, ex, fy + 3, cz, Blocks.RED_WALL_BANNER.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.WEST));
            // The hatch: a trapdoor set into the house floor, a ladder down against a stone post.
            hatch(w, hx, hz, floorY);
        } finally {
            WorldCare.quiet(false);
        }
    }

    static void hatch(ServerWorld w, int hx, int hz, int floorY) {
        int fy = floor(floorY);
        Direction face = Direction.WEST; // the ladder hangs on a post to its east
        WorldCare.quiet(true);
        for (int y = fy + 1; y < floorY; y++) {
            set(w, hx + 1, y, hz, Blocks.STONE_BRICKS.getDefaultState());
            set(w, hx, y, hz, Blocks.LADDER.getDefaultState().with(LadderBlock.FACING, face));
        }
        set(w, hx + 1, fy + 5, hz, Blocks.STONE_BRICKS.getDefaultState());
        set(w, hx, floorY, hz, Blocks.SPRUCE_TRAPDOOR.getDefaultState().with(TrapdoorBlock.FACING, face)
            .with(TrapdoorBlock.HALF, BlockHalf.TOP).with(TrapdoorBlock.OPEN, false));
        WorldCare.quiet(false);
    }

    /** A double chest facing f with its left half at (x, y, z). */
    private static void pair(ServerWorld w, int x, int y, int z, Direction f) {
        BlockState c = Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, f);
        BlockPos left = new BlockPos(x, y, z), right = left.offset(f.rotateYClockwise());
        w.setBlockState(left, c.with(ChestBlock.CHEST_TYPE, ChestType.LEFT), F);
        w.setBlockState(right, c.with(ChestBlock.CHEST_TYPE, ChestType.RIGHT), F);
    }

    /** Which bay (0..5) an upgrade fills, or -1. */
    static int bay(Homes.Upgrade u) {
        return switch (u) {
            case STORAGE -> 0;
            case FORGE -> 1;
            case ARMORY -> 2;
            case KITCHEN -> 3;
            case TRAINING -> 4;
            case TROPHY -> 5;
            default -> -1;
        };
    }

    /** Fits out one bay for its upgrade. */
    static void bay(ServerWorld w, Homes.Upgrade u, int cx, int cz, int floorY) {
        int i = bay(u);
        if (i < 0) return;
        int fy = floor(floorY), y = fy + 1;
        int x0 = cx - 10 + (i % 3) * 7;
        boolean north = i < 3;
        int back = north ? cz - HZ : cz + HZ, in = north ? 1 : -1;
        Direction face = north ? Direction.SOUTH : Direction.NORTH;
        WorldCare.quiet(true);
        try {
            // Clear the bay first (rebuilding over an old fit-out).
            for (int x = x0; x <= x0 + 6; x++) for (int k = 0; k < 4; k++) for (int yy = y; yy <= fy + 2; yy++) set(w, x, yy, back + in * k, Blocks.AIR.getDefaultState());
            // A rug marks each bay.
            BlockState rug = switch (u) {
                case STORAGE -> Blocks.BROWN_CARPET.getDefaultState();
                case FORGE -> Blocks.GRAY_CARPET.getDefaultState();
                case ARMORY -> Blocks.RED_CARPET.getDefaultState();
                case KITCHEN -> Blocks.ORANGE_CARPET.getDefaultState();
                case TRAINING -> Blocks.GREEN_CARPET.getDefaultState();
                default -> Blocks.PURPLE_CARPET.getDefaultState();
            };
            if (u != Homes.Upgrade.FORGE && u != Homes.Upgrade.TRAINING) {
                for (int x = x0 + 1; x <= x0 + 5; x++) for (int k = 1; k <= 2; k++) set(w, x, y, back + in * k, rug);
            }
            switch (u) {
                case STORAGE -> {
                    // Six large chests, two high, and barrels at the ends.
                    for (int j = 0; j < 3; j++) {
                        int x = x0 + j * 2 + (face == Direction.SOUTH ? 1 : 0);
                        // Facing south, the left half's partner is to its west: start from the east end.
                        int lx = face == Direction.SOUTH ? x + 1 : x;
                        pair(w, lx, y, back, face);
                        pair(w, lx, y + 1, back, face);
                    }
                    set(w, x0, y, back + in, Blocks.BARREL.getDefaultState().with(Properties.FACING, Direction.UP));
                    set(w, x0 + 6, y, back + in, Blocks.BARREL.getDefaultState().with(Properties.FACING, Direction.UP));
                    set(w, x0 + 3, fy + 3, back + in * 2, Blocks.LANTERN.getDefaultState().with(Properties.HANGING, true));
                    set(w, x0 + 3, fy + 4, back + in * 2, Blocks.CHAIN.getDefaultState());
                }
                case FORGE -> {
                    for (int x = x0; x <= x0 + 6; x++) for (int k = 0; k < 4; k++) set(w, x, fy, back + in * k, Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState());
                    set(w, x0 + 3, y, back + in * 2, Blocks.ANVIL.getDefaultState().with(Properties.HORIZONTAL_FACING, face.rotateYClockwise()));
                    set(w, x0 + 1, y, back, Blocks.BLAST_FURNACE.getDefaultState().with(Properties.HORIZONTAL_FACING, face));
                    set(w, x0 + 2, y, back, Blocks.SMITHING_TABLE.getDefaultState());
                    set(w, x0 + 3, y, back, Blocks.LAVA_CAULDRON.getDefaultState());
                    set(w, x0 + 4, y, back, Blocks.WATER_CAULDRON.getDefaultState().with(Properties.LEVEL_3, 3));
                    set(w, x0 + 5, y, back, Blocks.GRINDSTONE.getDefaultState().with(Properties.HORIZONTAL_FACING, face).with(Properties.BLOCK_FACE, net.minecraft.block.enums.BlockFace.FLOOR));
                    // A brick hood over the hearth.
                    for (int x = x0 + 1; x <= x0 + 5; x++) set(w, x, fy + 3, back, Blocks.BRICKS.getDefaultState());
                    set(w, x0 + 3, fy + 4, back, Blocks.BRICKS.getDefaultState());
                    set(w, x0 + 1, y, back + in * 3, Blocks.CHAIN.getDefaultState());
                    set(w, x0 + 5, fy + 3, back + in * 2, Blocks.LANTERN.getDefaultState().with(Properties.HANGING, true));
                    set(w, x0 + 5, fy + 4, back + in * 2, Blocks.CHAIN.getDefaultState());
                }
                case ARMORY -> {
                    // Three stands for your sets, a rack of polearms, a gear barrel.
                    for (int j = 0; j < 3; j++) stand(w, x0 + 1 + j * 2, y, back + in, face);
                    set(w, x0, y, back, Blocks.BARREL.getDefaultState().with(Properties.FACING, face));
                    set(w, x0 + 6, y, back, Blocks.GRINDSTONE.getDefaultState().with(Properties.HORIZONTAL_FACING, face).with(Properties.BLOCK_FACE, net.minecraft.block.enums.BlockFace.FLOOR));
                    for (int x = x0 + 1; x <= x0 + 5; x++) set(w, x, fy + 3, back, Blocks.SPRUCE_TRAPDOOR.getDefaultState()
                        .with(TrapdoorBlock.FACING, face).with(TrapdoorBlock.HALF, BlockHalf.TOP).with(TrapdoorBlock.OPEN, true));
                    set(w, x0 + 3, fy + 3, back + in * 2, Blocks.LANTERN.getDefaultState().with(Properties.HANGING, true));
                    set(w, x0 + 3, fy + 4, back + in * 2, Blocks.CHAIN.getDefaultState());
                }
                case KITCHEN -> {
                    // A working hearth: the campfire cooks (use it), smoker, pots and stores.
                    set(w, x0 + 3, y, back, Blocks.CAMPFIRE.getDefaultState().with(CampfireBlock.LIT, true).with(CampfireBlock.FACING, face));
                    set(w, x0 + 2, y, back, Blocks.SMOKER.getDefaultState().with(Properties.HORIZONTAL_FACING, face));
                    set(w, x0 + 4, y, back, Blocks.FURNACE.getDefaultState().with(Properties.HORIZONTAL_FACING, face));
                    set(w, x0 + 1, y, back, Blocks.WATER_CAULDRON.getDefaultState().with(Properties.LEVEL_3, 3));
                    set(w, x0 + 5, y, back, Blocks.CRAFTING_TABLE.getDefaultState());
                    set(w, x0, y, back, Blocks.BARREL.getDefaultState().with(Properties.FACING, face));
                    set(w, x0, y + 1, back, Blocks.BARREL.getDefaultState().with(Properties.FACING, face));
                    set(w, x0 + 6, y, back, Blocks.BARREL.getDefaultState().with(Properties.FACING, face));
                    for (int x = x0 + 2; x <= x0 + 4; x++) set(w, x, fy + 3, back, Blocks.BRICKS.getDefaultState());
                    set(w, x0 + 3, fy + 4, back, Blocks.BRICKS.getDefaultState());
                    // Hanging hams and herbs.
                    set(w, x0 + 1, fy + 3, back + in, Blocks.OAK_LEAVES.getDefaultState().with(Properties.PERSISTENT, true));
                    set(w, x0 + 5, fy + 3, back + in, Blocks.HAY_BLOCK.getDefaultState());
                    // A table and stools.
                    set(w, x0 + 3, y, back + in * 2, Blocks.SPRUCE_FENCE.getDefaultState());
                    set(w, x0 + 3, y + 1, back + in * 2, Blocks.SPRUCE_PRESSURE_PLATE.getDefaultState());
                    set(w, x0 + 2, y, back + in * 2, Blocks.SPRUCE_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.WEST));
                    set(w, x0 + 4, y, back + in * 2, Blocks.SPRUCE_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.EAST));
                }
                case TRAINING -> {
                    for (int x = x0; x <= x0 + 6; x++) for (int k = 0; k < 4; k++) set(w, x, fy, back + in * k,
                        (x + k) % 2 == 0 ? Blocks.STRIPPED_OAK_WOOD.getDefaultState() : Blocks.STRIPPED_BIRCH_WOOD.getDefaultState());
                    // Straw cadets: a post, a hay body, a pumpkin head with a red band. Targets on the wall.
                    for (int j = 0; j < 2; j++) {
                        int x = x0 + 2 + j * 3;
                        set(w, x, y, back + in, Blocks.SPRUCE_FENCE.getDefaultState());
                        set(w, x, y + 1, back + in, Blocks.HAY_BLOCK.getDefaultState());
                        set(w, x, y + 2, back + in, Blocks.CARVED_PUMPKIN.getDefaultState().with(Properties.HORIZONTAL_FACING, face));
                    }
                    set(w, x0, y + 1, back, Blocks.TARGET.getDefaultState());
                    set(w, x0 + 6, y + 1, back, Blocks.TARGET.getDefaultState());
                    set(w, x0 + 3, y + 2, back, Blocks.TARGET.getDefaultState());
                    set(w, x0 + 3, fy + 3, back + in * 3, Blocks.LANTERN.getDefaultState().with(Properties.HANGING, true));
                    set(w, x0 + 3, fy + 4, back + in * 3, Blocks.CHAIN.getDefaultState());
                }
                case TROPHY -> {
                    // Banners of the three regiments' colours, mounted skulls, a lectern and a record player.
                    set(w, x0 + 1, fy + 3, back + in, Blocks.GREEN_WALL_BANNER.getDefaultState().with(Properties.HORIZONTAL_FACING, face));
                    set(w, x0 + 3, fy + 3, back + in, Blocks.WHITE_WALL_BANNER.getDefaultState().with(Properties.HORIZONTAL_FACING, face));
                    set(w, x0 + 5, fy + 3, back + in, Blocks.RED_WALL_BANNER.getDefaultState().with(Properties.HORIZONTAL_FACING, face));
                    for (int x = x0; x <= x0 + 6; x++) set(w, x, y, back, x % 2 == 0 ? Blocks.CHISELED_BOOKSHELF.getDefaultState().with(Properties.HORIZONTAL_FACING, face)
                        : Blocks.BOOKSHELF.getDefaultState());
                    set(w, x0 + 1, y + 1, back, Blocks.SKELETON_WALL_SKULL.getDefaultState().with(Properties.HORIZONTAL_FACING, face));
                    set(w, x0 + 5, y + 1, back, Blocks.WITHER_SKELETON_WALL_SKULL.getDefaultState().with(Properties.HORIZONTAL_FACING, face));
                    set(w, x0 + 3, y + 1, back, Blocks.DECORATED_POT.getDefaultState());
                    set(w, x0 + 2, y, back + in * 2, Blocks.LECTERN.getDefaultState().with(Properties.HORIZONTAL_FACING, face.getOpposite()));
                    set(w, x0 + 4, y, back + in * 2, Blocks.JUKEBOX.getDefaultState());
                    set(w, x0 + 6, y, back + in * 2, Blocks.CANDLE.getDefaultState().with(Properties.CANDLES, 3).with(Properties.LIT, true));
                    set(w, x0, y, back + in * 2, Blocks.CANDLE.getDefaultState().with(Properties.CANDLES, 3).with(Properties.LIT, true));
                }
                default -> { }
            }
        } finally {
            WorldCare.quiet(false);
        }
    }

    /** An empty armor stand, arms out, facing into the aisle (unless one already stands here). */
    private static void stand(ServerWorld w, int x, int y, int z, Direction face) {
        Box b = new Box(x, y, z, x + 1, y + 2, z + 1);
        if (!w.getEntitiesByType(EntityType.ARMOR_STAND, b, e -> true).isEmpty()) return;
        ArmorStandEntity s = EntityType.ARMOR_STAND.create(w);
        if (s == null) return;
        s.refreshPositionAndAngles(x + 0.5, y, z + 0.5, face.asRotation(), 0);
        w.spawnEntity(s);
    }
}
