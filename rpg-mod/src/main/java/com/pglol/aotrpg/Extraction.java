package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Extraction. Choosing it takes you down to the staging hall, a lobby of its own: the deployment
 * board to pick a zone and drop in (with your party), your stash (your homes' chests and barrels,
 * plus rows bought with Salvage) and a refueler. Out in the zone titans roam and supply barrels
 * hold gear, gas and the odd refueler; three extraction flares burn somewhere around it. Stand at
 * one for eight seconds to get out with everything you carry. Die out there and it stays where you
 * fell; still out when the clock runs down and you're missing in action, and it's all lost.
 */
public final class Extraction {
    /** The staging hall, in the home world far from any home. */
    static final BlockPos LOBBY = new BlockPos(-400_000, 64, 0);
    private static final int HALF = 11, HEIGHT = 7;
    private static final long RUN_MS = 20 * 60_000L;
    private static final int EXTRACT_TICKS = 160, EXIT_RADIUS = 4, BARRELS = 8;

    static final class Run {
        String zone, zoneName;
        int level;
        final LinkedHashSet<UUID> members = new LinkedHashSet<>();
        long endsAt;
        final List<BlockPos> exits = new ArrayList<>();
        final List<BlockPos> barrels = new ArrayList<>();
        final Map<UUID, Integer> extracting = new HashMap<>();
    }

    static final class Data {
        boolean lobbyBuilt;
        int lobbyVersion;
        List<Long> leftoverBarrels = new ArrayList<>();
    }

    private static final int LOBBY_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private Data data = new Data();
    private Path file;
    private MinecraftServer server;
    private final List<Run> runs = new ArrayList<>();
    private static Extraction self;

    public void open(MinecraftServer server) {
        this.server = server;
        self = this;
        runs.clear();
        file = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("extraction.json");
        try {
            if (Files.exists(file)) data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read extraction.json", e);
        }
        if (data == null) data = new Data();
        if (data.leftoverBarrels == null) data.leftoverBarrels = new ArrayList<>();
        // Barrels left in the world by runs a restart cut short.
        ServerWorld ow = server.getOverworld();
        for (long l : data.leftoverBarrels) {
            BlockPos bp = BlockPos.fromLong(l);
            if (ow.getBlockState(bp).isOf(Blocks.BARREL)) {
                if (ow.getBlockEntity(bp) instanceof BarrelBlockEntity b) b.clear();
                ow.setBlockState(bp, Blocks.AIR.getDefaultState());
            }
        }
        data.leftoverBarrels.clear();
        save();
        Stash.open(server);
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save extraction.json", e);
        }
    }

    // ------------------------------------------------------------------ the lobby

    private ServerWorld hall() {
        return AotRpg.HOMES.stashWorld();
    }

    public static boolean inLobby(ServerPlayerEntity p) {
        return p.getWorld().getRegistryKey() == Homes.WORLD && Math.abs(p.getX() - LOBBY.getX()) < HALF + 4
            && Math.abs(p.getZ() - LOBBY.getZ()) < HALF + 4;
    }

    public static boolean inLobby(BlockPos pos) {
        return Math.abs(pos.getX() - LOBBY.getX()) <= HALF + 4 && Math.abs(pos.getZ() - LOBBY.getZ()) <= HALF + 4;
    }

    private static final BlockPos BOARD = LOBBY.add(0, 1, -HALF + 2), STASH = LOBBY.add(HALF - 2, 1, 0), FUEL = LOBBY.add(-HALF + 2, 1, 0);

    /** Builds the staging hall: a stone and timber room, lit, with the board, the stash and a refueler. */
    private void buildLobby() {
        ServerWorld w = hall();
        if (w == null || data.lobbyBuilt && data.lobbyVersion == LOBBY_VERSION) return;
        int f = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        int x0 = LOBBY.getX(), y0 = LOBBY.getY(), z0 = LOBBY.getZ();
        WorldCare.quiet(true);
        try {
            for (int x = -HALF - 1; x <= HALF + 1; x++) {
                for (int z = -HALF - 1; z <= HALF + 1; z++) {
                    boolean wall = Math.abs(x) == HALF + 1 || Math.abs(z) == HALF + 1;
                    boolean pillar = wall && (Math.floorMod(x, 6) == 0 || Math.floorMod(z, 6) == 0 || Math.abs(x) == Math.abs(z));
                    w.setBlockState(new BlockPos(x0 + x, y0 - 1, z0 + z), Blocks.STONE.getDefaultState(), f);
                    BlockState floor = Math.abs(x) <= 1 || Math.abs(z) <= 1 ? Blocks.POLISHED_DEEPSLATE.getDefaultState()
                        : (x + z) % 4 == 0 ? Blocks.CRACKED_STONE_BRICKS.getDefaultState() : Blocks.STONE_BRICKS.getDefaultState();
                    w.setBlockState(new BlockPos(x0 + x, y0, z0 + z), wall ? Blocks.STONE_BRICKS.getDefaultState() : floor, f);
                    for (int y = 1; y <= HEIGHT; y++) {
                        BlockState s = Blocks.AIR.getDefaultState();
                        if (wall) s = pillar ? Blocks.STRIPPED_DARK_OAK_LOG.getDefaultState() : y <= 2 ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.SPRUCE_PLANKS.getDefaultState();
                        w.setBlockState(new BlockPos(x0 + x, y0 + y, z0 + z), s, f);
                    }
                    BlockState roof = Math.floorMod(x, 4) == 0 && Math.floorMod(z, 4) == 0 && !wall ? Blocks.SHROOMLIGHT.getDefaultState() : Blocks.DARK_OAK_PLANKS.getDefaultState();
                    w.setBlockState(new BlockPos(x0 + x, y0 + HEIGHT + 1, z0 + z), roof, f);
                }
            }
            // Beams across the ceiling, lanterns hanging from them.
            for (int x = -HALF; x <= HALF; x++) {
                for (int z : new int[] {-6, 0, 6}) {
                    w.setBlockState(new BlockPos(x0 + x, y0 + HEIGHT, z0 + z), Blocks.STRIPPED_SPRUCE_LOG.getDefaultState()
                        .with(net.minecraft.state.property.Properties.AXIS, Direction.Axis.X), f);
                    if (Math.floorMod(x, 5) == 0) w.setBlockState(new BlockPos(x0 + x, y0 + HEIGHT - 1, z0 + z),
                        Blocks.LANTERN.getDefaultState().with(net.minecraft.block.LanternBlock.HANGING, true), f);
                }
            }
            // The deployment board: a lectern on a dais under the regiment banners.
            for (int x = -2; x <= 2; x++) for (int z = -1; z <= 0; z++)
                w.setBlockState(new BlockPos(x0 + x, y0, BOARD.getZ() + z), Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState(), f);
            w.setBlockState(BOARD, Blocks.LECTERN.getDefaultState().with(net.minecraft.block.LecternBlock.FACING, Direction.SOUTH), f);
            w.setBlockState(BOARD.add(-2, 1, -1), Blocks.GREEN_WALL_BANNER.getDefaultState().with(net.minecraft.block.WallBannerBlock.FACING, Direction.SOUTH), f);
            w.setBlockState(BOARD.add(2, 1, -1), Blocks.GREEN_WALL_BANNER.getDefaultState().with(net.minecraft.block.WallBannerBlock.FACING, Direction.SOUTH), f);
            w.setBlockState(BOARD.add(0, 3, -1), Blocks.CARTOGRAPHY_TABLE.getDefaultState(), f);
            // The stash on the east wall, the refueler on the west.
            w.setBlockState(STASH, Blocks.ENDER_CHEST.getDefaultState().with(net.minecraft.block.EnderChestBlock.FACING, Direction.WEST), f);
            w.setBlockState(STASH.add(0, 0, -1), Blocks.BARREL.getDefaultState().with(net.minecraft.block.BarrelBlock.FACING, Direction.UP), f);
            w.setBlockState(STASH.add(0, 0, 1), Blocks.BARREL.getDefaultState().with(net.minecraft.block.BarrelBlock.FACING, Direction.UP), f);
            if (Refueler.BLOCK != null) w.setBlockState(FUEL, Refueler.BLOCK.getDefaultState(), f);
            // Supply crates and racks along the south wall.
            for (int x = -HALF + 1; x <= HALF - 1; x += 3) {
                w.setBlockState(new BlockPos(x0 + x, y0 + 1, z0 + HALF), Blocks.BARREL.getDefaultState(), f);
                w.setBlockState(new BlockPos(x0 + x + 1, y0 + 1, z0 + HALF), Blocks.SPRUCE_TRAPDOOR.getDefaultState(), f);
            }
            data.lobbyBuilt = true;
            data.lobbyVersion = LOBBY_VERSION;
            save();
        } finally {
            WorldCare.quiet(false);
        }
    }

    /** Down to the staging hall (from the open world, or back from a run). */
    public void toLobby(ServerPlayerEntity p) {
        ServerWorld w = hall();
        if (w == null) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (p.getWorld().getRegistryKey() == World.OVERWORLD && !pr.inRun) pr.openWorldPos = new double[] {p.getX(), p.getY(), p.getZ(), p.getYaw()};
        pr.inRun = false;
        AotRpg.PROFILES.save(p.getUuid());
        buildLobby();
        Homes.border(w, p);
        p.stopRiding();
        p.fallDistance = 0;
        p.teleport(w, LOBBY.getX() + 0.5, LOBBY.getY() + 1, LOBBY.getZ() + 4.5, 180, 0);
        p.playSoundToPlayer(SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, SoundCategory.PLAYERS, 0.8f, 0.8f);
    }

    /** Up from the lobby into the open world, where the character was before. */
    public void toOpenWorld(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        ServerWorld ow = server.getOverworld();
        double[] at = pr.openWorldPos;
        pr.openWorldPos = null;
        AotRpg.PROFILES.save(p.getUuid());
        if (at != null && at.length >= 4) p.teleport(ow, at[0], at[1], at[2], (float) at[3], 0);
        else {
            BlockPos sp = ow.getSpawnPos();
            p.teleport(ow, sp.getX() + 0.5, ow.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, sp.getX(), sp.getZ()), sp.getZ() + 0.5, p.getYaw(), 0);
        }
    }

    /** The lobby's blocks: the board opens deployment, the ender chest the stash. True when handled. */
    public boolean use(ServerPlayerEntity p, BlockPos pos) {
        if (p.getWorld().getRegistryKey() != Homes.WORLD || !inLobby(pos)) {
            Run r = runOf(p.getUuid());
            if (r != null && r.barrels.contains(pos) && p.getServerWorld().getBlockEntity(pos) instanceof BarrelBlockEntity b) {
                p.openHandledScreen(b);
                return true;
            }
            return false;
        }
        if (pos.equals(BOARD)) {
            send(p, true);
            return true;
        }
        if (pos.equals(STASH)) {
            Stash.show(p, 0);
            return true;
        }
        if (pos.equals(FUEL)) return false;
        // Nothing else in the hall opens (the crates are dressing).
        return true;
    }

    // ------------------------------------------------------------------ zones and runs

    /** The zones you can drop into: titan country and landmarks with titans about. */
    private List<Net.Area> zones() {
        List<Net.Area> out = new ArrayList<>();
        for (Net.Area a : AotRpg.PLACES.areas()) {
            if (a.look().equals("danger") || (a.look().equals("landmark") || a.look().equals("cave")) && a.titans() > 0) out.add(a);
        }
        out.sort((a, b) -> Integer.compare(a.min() + a.max(), b.min() + b.max()));
        return out;
    }

    private Run runOf(UUID id) {
        for (Run r : runs) if (r.members.contains(id)) return r;
        return null;
    }

    public static boolean inRun(UUID id) {
        return self != null && self.runOf(id) != null;
    }

    public void deploy(ServerPlayerEntity p, String zoneId) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!DeathCare.EXTRACTION.equals(pr.mode) || !inLobby(p) || runOf(p.getUuid()) != null) return;
        Parties.Party party = AotRpg.PARTIES.of(p.getUuid());
        if (party != null && !p.getUuid().equals(party.leader)) {
            Notify.toast(p, Text.literal("Your squad leader deploys").formatted(Formatting.GOLD), null, 0xE0B96A);
            return;
        }
        Net.Area zone = null;
        for (Net.Area a : zones()) if (a.id().equals(zoneId)) zone = a;
        if (zone == null) return;
        List<ServerPlayerEntity> squad = new ArrayList<>();
        squad.add(p);
        if (party != null) {
            for (UUID id : party.members) {
                ServerPlayerEntity m = server.getPlayerManager().getPlayer(id);
                if (m != null && m != p && inLobby(m) && DeathCare.EXTRACTION.equals(AotRpg.PROFILES.get(id).mode) && runOf(id) == null) squad.add(m);
            }
        }
        ServerWorld ow = server.getOverworld();
        Random rnd = p.getRandom();
        Run run = new Run();
        run.zone = zone.id();
        run.zoneName = zone.name();
        run.level = Math.max(1, (zone.min() + zone.max()) / 2);
        run.endsAt = System.currentTimeMillis() + RUN_MS;
        double base = rnd.nextDouble() * Math.PI * 2;
        BlockPos drop = surface(ow, zone.x() + (int) (Math.cos(base) * 90), zone.z() + (int) (Math.sin(base) * 90));
        // The exits, spread round the far side of the zone from the drop.
        for (int i = 0; i < 3; i++) {
            double a = base + Math.PI + (i - 1) * 1.1 + (rnd.nextDouble() - 0.5) * 0.4;
            double d = 110 + rnd.nextInt(80);
            run.exits.add(surface(ow, zone.x() + (int) (Math.cos(a) * d), zone.z() + (int) (Math.sin(a) * d)));
        }
        // Supply barrels scattered through the zone.
        for (int i = 0; i < BARRELS; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = 15 + rnd.nextInt(150);
            BlockPos bp = surface(ow, zone.x() + (int) (Math.cos(a) * d), zone.z() + (int) (Math.sin(a) * d));
            if (!ow.getBlockState(bp).isAir() && !ow.getBlockState(bp).isReplaceable()) continue;
            ow.setBlockState(bp, Blocks.BARREL.getDefaultState().with(net.minecraft.block.BarrelBlock.FACING, Direction.UP));
            if (ow.getBlockEntity(bp) instanceof BarrelBlockEntity b) fill(b, run.level, rnd);
            run.barrels.add(bp);
            data.leftoverBarrels.add(bp.asLong());
        }
        save();
        runs.add(run);
        int k = 0;
        for (ServerPlayerEntity m : squad) {
            run.members.add(m.getUuid());
            Profile mp = AotRpg.PROFILES.get(m.getUuid());
            mp.inRun = true;
            AotRpg.PROFILES.save(m.getUuid());
            double ox = (k % 3 - 1) * 2, oz = (k / 3) * 2;
            k++;
            m.teleport(ow, drop.getX() + 0.5 + ox, drop.getY() + 0.2, drop.getZ() + 0.5 + oz, m.getYaw(), 0);
            m.fallDistance = 0;
            Titles.show(m, Text.literal("DEPLOYED").formatted(Formatting.RED, Formatting.BOLD),
                Text.literal(zone.name() + "  ·  3 flares burn around the zone").formatted(Formatting.GOLD), 8, 60, 20);
            m.playSoundToPlayer(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.MASTER, 0.5f, 1.4f);
            m.playSoundToPlayer(SoundEvents.ITEM_TRIDENT_RIPTIDE_3.value(), SoundCategory.MASTER, 0.8f, 0.9f);
        }
    }

    /** The first open spot on the ground at x, z. */
    private static BlockPos surface(ServerWorld w, int x, int z) {
        w.getChunk(x >> 4, z >> 4);
        int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, Math.max(y, w.getSeaLevel()), z);
    }

    /** A supply barrel: gear for the zone's level, gas, blades, and now and then a refueler. */
    private static void fill(BarrelBlockEntity b, int level, Random rnd) {
        Random r = rnd;
        List<ItemStack> loot = new ArrayList<>();
        int gear = 1 + r.nextInt(3);
        for (int i = 0; i < gear; i++) {
            ItemStack s = Gear.roll(r, Gear.rollRarity(r, 1), Math.max(1, level + r.nextInt(3) - 1));
            if (!s.isEmpty()) loot.add(s);
        }
        net.minecraft.item.Item gas = AotItems.exact("gas_canister"), blades = AotItems.exact("blade_component");
        if (gas != null && r.nextFloat() < 0.7f) loot.add(new ItemStack(gas, 1 + r.nextInt(2)));
        if (blades != null && r.nextFloat() < 0.6f) loot.add(new ItemStack(blades, 4 + r.nextInt(9)));
        if (r.nextFloat() < 0.5f) loot.add(new ItemStack(Items.BREAD, 2 + r.nextInt(4)));
        if (Refueler.ITEM != null && r.nextFloat() < 0.08f) loot.add(new ItemStack(Refueler.ITEM));
        for (ItemStack s : loot) {
            for (int tries = 0; tries < 10; tries++) {
                int slot = r.nextInt(b.size());
                if (b.getStack(slot).isEmpty()) {
                    b.setStack(slot, s);
                    break;
                }
            }
        }
    }

    public void tick(int ticks) {
        if (runs.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Run r : new ArrayList<>(runs)) {
            for (UUID id : new ArrayList<>(r.members)) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                if (p == null) continue;
                if (now > r.endsAt) {
                    missing(r, p);
                    continue;
                }
                if (p.getWorld().getRegistryKey() != World.OVERWORLD) continue;
                if (ticks % 10 == 0) flares(r, p, ticks);
                // Standing at an exit: eight seconds and you're out.
                BlockPos at = null;
                for (BlockPos e : r.exits) {
                    double dx = p.getX() - e.getX() - 0.5, dz = p.getZ() - e.getZ() - 0.5;
                    if (dx * dx + dz * dz < EXIT_RADIUS * EXIT_RADIUS && Math.abs(p.getY() - e.getY()) < 8) at = e;
                }
                if (at == null) {
                    if (r.extracting.remove(id) != null) p.sendMessage(Text.literal("Extraction interrupted").formatted(Formatting.RED), true);
                    if (ticks % 10 == 0) hint(r, p, now);
                    continue;
                }
                int t = r.extracting.merge(id, 1, Integer::sum);
                if (t % 10 == 0) {
                    int n = Math.min(10, t * 10 / EXTRACT_TICKS);
                    String bar = "▮".repeat(n) + "▯".repeat(10 - n);
                    p.sendMessage(Text.literal("EXTRACTING  ").formatted(Formatting.GOLD, Formatting.BOLD)
                        .append(Text.literal(bar).formatted(Formatting.GREEN)), true);
                    p.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.MASTER, 0.6f, 0.8f + n * 0.1f);
                }
                if (t >= EXTRACT_TICKS) extracted(r, p);
            }
            if (r.members.isEmpty()) end(r);
        }
    }

    /** The flares: tall columns of light over each exit, seen only by the squad. */
    private void flares(Run r, ServerPlayerEntity p, int ticks) {
        ServerWorld w = p.getServerWorld();
        DustParticleEffect red = new DustParticleEffect(new Vector3f(1f, 0.25f, 0.15f), 2.2f);
        for (BlockPos e : r.exits) {
            double x = e.getX() + 0.5, z = e.getZ() + 0.5;
            if (p.squaredDistanceTo(x, e.getY(), z) > 512 * 512) continue;
            for (int y = 0; y < 60; y += 3) w.spawnParticles(p, red, true, x, e.getY() + y + (ticks % 20) / 10.0, z, 1, 0.15, 0.3, 0.15, 0);
            w.spawnParticles(p, ParticleTypes.FLAME, true, x, e.getY() + 0.3, z, 4, 0.4, 0.1, 0.4, 0.01);
            // The ring you stand in.
            for (int i = 0; i < 16; i++) {
                double a = i * Math.PI / 8 + ticks * 0.05;
                w.spawnParticles(p, ParticleTypes.END_ROD, true, x + Math.cos(a) * EXIT_RADIUS, e.getY() + 0.2, z + Math.sin(a) * EXIT_RADIUS, 1, 0, 0, 0, 0);
            }
        }
    }

    private void hint(Run r, ServerPlayerEntity p, long now) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos e : r.exits) {
            double d = p.squaredDistanceTo(e.getX() + 0.5, p.getY(), e.getZ() + 0.5);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        if (best == null) return;
        long left = Math.max(0, r.endsAt - now) / 1000;
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        double ang = Math.toDegrees(Math.atan2(best.getZ() + 0.5 - p.getZ(), best.getX() + 0.5 - p.getX())) - 90 - p.getYaw();
        int dir = Math.floorMod((int) Math.round(ang / 45.0) + 4, 8);
        Formatting clock = left < 120 ? Formatting.RED : left < 300 ? Formatting.GOLD : Formatting.WHITE;
        p.sendMessage(Text.literal(arrows[dir] + " Exit " + (int) Math.sqrt(bd) + "m").formatted(Formatting.GOLD)
            .append(Text.literal("   " + String.format("%d:%02d", left / 60, left % 60)).formatted(clock)), true);
    }

    private void extracted(Run r, ServerPlayerEntity p) {
        r.members.remove(p.getUuid());
        r.extracting.remove(p.getUuid());
        int gear = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) if (Gear.isGear(inv.getStack(i))) gear++;
        long salvage = 25 + gear * 4L + r.level;
        Stash.earn(p, salvage);
        AotRpg.TASKS.count(p, "extractions", 1);
        toLobby(p);
        Reveal.show(p, "EXTRACTED", r.zoneName + "  ·  +" + salvage + " Salvage", "aot_rpg:gas_refueler", gear >= 6 ? 4 : gear >= 3 ? 3 : 2);
    }

    private void missing(Run r, ServerPlayerEntity p) {
        r.members.remove(p.getUuid());
        r.extracting.remove(p.getUuid());
        // Everything carried is lost; the satchel is always safe.
        p.getInventory().clear();
        toLobby(p);
        Titles.show(p, Text.literal("MISSING IN ACTION").formatted(Formatting.DARK_RED, Formatting.BOLD),
            Text.literal("Everything you carried is lost").formatted(Formatting.RED), 10, 70, 20);
        p.playSoundToPlayer(SoundEvents.ENTITY_WITHER_DEATH, SoundCategory.MASTER, 0.4f, 0.8f);
    }

    private void end(Run r) {
        runs.remove(r);
        ServerWorld ow = server.getOverworld();
        for (BlockPos bp : r.barrels) {
            if (ow.getBlockState(bp).isOf(Blocks.BARREL)) {
                if (ow.getBlockEntity(bp) instanceof BarrelBlockEntity b) b.clear();
                ow.setBlockState(bp, Blocks.AIR.getDefaultState());
            }
            data.leftoverBarrels.remove(bp.asLong());
        }
        save();
    }

    /** Died out there: the gear stays where they fell, and they wake in the lobby. */
    private final Set<UUID> fallen = new HashSet<>();

    public void onDeath(ServerPlayerEntity p) {
        Run r = runOf(p.getUuid());
        if (r == null) return;
        r.members.remove(p.getUuid());
        r.extracting.remove(p.getUuid());
        fallen.add(p.getUuid());
    }

    /** After a respawn: true when they were sent to the lobby. */
    public boolean respawn(ServerPlayerEntity p) {
        if (!fallen.remove(p.getUuid())) return false;
        toLobby(p);
        Titles.show(p, Text.literal("KILLED IN ACTION").formatted(Formatting.DARK_RED, Formatting.BOLD),
            Text.literal("Your gear lies where you fell").formatted(Formatting.RED), 10, 60, 20);
        return true;
    }

    /** Joining: someone who logged out mid-run (or whose run a restart ended) starts in the lobby. */
    public void joined(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (pr.inRun && runOf(p.getUuid()) == null) toLobby(p);
    }

    /** Leaving mid-run takes you out of it (you'll be back in the lobby next time, with what you carry). */
    public void forget(ServerPlayerEntity p) {
        Run r = runOf(p.getUuid());
        if (r != null) {
            r.members.remove(p.getUuid());
            r.extracting.remove(p.getUuid());
        }
        fallen.remove(p.getUuid());
    }

    // ------------------------------------------------------------------ the deployment board

    public void action(ServerPlayerEntity p, String action, String arg) {
        if (!inLobby(p)) return;
        switch (action) {
            case "open" -> send(p, true);
            case "deploy" -> deploy(p, arg);
            case "stash" -> Stash.show(p, 0);
            case "stash_page" -> {
                try {
                    Stash.show(p, Integer.parseInt(arg));
                } catch (NumberFormatException ignored) { }
            }
            case "expand" -> {
                Stash.expand(p);
                send(p, false);
            }
            default -> { }
        }
    }

    public void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.ExtractionView.ID)) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        List<Net.ExtractionZone> zs = new ArrayList<>();
        for (Net.Area a : zones()) zs.add(new Net.ExtractionZone(a.id(), a.name(), a.min(), a.max(), a.titans()));
        List<String> squad = new ArrayList<>();
        Parties.Party party = AotRpg.PARTIES.of(p.getUuid());
        boolean leader = party == null || p.getUuid().equals(party.leader);
        if (party != null) {
            for (UUID id : party.members) {
                ServerPlayerEntity m = server.getPlayerManager().getPlayer(id);
                if (m != null && inLobby(m)) squad.add(AotRpg.PROFILES.get(id).name);
            }
        } else squad.add(pr.name);
        int[] use = Stash.usage(p);
        ServerPlayNetworking.send(p, new Net.ExtractionView(zs, squad, leader, pr.salvage, use[0], use[1],
            pr.stashRows >= Stash.MAX_ROWS ? -1 : Stash.rowCost(pr), open));
    }
}
