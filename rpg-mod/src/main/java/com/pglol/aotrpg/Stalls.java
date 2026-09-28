package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LanternBlock;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Market stalls for the town's traders: fence posts, a striped awning in the trade's colours, a
 * counter of barrels and planks with their wares on it, a lantern; a dark tent for the Stranger.
 * Built once where the trader stands (facing the square), and remembered.
 */
public final class Stalls {
    private Stalls() {}

    /** A trader's spot: where they stand, and which way the counter faces. */
    public record Spot(int x, int y, int z, int dir) {
        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        public Direction facing() {
            return Direction.fromHorizontal(dir);
        }
    }

    private static final Gson GSON = new Gson();
    private static Map<String, Spot> spots = new HashMap<>();
    private static Path file;

    public static void open(MinecraftServer server) {
        file = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("stalls.json");
        try {
            if (Files.exists(file)) spots = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<Map<String, Spot>>() { }.getType());
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read stalls.json", e);
        }
        if (spots == null) spots = new HashMap<>();
    }

    private static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(spots), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save stalls.json", e);
        }
    }

    public static Spot get(String key) {
        return spots.get(key);
    }

    /**
     * Finds and builds a stall for this trader (once): open, level ground at the right distance from
     * the square, the counter facing it. Null if nowhere fits yet (chunks not loaded).
     */
    public static Spot place(ServerWorld w, String key, Net.Area town, String kind, double minR, double maxR, long seed) {
        Spot have = spots.get(key);
        if (have != null) return have;
        java.util.Random r = new java.util.Random(seed);
        int base = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, town.x(), town.z());
        for (int tries = 0; tries < 160; tries++) {
            double a = r.nextDouble() * Math.PI * 2, rad = minR + r.nextDouble() * (maxR - minR);
            int x = town.x() + (int) Math.round(Math.cos(a) * rad), z = town.z() + (int) Math.round(Math.sin(a) * rad);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(y - base) > (kind.equals("STRANGER") ? 8 : 4)) continue;
            Direction f = Direction.getFacing(town.x() - x, 0, town.z() - z);
            if (kind.equals("STRANGER")) f = f.getOpposite();
            if (f.getAxis() == Direction.Axis.Y) continue;
            BlockPos v = new BlockPos(x, y, z);
            if (!fits(w, v, f)) continue;
            boolean clash = false;
            for (Spot s : spots.values()) if (s.pos().getSquaredDistance(v) < 8 * 8) clash = true;
            if (clash) continue;
            build(w, v, f, kind);
            Spot s = new Spot(x, y, z, f.getHorizontal());
            spots.put(key, s);
            save();
            return s;
        }
        return null;
    }

    private static BlockPos at(BlockPos v, Direction f, int right, int fwd, int up) {
        Direction rt = f.rotateYClockwise();
        return v.offset(f, fwd).offset(rt, right).up(up);
    }

    /** Room for the stall and a customer in front: level, solid ground, open air, no water, nothing built. */
    private static boolean fits(ServerWorld w, BlockPos v, Direction f) {
        for (int rr = -2; rr <= 2; rr++) {
            for (int ff = -1; ff <= 3; ff++) {
                BlockPos g = at(v, f, rr, ff, -1);
                if (!w.isChunkLoaded(g.getX() >> 4, g.getZ() >> 4)) return false;
                BlockState gs = w.getBlockState(g);
                if (!gs.isSolidBlock(w, g) || !w.getFluidState(g).isEmpty()) return false;
                for (int u = 0; u <= 3; u++) if (!w.getBlockState(at(v, f, rr, ff, u)).isAir()) return false;
            }
        }
        return true;
    }

    private static final Map<String, BlockState[]> AWNING = Map.of(
        "ARMORER", new BlockState[] {Blocks.LIGHT_GRAY_WOOL.getDefaultState(), Blocks.WHITE_WOOL.getDefaultState()},
        "BLADESMITH", new BlockState[] {Blocks.RED_WOOL.getDefaultState(), Blocks.WHITE_WOOL.getDefaultState()},
        "PROVISIONER", new BlockState[] {Blocks.GREEN_WOOL.getDefaultState(), Blocks.WHITE_WOOL.getDefaultState()},
        "TOOLMAKER", new BlockState[] {Blocks.BROWN_WOOL.getDefaultState(), Blocks.YELLOW_WOOL.getDefaultState()},
        "MERCHANT", new BlockState[] {Blocks.BLUE_WOOL.getDefaultState(), Blocks.WHITE_WOOL.getDefaultState()},
        "STRANGER", new BlockState[] {Blocks.BLACK_WOOL.getDefaultState(), Blocks.GRAY_WOOL.getDefaultState()});

    private static void set(ServerWorld w, BlockPos p, BlockState s) {
        w.setBlockState(p, s, Block.NOTIFY_ALL);
    }

    /** The stall itself, around the trader standing at v, its counter facing f. */
    public static void build(ServerWorld w, BlockPos v, Direction f, String kind) {
        WorldCare.quiet(true);
        try {
            boolean shady = kind.equals("STRANGER");
            BlockState post = shady ? Blocks.DARK_OAK_FENCE.getDefaultState() : Blocks.SPRUCE_FENCE.getDefaultState();
            BlockState plank = shady ? Blocks.DARK_OAK_PLANKS.getDefaultState() : Blocks.SPRUCE_PLANKS.getDefaultState();
            BlockState[] cloth = AWNING.getOrDefault(kind, AWNING.get("MERCHANT"));
            // Posts at the four corners, three high.
            for (int rr : new int[] {-2, 2}) for (int ff : new int[] {-1, 1}) for (int u = 0; u <= 2; u++) set(w, at(v, f, rr, ff, u), post);
            // The awning: stripes across, running a block out over the customer.
            for (int rr = -2; rr <= 2; rr++) {
                for (int ff = -1; ff <= 2; ff++) {
                    BlockPos p = at(v, f, rr, ff, 3);
                    set(w, p, cloth[Math.floorMod(rr, 2)]);
                }
            }
            if (shady) {
                // A tent: cloth walls on three sides, a dim floor, a soul lantern and a cobweb.
                for (int u = 0; u <= 2; u++) {
                    for (int ff = -1; ff <= 1; ff++) {
                        set(w, at(v, f, -2, ff, u), cloth[0]);
                        set(w, at(v, f, 2, ff, u), cloth[0]);
                    }
                    for (int rr = -1; rr <= 1; rr++) set(w, at(v, f, rr, -2, u), cloth[0]);
                }
                for (int rr = -1; rr <= 1; rr++) for (int ff = -1; ff <= 1; ff++) set(w, at(v, f, rr, ff, -1), plank);
                set(w, at(v, f, -1, 1, 0), Blocks.BARREL.getDefaultState());
                set(w, at(v, f, 1, 1, 0), Blocks.CHEST.getDefaultState().with(net.minecraft.block.ChestBlock.FACING, f));
                set(w, at(v, f, 0, 1, 0), Blocks.DARK_OAK_SLAB.getDefaultState());
                set(w, at(v, f, 0, 0, 2), Blocks.SOUL_LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
                set(w, at(v, f, 1, -1, 2), Blocks.COBWEB.getDefaultState());
                set(w, at(v, f, -1, -1, 0), Blocks.BLACK_CARPET.getDefaultState());
                return;
            }
            // The counter along the front, the trader behind it.
            set(w, at(v, f, -1, 1, 0), Blocks.BARREL.getDefaultState());
            set(w, at(v, f, 0, 1, 0), plank);
            set(w, at(v, f, 1, 1, 0), Blocks.BARREL.getDefaultState());
            // Wares on the counter and behind.
            BlockState ware = switch (kind) {
                case "ARMORER" -> Blocks.CHAIN.getDefaultState();
                case "BLADESMITH" -> Blocks.GRINDSTONE.getDefaultState();
                case "PROVISIONER" -> Blocks.HAY_BLOCK.getDefaultState();
                case "TOOLMAKER" -> Blocks.CRAFTING_TABLE.getDefaultState();
                default -> Blocks.BARREL.getDefaultState();
            };
            set(w, at(v, f, 0, 1, 1), switch (kind) {
                case "PROVISIONER" -> Blocks.CAKE.getDefaultState();
                case "BLADESMITH", "ARMORER" -> Blocks.IRON_TRAPDOOR.getDefaultState();
                default -> Blocks.CANDLE.getDefaultState().with(net.minecraft.block.CandleBlock.LIT, true);
            });
            set(w, at(v, f, -1, -1, 0), ware);
            set(w, at(v, f, 1, -1, 0), kind.equals("PROVISIONER") ? Blocks.MELON.getDefaultState()
                : kind.equals("BLADESMITH") ? Blocks.ANVIL.getDefaultState().with(net.minecraft.block.AnvilBlock.FACING, f.rotateYClockwise())
                : kind.equals("ARMORER") ? Blocks.SMITHING_TABLE.getDefaultState() : Blocks.BARREL.getDefaultState());
            if (kind.equals("MERCHANT")) {
                set(w, at(v, f, -1, -1, 1), Blocks.BARREL.getDefaultState());
                set(w, at(v, f, 1, -1, 1), Blocks.CHEST.getDefaultState().with(net.minecraft.block.ChestBlock.FACING, f));
            }
            // A lantern hanging under the awning, over the counter.
            set(w, at(v, f, 0, 1, 2), Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
        } finally {
            WorldCare.quiet(false);
        }
    }
}
