package com.pglol.aotrpg;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/**
 * Property plots: unique land with a single owner. They lie empty until bought; then a house is
 * built on the plot in the real world (a copy of a fitting town house, door facing the road),
 * and the owner may build and break inside the plot.
 */
final class HomePlots {
    private HomePlots() {}

    static Homes.Data data() {
        return AotRpg.HOMES.data;
    }

    static long price(Places.PlotInfo p) {
        int area = (p.x1() - p.x0() + 1) * (p.z1() - p.z0() + 1);
        double k = switch (p.kind()) {
            case "town" -> 1.6;
            case "lakeside", "coast" -> 1.3;
            case "hilltop", "mountain" -> 1.2;
            default -> 1.0;
        };
        return Math.round((1500 + area * 12L) * k / 50.0) * 50;
    }

    static int plotAt(BlockPos pos, int margin) {
        var list = AotRpg.PLACES.plots;
        for (int i = 0; i < list.size(); i++) {
            Places.PlotInfo p = list.get(i);
            if (pos.getX() >= p.x0() - margin && pos.getX() <= p.x1() + margin && pos.getZ() >= p.z0() - margin && pos.getZ() <= p.z1() + margin
                && Math.abs(pos.getY() - p.y()) < 30) return i;
        }
        return -1;
    }

    /** The land that comes with a plot: the plot and its surround out to the road (for building). */
    static final int LAND = 3;

    static boolean ownsAt(ServerPlayerEntity p, BlockPos pos) {
        int i = plotAt(pos, LAND);
        if (i < 0) return false;
        Homes.PlotDeed d = data().plots.get(i);
        return d != null && d.stem.equals(AotRpg.HOMES.stem(p));
    }

    /** Any sold plot's land here? (Its blocks are the owner's: never regenerated.) */
    static boolean ownedAt(BlockPos pos) {
        if (data().plots.isEmpty()) return false;
        int i = plotAt(pos, LAND);
        return i >= 0 && data().plots.containsKey(i);
    }

    static String label(Places.PlotInfo p) {
        return "Property #" + p.id() + (p.region().isEmpty() ? "" : " · " + p.region());
    }

    static void buy(ServerPlayerEntity p, int idx, long price) {
        if (idx < 0 || idx >= AotRpg.PLACES.plots.size() || data().plots.containsKey(idx)) return;
        if (!AotRpg.WALLET.spendMarks(p, price)) {
            p.sendMessage(Text.literal("You need " + price + " Marks.").formatted(Formatting.RED), true);
            return;
        }
        give(AotRpg.HOMES.stem(p), AotRpg.PROFILES.get(p.getUuid()).name, idx, p.getServerWorld().getServer().getOverworld());
        Titles.show(p, Text.literal("PROPERTY BOUGHT").formatted(Formatting.GOLD, Formatting.BOLD),
            Text.literal(label(AotRpg.PLACES.plots.get(idx))).formatted(Formatting.GRAY), 10, 50, 20);
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.BLOCKS, 0.5f, 1.2f);
    }

    static void give(String stem, String name, int idx, ServerWorld ow) {
        Homes.PlotDeed d = new Homes.PlotDeed();
        d.stem = stem;
        d.ownerName = name;
        Places.PlotInfo plot = AotRpg.PLACES.plots.get(idx);
        d.template = pickTemplate(plot);
        data().plots.put(idx, d);
        AotRpg.HOMES.save();
        WorldCare.quiet(true);
        try {
            removeSign(ow, plot);
        } finally {
            WorldCare.quiet(false);
        }
        if (d.template >= 0) build(ow, plot, AotRpg.PLACES.homes.get(d.template));
    }

    /** Takes down the "For sale" sign by the gate (it stands a few blocks outside the plot). */
    static int removeSign(ServerWorld ow, Places.PlotInfo p) {
        int n = 0;
        for (int x = p.x0() - 6; x <= p.x1() + 6; x++) {
            for (int z = p.z0() - 6; z <= p.z1() + 6; z++) {
                boolean inside = x >= p.x0() && x <= p.x1() && z >= p.z0() && z <= p.z1();
                if (inside) continue;
                ow.getChunk(x >> 4, z >> 4);
                for (int y = p.y() - 4; y <= p.y() + 4; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (ow.getBlockState(pos).getBlock() instanceof net.minecraft.block.AbstractSignBlock) {
                        ow.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                        n++;
                    }
                }
            }
        }
        return n;
    }

    static void revoke(int idx) {
        data().plots.remove(idx);
        AotRpg.HOMES.save();
    }

    private static String doorSide(int[] h) {
        if (h[7] < h[1]) return "north";
        if (h[7] > h[3]) return "south";
        if (h[6] < h[0]) return "west";
        return "east";
    }

    /** The biggest town house that fits the plot with its door towards the road. */
    private static int pickTemplate(Places.PlotInfo p) {
        int w = p.x1() - p.x0() + 1, d = p.z1() - p.z0() + 1;
        int best = -1, bestArea = 0, fallback = -1, fallbackArea = 0;
        var homes = AotRpg.PLACES.homes;
        for (int i = 0; i < homes.size(); i++) {
            int[] h = homes.get(i);
            if (h[4] < 40) continue; // not the Underground ones
            int hw = h[2] - h[0] + 3, hd = h[3] - h[1] + 3;
            if (hw > w - 2 || hd > d - 4) continue;
            int area = hw * hd;
            if (doorSide(h).equals(p.gate()) && area > bestArea) {
                best = i;
                bestArea = area;
            } else if (area > fallbackArea) {
                fallback = i;
                fallbackArea = area;
            }
        }
        return best >= 0 ? best : fallback;
    }

    /** Copies the template house onto the plot and lays a path to the road. */
    private static void build(ServerWorld ow, Places.PlotInfo p, int[] h) {
        int hw = h[2] - h[0] + 3, hd = h[3] - h[1] + 3;
        int ox = p.x0() + (p.x1() - p.x0() + 1 - hw) / 2, oz = p.z0() + (p.z1() - p.z0() + 1 - hd) / 2;
        int dy = (p.y() - 1) - h[4];
        int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        WorldCare.quiet(true);
        try {
            // Clear the plot first (and any old fence around it).
            clearFence(ow, p);
            for (int x = h[0] - 1; x <= h[2] + 1; x++) {
                for (int z = h[1] - 1; z <= h[3] + 1; z++) {
                    ow.getChunk(x >> 4, z >> 4);
                    for (int y = h[4]; y <= h[5] + 3; y++) {
                        BlockPos src = new BlockPos(x, y, z);
                        BlockPos dst = new BlockPos(ox + x - (h[0] - 1), y + dy, oz + z - (h[1] - 1));
                        BlockState st = ow.getBlockState(src);
                        ow.setBlockState(dst, st, flags);
                        BlockEntity be = ow.getBlockEntity(src);
                        if (be != null) {
                            NbtCompound n = be.createNbt(ow.getRegistryManager());
                            n.remove("Items");
                            n.remove("LootTable");
                            BlockEntity nb = ow.getBlockEntity(dst);
                            if (nb != null) nb.read(n, ow.getRegistryManager());
                        }
                    }
                }
            }
            HouseFix.stairs(ow, new BlockPos(ox, p.y() - 1, oz), new BlockPos(ox + hw - 1, h[5] + 3 + dy, oz + hd - 1));
            // Path from the door to the plot edge on the road side.
            int dx = ox + h[6] - (h[0] - 1), dz = oz + h[7] - (h[1] - 1);
            int sx = 0, sz = 0;
            switch (p.gate()) {
                case "north" -> sz = -1;
                case "south" -> sz = 1;
                case "west" -> sx = -1;
                default -> sx = 1;
            }
            for (int k = 0; k < 40; k++) {
                int x = dx + sx * k, z = dz + sz * k;
                if (x < p.x0() - 1 || x > p.x1() + 1 || z < p.z0() - 1 || z > p.z1() + 1) break;
                BlockPos g = new BlockPos(x, p.y() - 1, z);
                if (ow.getBlockState(g.up()).isAir()) ow.setBlockState(g, Blocks.DIRT_PATH.getDefaultState(), flags);
            }
        } finally {
            WorldCare.quiet(false);
        }
    }

    /** Removes an old generated fence, gate, corner walls and lanterns around a plot. */
    static int clearFence(ServerWorld ow, Places.PlotInfo p) {
        int n = 0;
        for (int x = p.x0() - 1; x <= p.x1() + 1; x++) {
            for (int z = p.z0() - 1; z <= p.z1() + 1; z++) {
                boolean ring = x == p.x0() - 1 || x == p.x1() + 1 || z == p.z0() - 1 || z == p.z1() + 1;
                if (!ring) continue;
                ow.getChunk(x >> 4, z >> 4);
                for (int y = p.y() - 1; y <= p.y() + 2; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState s = ow.getBlockState(pos);
                    if (s.getBlock() instanceof FenceBlock || s.getBlock() instanceof FenceGateBlock || s.getBlock() instanceof WallBlock
                        || s.isOf(Blocks.LANTERN)) {
                        ow.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                        n++;
                    }
                    if (s.isOf(Blocks.DIRT_PATH) && y == p.y() - 1) ow.setBlockState(pos, Blocks.GRASS_BLOCK.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
            }
        }
        return n;
    }

    /** /aotrpg patch plots: clears the old fences of every unsold plot. */
    static int patchAll(ServerWorld ow) {
        int n = 0;
        WorldCare.quiet(true);
        try {
            for (int i = 0; i < AotRpg.PLACES.plots.size(); i++) {
                if (!data().plots.containsKey(i)) n += clearFence(ow, AotRpg.PLACES.plots.get(i));
                else {
                    Places.PlotInfo p = AotRpg.PLACES.plots.get(i);
                    n += removeSign(ow, p);
                    n += HouseFix.stairs(ow, new BlockPos(p.x0(), p.y() - 1, p.z0()), new BlockPos(p.x1(), p.y() + 40, p.z1()));
                }
            }
        } finally {
            WorldCare.quiet(false);
        }
        return n;
    }
}
