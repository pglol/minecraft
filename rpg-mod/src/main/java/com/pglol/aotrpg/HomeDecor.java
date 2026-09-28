package com.pglol.aotrpg;

import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Furnishing packages for a home, one floor at a time. Each theme lines the walls with its pieces
 * (chairs backed to the wall, shelves, a bed with its head to the wall, a stove, a banner...) and
 * hangs lanterns from the ceiling, sized to the floor: a bigger floor gets more of it. The middle
 * of every room is left open for the owner's own building, and doorways, stairs and the cellar
 * hatch are always kept clear. Swapping a floor's package takes back only what the last one put
 * there (and never a container with anything in it).
 */
public final class HomeDecor {
    private HomeDecor() {}

    public record Package(String id, String name, String blurb, String icon, List<String> pieces) { }

    public static final List<Package> PACKAGES = List.of(
        new Package("hearth", "Hearthside", "Armchairs, bookshelves and lamplight", "minecraft:campfire",
            List.of("shelf", "chair", "lamp", "chair", "plant", "shelf", "barrel", "table", "pot")),
        new Package("quarters", "Scout's Quarters", "A bunk, a footlocker and your colours", "minecraft:red_bed",
            List.of("bed", "chest", "lamp", "stand", "banner", "barrel", "plant")),
        new Package("study", "Scholar's Study", "Walls of books, a lectern, candlelight", "minecraft:bookshelf",
            List.of("shelf", "shelf", "lectern", "chair", "candles", "maps", "shelf", "plant")),
        new Package("kitchen", "Kitchen & Pantry", "A stove, stores and a table to eat at", "minecraft:smoker",
            List.of("smoker", "furnace", "cauldron", "barrel", "barrel", "crafting", "hay", "table", "pot")),
        new Package("workshop", "Workshop", "Benches, a grindstone and an anvil", "minecraft:anvil",
            List.of("crafting", "smithing", "grindstone", "anvil", "barrel", "chest", "lamp")),
        new Package("lounge", "Officer's Lounge", "Music, good chairs and the regiment's banner", "minecraft:jukebox",
            List.of("jukebox", "chair", "table", "chair", "shelf", "banner", "plant", "lamp")));

    public static Package find(String id) {
        for (Package p : PACKAGES) if (p.id().equals(id)) return p;
        return null;
    }

    /** What furnishing a floor this size costs. */
    public static long price(int area) {
        return 1200 + area * 18L;
    }

    /** A floor of a house: the level you stand on (y + 1), and how many open cells it has. */
    public record Floor(int y, int area) { }

    private static boolean open(ServerWorld w, BlockPos p) {
        return w.getBlockState(p).getCollisionShape(w, p).isEmpty() && !(w.getBlockState(p).getBlock() instanceof LadderBlock);
    }

    private static boolean ground(ServerWorld w, BlockPos p) {
        BlockState s = w.getBlockState(p);
        return !s.getCollisionShape(w, p).isEmpty() && !(s.getBlock() instanceof StairsBlock) && !(s.getBlock() instanceof TrapdoorBlock);
    }

    /** The floors inside the box (x0..x1, z0..z1 inside the walls), from y0 up to y1. */
    public static List<Floor> floors(ServerWorld w, int x0, int z0, int x1, int z1, int y0, int y1) {
        List<Floor> out = new ArrayList<>();
        int cells = Math.max(1, (x1 - x0 + 1) * (z1 - z0 + 1));
        for (int y = y0; y <= y1 - 2; y++) {
            int n = 0;
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                BlockPos b = new BlockPos(x, y, z);
                if (ground(w, b) && open(w, b.up()) && open(w, b.up(2))) n++;
            }
            if (n >= cells * 0.35) {
                out.add(new Floor(y, n));
                y += 2;
            }
        }
        return out;
    }

    private static final java.util.Set<Block> OURS = java.util.Set.of(Blocks.BOOKSHELF, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_FENCE, Blocks.LANTERN,
        Blocks.DARK_OAK_FENCE, Blocks.DARK_OAK_PRESSURE_PLATE, Blocks.POTTED_FERN, Blocks.POTTED_AZALEA_BUSH, Blocks.POTTED_RED_TULIP,
        Blocks.CHEST, Blocks.BARREL, Blocks.RED_BED, Blocks.RED_WALL_BANNER, Blocks.GREEN_WALL_BANNER, Blocks.LECTERN, Blocks.CANDLE,
        Blocks.CARTOGRAPHY_TABLE, Blocks.SMOKER, Blocks.FURNACE, Blocks.WATER_CAULDRON, Blocks.CRAFTING_TABLE, Blocks.HAY_BLOCK,
        Blocks.DECORATED_POT, Blocks.SMITHING_TABLE, Blocks.GRINDSTONE, Blocks.ANVIL, Blocks.JUKEBOX, Blocks.CHAIN);

    /** Takes back what a package put down (only our own pieces, and never a container holding anything). */
    public static void clear(ServerWorld w, List<Long> placed, String tag, Box house) {
        int f = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        for (long l : placed) {
            BlockPos p = BlockPos.fromLong(l);
            BlockState s = w.getBlockState(p);
            if (!OURS.contains(s.getBlock())) continue;
            if (w.getBlockEntity(p) instanceof Inventory inv && !inv.isEmpty()) continue;
            w.setBlockState(p, Blocks.AIR.getDefaultState(), f);
        }
        for (ArmorStandEntity a : w.getEntitiesByClass(ArmorStandEntity.class, house, e -> e.getCommandTags().contains(tag))) {
            if (a.getEquippedItems().iterator().hasNext() && hasGear(a)) continue;
            a.discard();
        }
    }

    private static boolean hasGear(ArmorStandEntity a) {
        for (var s : a.getEquippedItems()) if (!s.isEmpty()) return true;
        return false;
    }

    /**
     * Furnishes one floor with a package. keepClear: spots never to use (the cellar hatch).
     * Returns every block position placed, for taking it back later.
     */
    public static List<Long> furnish(ServerWorld w, Package pkg, Floor fl, int x0, int z0, int x1, int z1, String tag, List<BlockPos> keepClear) {
        int y = fl.y() + 1;
        int f = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        List<Long> placed = new ArrayList<>();
        // Spots along the walls: open, floored, backed by a wall, away from doors and stairs.
        List<BlockPos> spots = new ArrayList<>();
        List<Direction> backs = new ArrayList<>();
        double cx = (x0 + x1) / 2.0, cz = (z0 + z1) / 2.0;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            BlockPos p = new BlockPos(x, y, z);
            if (!ground(w, p.down()) || !open(w, p) || !open(w, p.up()) || !w.getBlockState(p).isAir()) continue;
            Direction back = null;
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos n = p.offset(d);
                BlockState s = w.getBlockState(n);
                if (s.isFullCube(w, n) && !(s.getBlock() instanceof DoorBlock)) back = d;
            }
            if (back == null || nearBusy(w, p) || near(keepClear, p)) continue;
            spots.add(p);
            backs.add(back);
        }
        // Round the room in order, so pieces sit in a natural run along the walls.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < spots.size(); i++) order.add(i);
        order.sort(java.util.Comparator.comparingDouble(i -> Math.atan2(spots.get(i).getZ() + 0.5 - cz, spots.get(i).getX() + 0.5 - cx)));
        // A bigger floor gets more: roughly a piece every other wall spot, the package repeating.
        int want = Math.min(order.size() / 2, Math.max(pkg.pieces().size(), fl.area() / 7));
        java.util.Set<Long> used = new java.util.HashSet<>();
        int k = 0;
        for (int oi = 0; oi < order.size() && k < want; oi += 2) {
            int i = order.get(oi);
            BlockPos p = spots.get(i);
            if (used.contains(p.asLong()) || !w.getBlockState(p).isAir()) continue;
            Direction back = backs.get(i), away = back.getOpposite();
            String piece = pkg.pieces().get(k % pkg.pieces().size());
            k++;
            used.add(p.asLong());
            switch (piece) {
                case "shelf" -> {
                    put(w, p, Blocks.BOOKSHELF.getDefaultState(), placed, f);
                    put(w, p.up(), Blocks.BOOKSHELF.getDefaultState(), placed, f);
                }
                case "chair" -> put(w, p, Blocks.SPRUCE_STAIRS.getDefaultState().with(StairsBlock.FACING, back), placed, f);
                case "lamp" -> {
                    put(w, p, Blocks.SPRUCE_FENCE.getDefaultState(), placed, f);
                    put(w, p.up(), Blocks.LANTERN.getDefaultState(), placed, f);
                }
                case "table" -> {
                    put(w, p, Blocks.DARK_OAK_FENCE.getDefaultState(), placed, f);
                    put(w, p.up(), Blocks.DARK_OAK_PRESSURE_PLATE.getDefaultState(), placed, f);
                }
                case "candles" -> {
                    put(w, p, Blocks.DARK_OAK_FENCE.getDefaultState(), placed, f);
                    put(w, p.up(), Blocks.CANDLE.getDefaultState().with(Properties.CANDLES, 3).with(Properties.LIT, true), placed, f);
                }
                case "plant" -> {
                    BlockState[] pots = {Blocks.POTTED_FERN.getDefaultState(), Blocks.POTTED_AZALEA_BUSH.getDefaultState(), Blocks.POTTED_RED_TULIP.getDefaultState()};
                    put(w, p, pots[Math.floorMod(p.getX() * 31 + p.getZ(), pots.length)], placed, f);
                }
                case "pot" -> put(w, p, Blocks.DECORATED_POT.getDefaultState(), placed, f);
                case "chest" -> put(w, p, Blocks.CHEST.getDefaultState().with(Properties.HORIZONTAL_FACING, away), placed, f);
                case "barrel" -> put(w, p, Blocks.BARREL.getDefaultState().with(Properties.FACING, Direction.UP), placed, f);
                case "bed" -> {
                    BlockPos foot = p.offset(away);
                    if (w.getBlockState(foot).isAir() && ground(w, foot.down()) && !used.contains(foot.asLong())) {
                        put(w, p, Blocks.RED_BED.getDefaultState().with(BedBlock.PART, BedPart.HEAD).with(BedBlock.FACING, back), placed, f);
                        put(w, foot, Blocks.RED_BED.getDefaultState().with(BedBlock.PART, BedPart.FOOT).with(BedBlock.FACING, back), placed, f);
                        used.add(foot.asLong());
                    } else {
                        put(w, p, Blocks.CHEST.getDefaultState().with(Properties.HORIZONTAL_FACING, away), placed, f);
                    }
                }
                case "stand" -> {
                    ArmorStandEntity s = EntityType.ARMOR_STAND.create(w);
                    if (s != null) {
                        s.refreshPositionAndAngles(p.getX() + 0.5, y, p.getZ() + 0.5, away.asRotation(), 0);
                        s.addCommandTag(tag);
                        w.spawnEntity(s);
                    }
                }
                case "banner" -> put(w, p.up(), (Math.floorMod(p.getX() + p.getZ(), 2) == 0 ? Blocks.RED_WALL_BANNER : Blocks.GREEN_WALL_BANNER)
                    .getDefaultState().with(Properties.HORIZONTAL_FACING, away), placed, f);
                case "lectern" -> put(w, p, Blocks.LECTERN.getDefaultState().with(Properties.HORIZONTAL_FACING, away), placed, f);
                case "maps" -> put(w, p, Blocks.CARTOGRAPHY_TABLE.getDefaultState(), placed, f);
                case "smoker" -> put(w, p, Blocks.SMOKER.getDefaultState().with(Properties.HORIZONTAL_FACING, away), placed, f);
                case "furnace" -> put(w, p, Blocks.FURNACE.getDefaultState().with(Properties.HORIZONTAL_FACING, away), placed, f);
                case "cauldron" -> put(w, p, Blocks.WATER_CAULDRON.getDefaultState().with(Properties.LEVEL_3, 3), placed, f);
                case "crafting" -> put(w, p, Blocks.CRAFTING_TABLE.getDefaultState(), placed, f);
                case "hay" -> put(w, p, Blocks.HAY_BLOCK.getDefaultState(), placed, f);
                case "smithing" -> put(w, p, Blocks.SMITHING_TABLE.getDefaultState(), placed, f);
                case "grindstone" -> put(w, p, Blocks.GRINDSTONE.getDefaultState().with(Properties.HORIZONTAL_FACING, away)
                    .with(Properties.BLOCK_FACE, net.minecraft.block.enums.BlockFace.FLOOR), placed, f);
                case "anvil" -> put(w, p, Blocks.ANVIL.getDefaultState().with(Properties.HORIZONTAL_FACING, back.rotateYClockwise()), placed, f);
                case "jukebox" -> put(w, p, Blocks.JUKEBOX.getDefaultState(), placed, f);
                default -> { }
            }
        }
        // Lanterns hung from the ceiling down the middle of the room, clear of your head.
        int hung = 0;
        for (int x = x0 + 2; x <= x1 - 2 && hung < 4; x += 5) {
            for (int z = z0 + 2; z <= z1 - 2 && hung < 4; z += 5) {
                BlockPos base = new BlockPos(x, y, z);
                if (!open(w, base) || !open(w, base.up())) continue;
                for (int up = 3; up <= 6; up++) {
                    BlockPos c = base.up(up);
                    if (w.getBlockState(c).isAir()) continue;
                    if (!w.getBlockState(c).isFullCube(w, c)) break;
                    BlockPos l = c.down();
                    if (l.getY() >= y + 2 && w.getBlockState(l).isAir()) {
                        put(w, l, Blocks.LANTERN.getDefaultState().with(Properties.HANGING, true), placed, f);
                        hung++;
                    }
                    break;
                }
            }
        }
        return placed;
    }

    private static void put(ServerWorld w, BlockPos p, BlockState s, List<Long> placed, int f) {
        if (!w.getBlockState(p).isAir()) return;
        w.setBlockState(p, s, f);
        placed.add(p.asLong());
    }

    /** Next to a door, stairs, a ladder or a trapdoor (a way through that must stay clear). */
    private static boolean nearBusy(ServerWorld w, BlockPos p) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 2; dy++) {
            BlockState s = w.getBlockState(p.add(dx, dy, dz));
            Block b = s.getBlock();
            boolean close = Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
            if (b instanceof DoorBlock) return true;
            if (close && (b instanceof StairsBlock || b instanceof LadderBlock || b instanceof TrapdoorBlock)) return true;
        }
        return false;
    }

    private static boolean near(List<BlockPos> keep, BlockPos p) {
        for (BlockPos k : keep) if (Math.abs(k.getX() - p.getX()) <= 1 && Math.abs(k.getZ() - p.getZ()) <= 1) return true;
        return false;
    }
}
