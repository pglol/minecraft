package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.SaplingBlock;
import net.minecraft.block.StemBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the world clean.
 * Protection: players cannot break or place blocks in the overworld (Paradis, Marley), except in
 * build zones set by operators. Operators in creative mode can always build.
 * Regeneration: blocks destroyed by anything else (titans, explosions, fire, mobs) are remembered
 * and put back after a delay, when no player is right there. Blocks that mobs or falling blocks drop
 * where there was air (thrown debris) are removed again. Commands and operator edits are permanent.
 */
public final class WorldCare {
    public static final class Zone {
        public String name;
        public int x1, z1, x2, z2;

        boolean contains(int x, int z) {
            return x >= Math.min(x1, x2) && x <= Math.max(x1, x2) && z >= Math.min(z1, z2) && z <= Math.max(z1, z2);
        }
    }

    public static final class Config {
        public boolean protect = true;
        public boolean regen = true;
        public int regenDelaySeconds = 300;
        public int blocksPerSecond = 80;
        /** Vanilla loot tables in chests and barrels (off: loot comes from quests, bosses and drops). */
        public boolean vanillaChestLoot = false;
        public List<Zone> buildZones = new ArrayList<>();
    }

    /** debris: something landed here (thrown or fallen blocks); put the original back over it. */
    private record Entry(BlockState state, NbtCompound be, long at, boolean debris) { }

    /** Above zero while a non-player entity ticks (falling blocks, mobs): blocks they place are debris. */
    public static int mobTicking;

    private static final int MAX_ENTRIES = 250_000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Set while a command runs or we restore, so those changes are not recorded. */
    private static final ThreadLocal<Integer> quiet = ThreadLocal.withInitial(() -> 0);

    public Config config = new Config();
    private final Map<Long, Entry> changed = new HashMap<>();
    private MinecraftServer server;
    private Path dir;
    private boolean full;

    public static void quiet(boolean on) {
        quiet.set(Math.max(0, quiet.get() + (on ? 1 : -1)));
    }

    public void open(MinecraftServer server) {
        this.server = server;
        dir = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg");
        changed.clear();
        try {
            Path f = dir.resolve("protection.json");
            if (Files.exists(f)) config = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Config.class);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read protection.json", e);
        }
        if (config == null) config = new Config();
        if (config.buildZones == null) config.buildZones = new ArrayList<>();
        saveConfig();
        try {
            Path f = dir.resolve("regen.nbt");
            if (Files.exists(f)) {
                NbtCompound root = NbtIo.readCompressed(f, NbtSizeTracker.ofUnlimitedBytes());
                NbtList list = root.getList("b", NbtElement.COMPOUND_TYPE);
                for (int i = 0; i < list.size(); i++) {
                    NbtCompound c = list.getCompound(i);
                    BlockState st = NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), c.getCompound("s"));
                    changed.put(c.getLong("p"), new Entry(st, c.contains("e") ? c.getCompound("e") : null, c.getLong("t"), c.getBoolean("d")));
                }
            }
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read regen.nbt", e);
        }
    }

    public void saveConfig() {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("protection.json"), GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save protection.json", e);
        }
    }

    public void save() {
        if (dir == null) return;
        try {
            NbtList list = new NbtList();
            for (var e : changed.entrySet()) {
                NbtCompound c = new NbtCompound();
                c.putLong("p", e.getKey());
                c.put("s", NbtHelper.fromBlockState(e.getValue().state()));
                if (e.getValue().be() != null) c.put("e", e.getValue().be());
                c.putLong("t", e.getValue().at());
                if (e.getValue().debris()) c.putBoolean("d", true);
                list.add(c);
            }
            NbtCompound root = new NbtCompound();
            root.put("b", list);
            Files.createDirectories(dir);
            NbtIo.writeCompressed(root, dir.resolve("regen.nbt"));
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save regen.nbt", e);
        }
    }

    public int pending() {
        return changed.size();
    }

    public boolean inBuildZone(int x, int z) {
        for (Zone zn : config.buildZones) if (zn.contains(x, z)) return true;
        return false;
    }

    /** Can this player change blocks here? */
    public boolean canBuild(PlayerEntity p, BlockPos pos) {
        if (p.getWorld().getRegistryKey() == Homes.WORLD) {
            // In the home world you build only in your own home and yard.
            if (p instanceof ServerPlayerEntity sp && sp.interactionManager.getGameMode() == GameMode.CREATIVE && sp.hasPermissionLevel(2)) return true;
            return p instanceof ServerPlayerEntity sp && AotRpg.HOMES.canBuild(sp, pos);
        }
        if (!config.protect || p.getWorld().getRegistryKey() != World.OVERWORLD) return true;
        if (p instanceof ServerPlayerEntity sp && sp.interactionManager.getGameMode() == GameMode.CREATIVE && sp.hasPermissionLevel(2)) return true;
        return inBuildZone(pos.getX(), pos.getZ()) || (p instanceof ServerPlayerEntity sp && AotRpg.HOMES.ownsPlotAt(sp, pos));
    }

    public void deny(PlayerEntity p) {
        if (p instanceof ServerPlayerEntity sp) {
            sp.sendMessage(Text.literal("This land is protected.").formatted(Formatting.GRAY), true);
            sp.playerScreenHandler.syncState();
        }
    }

    private static boolean transientBlock(BlockState s) {
        return s.isAir() || s.getBlock() instanceof FluidBlock || s.isOf(Blocks.FIRE) || s.isOf(Blocks.SOUL_FIRE)
            || s.isOf(Blocks.MOVING_PISTON) || s.isOf(Blocks.FROSTED_ICE);
    }

    /** Called from the World mixin before any block change on the server. */
    public void beforeChange(ServerWorld w, BlockPos pos, BlockState next) {
        // Cheapest checks first: this runs for every block change on the server.
        boolean destroyed = next.isAir() || next.getBlock() instanceof FluidBlock || next.isOf(Blocks.FIRE) || next.isOf(Blocks.SOUL_FIRE);
        if (!destroyed && mobTicking <= 0) return;
        if (!config.regen || full || w.getRegistryKey() != World.OVERWORLD || quiet.get() > 0) return;
        long key = pos.asLong();
        if (!destroyed) {
            // A block appearing where there was air or water while a mob or falling block ticks:
            // debris from titans and explosions (villager crops and saplings are left alone).
            if (next.getBlock() instanceof CropBlock || next.getBlock() instanceof StemBlock || next.getBlock() instanceof SaplingBlock) return;
            BlockState old = w.getBlockState(pos);
            if (!transientBlock(old) || old.isOf(Blocks.MOVING_PISTON) || inBuildZone(pos.getX(), pos.getZ())) return;
            Entry e = changed.get(key);
            if (e != null) {
                if (!e.debris()) changed.put(key, new Entry(e.state(), e.be(), e.at(), true));
            } else {
                changed.put(key, new Entry(old, null, w.getTime(), true));
            }
            return;
        }
        if (changed.containsKey(key)) return; // keep the first, original state
        BlockState old = w.getBlockState(pos);
        if (transientBlock(old) || inBuildZone(pos.getX(), pos.getZ()) || HomePlots.ownedAt(pos)) return;
        NbtCompound be = null;
        BlockEntity ent = w.getBlockEntity(pos);
        if (ent != null) {
            be = ent.createNbt(w.getRegistryManager());
            be.remove("Items"); // contents dropped already; never duplicate loot
        }
        changed.put(key, new Entry(old, be, w.getTime(), false));
        if (changed.size() >= MAX_ENTRIES) {
            full = true;
            AotRpg.LOG.warn("World regeneration is tracking {} blocks; not recording more until some are restored.", MAX_ENTRIES);
        }
    }

    /** A block an operator removed on purpose stays removed. */
    public void forget(BlockPos pos) {
        changed.remove(pos.asLong());
    }

    public void forgetAll() {
        changed.clear();
        full = false;
    }

    /** Once a second: restore what is due, bottom up, away from players. */
    public void tick(long ticks, boolean force) {
        mobTicking = 0; // safety: never left raised by an entity that crashed mid-tick
        if (changed.isEmpty() || (!config.regen && !force)) return;
        if (ticks % (20 * 300) == 0) save();
        if (!force && ticks % 20 != 0) return;
        ServerWorld w = server.getOverworld();
        long now = w.getTime();
        long delay = config.regenDelaySeconds * 20L;
        List<Map.Entry<Long, Entry>> due = new ArrayList<>();
        for (var e : changed.entrySet()) if (force || now - e.getValue().at() >= delay) due.add(e);
        if (due.isEmpty()) return;
        due.sort((a, b) -> Integer.compare(BlockPos.unpackLongY(a.getKey()), BlockPos.unpackLongY(b.getKey())));
        int budget = force ? Integer.MAX_VALUE : Math.max(1, config.blocksPerSecond);
        quiet(true);
        try {
            for (var e : due) {
                if (budget <= 0) break;
                BlockPos pos = BlockPos.fromLong(e.getKey());
                if (!w.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
                if (!force && w.getClosestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 4, false) != null) continue;
                changed.remove(e.getKey());
                BlockState cur = w.getBlockState(pos);
                Entry en = e.getValue();
                if (cur.isOf(Blocks.MOVING_PISTON) || cur.equals(en.state())) continue;
                // Debris is always cleared; otherwise something new there (a villager replanted,
                // an operator built) is left alone.
                if (!en.debris() && !transientBlock(cur)) continue;
                w.setBlockState(pos, en.state(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                if (en.be() != null) {
                    BlockEntity ent = w.getBlockEntity(pos);
                    if (ent != null) {
                        ent.read(en.be(), w.getRegistryManager());
                        ent.markDirty();
                    }
                }
                budget--;
            }
        } finally {
            quiet(false);
        }
        if (changed.size() < MAX_ENTRIES * 9 / 10) full = false;
    }
}
