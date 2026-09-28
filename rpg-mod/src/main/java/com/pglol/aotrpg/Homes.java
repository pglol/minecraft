package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Homes. Every town house can be bought, by any number of players: the house in town stays
 * shared scenery, and buying it gives a deed whose door leads to your own private copy of that
 * house in the home world, with a fenced yard around it. Inside, everything is yours to arrange
 * and store in (chests stay put). Upgrades build into the yard: a stable with a horse, a forge
 * for gear, a garden, a fishing pond, a storage shed. Party members can visit.
 *
 * Use the door of your house to go in (Sneak + use on any house to see its deed), the front door
 * inside to come back out, or /home from anywhere out of combat.
 */
public final class Homes {
    public static final RegistryKey<World> WORLD = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("aot_rpg", "homes"));
    public static final int MAX_HOMES = 2;
    private static final int BASE_X = 200_000, CELL = 160, YARD = 72, FLOOR = 64, PER_ROW = 64;
    /**
     * Where homes are now: a kilometre apart, so nobody at home ever sees another home (the old
     * layout, 160 apart around BASE_X, is only read to move homes over the first time they're used).
     */
    private static final int BASE_X2 = 1_000_000, CELL2 = 1024;
    /** How far of the real street around a town house is copied in, to look at through the windows. */
    private static final int BACKDROP = 28, OLD_BACKDROP = 14;

    /**
     * Home upgrades. Yards (stable, garden, pond) belong to property plots now: town homes are walled
     * in, so their upgrades go inside and down, into a cellar under the house. Homes that bought yard
     * upgrades before keep their yards.
     */
    public enum Upgrade {
        STABLE("Stable", 2500, "A paddock and stable in your yard: room for 4 more horses.", true),
        GARDEN("Garden", 1500, "Tilled beds with water for growing ingredients.", true),
        POND("Fishing Pond", 1200, "A stocked pond to fish in at home.", true),
        CELLAR("Cellar", 2000, "Dig out a stone cellar under the house, down a ladder hatch: chests, barrels, six bays to fit out.", false),
        STORAGE("Storage Vault", 1000, "Six large chests stacked along a cellar wall.", false),
        FORGE("Cellar Forge", 3000, "Anvil, blast furnace, grindstone, lava trough: upgrade and craft gear.", false),
        ARMORY("Armory", 1800, "Three armor stands for your sets, a weapons rack and a gear barrel.", false),
        KITCHEN("Kitchen", 1200, "A working hearth to cook at, smoker, stores and a table.", false),
        TRAINING("Training Room", 2200, "Straw cadets and targets to practise your cuts on.", false),
        TROPHY("Trophy Hall", 1500, "Regiment banners, mounted skulls, a lectern and a record player.", false);

        public final String title, desc;
        public final int price;
        /** Built in a yard (plots only now) rather than in the cellar. */
        public final boolean yard;

        Upgrade(String title, int price, String desc, boolean yard) {
            this.title = title;
            this.price = price;
            this.desc = desc;
            this.yard = yard;
        }
    }

    public static final class Deed {
        public int home;
        public int instance;
        public List<String> upgrades = new ArrayList<>();
        public String ownerName = "";
        /** Staircases repaired (homes bought before the fix). */
        public boolean stairsFixed;
        /** Blocks over the stairs cleared, so you can walk up without hitting your head. */
        public boolean headroomFixed;
        /** Walled in: no yard, the real street outside the windows, and an invisible wall at the doorstep. */
        public boolean enclosed;
        /** The wider street copy (28 blocks out rather than 14) is in. */
        public boolean backdrop2;
        /** Has a yard (bought yard upgrades before yards moved to plots). */
        public boolean yard;
        /** Headroom over the bottom step cleared too (the upper floor there blocked the climb). */
        public boolean headroom3;
        /** The cellar hatch, in the home world (x, z). */
        public int hatchX, hatchZ;
        /** Bandit raids: days played since the last one, and when. */
        public int daysPlayed;
        public long lastDay, lastRaid;
        /** The street closed in by a ring of town houses (no edge of the world out of the windows). */
        public boolean streetWall;
        /** The chests and barrels counted into the stash (packed positions), null until counted. */
        public List<Long> stash;
        /** Counted with the tight bounds (the house itself and the cellar room, nothing of the street). */
        public boolean stashV2;
    }

    /** An exclusive plot: one owner, the house is built on the plot in the real world. */
    public static final class PlotDeed {
        public String stem;
        public String ownerName = "";
        public int template = -1;
        /** A stable built on the plot (room for more horses). */
        public boolean stable;
        /** Yard buildings on the plot: a fishing pond, a smithy. */
        public boolean pond, smithy;
        /** Home raids: distinct days the owner has played since the last raid, and when it was. */
        public int daysPlayed;
        public long lastDay;
        public long lastRaid;
        /** Estate: wall tier (0 none, 1 palisade, 2 stone wall, 3 fortress), farm and animal pen, the project being built, harvests. */
        public int fort;
        public boolean farm, pen;
        public String building = "";
        public long harvestAt;
        public int harvestStock;
    }

    /** An operator's offer of a home or plot to a player at a set price. */
    public static final class Offer {
        public String kind;
        public int index;
        public String player;
        public String name;
        public long price;
    }

    static final class Data {
        int nextInstance;
        Map<String, List<Deed>> owners = new HashMap<>();
        Map<Integer, String> instances = new HashMap<>();
        Map<Integer, PlotDeed> plots = new HashMap<>();
        List<Offer> offers = new ArrayList<>();
        List<String> recent = new ArrayList<>();
        /** Town houses (the shared ones in the world) whose stairs have been repaired. */
        java.util.Set<Integer> fixedWorld = new java.util.HashSet<>();
        /** Homes already moved to the new, spread-out layout. */
        java.util.Set<Integer> moved = new java.util.HashSet<>();
        /** Town houses given the full stairwell headroom. */
        java.util.Set<Integer> fixedWorld3 = new java.util.HashSet<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    Data data = new Data();
    private Path file;
    private MinecraftServer server;
    private final Map<Long, List<Integer>> byChunk = new HashMap<>();
    private final Map<UUID, double[]> returnTo = new HashMap<>();

    public void open(MinecraftServer server) {
        this.server = server;
        file = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("homes.json");
        try {
            if (Files.exists(file)) data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read homes.json", e);
        }
        if (data == null) data = new Data();
        if (data.owners == null) data.owners = new HashMap<>();
        if (data.instances == null) data.instances = new HashMap<>();
        if (data.plots == null) data.plots = new HashMap<>();
        if (data.offers == null) data.offers = new ArrayList<>();
        if (data.recent == null) data.recent = new ArrayList<>();
        if (data.fixedWorld == null) data.fixedWorld = new java.util.HashSet<>();
        if (data.moved == null) data.moved = new java.util.HashSet<>();
        if (data.fixedWorld3 == null) data.fixedWorld3 = new java.util.HashSet<>();
        // Homes with yard upgrades from before yards moved to plots keep their yards.
        for (List<Deed> list : data.owners.values()) for (Deed d : list) if (!d.enclosed && !d.upgrades.isEmpty()) d.yard = true;
        index();
    }

    /** Indexes houses by chunk for door lookups (call after the world data is read). */
    public void index() {
        byChunk.clear();
        List<int[]> homes = AotRpg.PLACES.homes;
        for (int i = 0; i < homes.size(); i++) {
            int[] h = homes.get(i);
            for (int cx = (h[0] - 1) >> 4; cx <= (h[2] + 1) >> 4; cx++) {
                for (int cz = (h[1] - 1) >> 4; cz <= (h[3] + 1) >> 4; cz++) {
                    byChunk.computeIfAbsent(((long) cx << 32) ^ (cz & 0xFFFFFFFFL), k -> new ArrayList<>()).add(i);
                }
            }
        }
    }

    void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save homes.json", e);
        }
    }

    String stem(ServerPlayerEntity p) {
        return AotRpg.PROFILES.activeStem(p.getUuid());
    }

    public List<Deed> deeds(ServerPlayerEntity p) {
        return data.owners.getOrDefault(stem(p), List.of());
    }

    private Deed deed(ServerPlayerEntity p, int home) {
        for (Deed d : deeds(p)) if (d.home == home) return d;
        return null;
    }

    /** The house whose walls (plus the overhang) contain this block, or -1. */
    public int homeAt(BlockPos pos) {
        List<Integer> list = byChunk.get(((long) (pos.getX() >> 4) << 32) ^ ((pos.getZ() >> 4) & 0xFFFFFFFFL));
        if (list == null) return -1;
        for (int i : list) {
            int[] h = AotRpg.PLACES.homes.get(i);
            if (pos.getX() >= h[0] - 1 && pos.getX() <= h[2] + 1 && pos.getZ() >= h[1] - 1 && pos.getZ() <= h[3] + 1
                && pos.getY() >= h[4] && pos.getY() <= h[5] + 1) return i;
        }
        return -1;
    }

    public static long price(int[] h) {
        int area = (h[2] - h[0] + 1) * (h[3] - h[1] + 1);
        int floors = Math.max(1, (h[5] - h[4]) / 6);
        double region = 1;
        double d = Math.hypot((h[0] + h[2]) / 2.0, (h[1] + h[3]) / 2.0);
        int[] w = AotRpg.PLACES.walls;
        if (h[4] < 40) region = 0.5; // the Underground City
        else if (w != null && d < w[0]) region = 3.0; // inside Wall Sina
        else if (w != null && d < w[1]) region = 1.6;
        return Math.round((1200 + area * 18L * floors) * region / 50.0) * 50;
    }

    String townOf(int[] h) {
        Net.Area a = AotRpg.PLACES.nearest((h[0] + h[2]) / 2.0, (h[1] + h[3]) / 2.0, 800);
        return a == null ? "Paradis" : a.name();
    }

    // ------------------------------------------------------------------ instances

    private static int originX(int n) {
        return BASE_X2 + (n % PER_ROW) * CELL2;
    }

    private static int originZ(int n) {
        return (n / PER_ROW) * CELL2;
    }

    private static int oldOriginX(int n) {
        return BASE_X + (n % PER_ROW) * CELL;
    }

    private static int oldOriginZ(int n) {
        return (n / PER_ROW) * CELL;
    }

    /**
     * Moves a home from the old, crowded layout to its own spot a kilometre from any other: every
     * block (chests with their contents) and every creature or stand in its yard. Once per home.
     */
    private void migrate(int n) {
        if (data.moved.contains(n)) return;
        data.moved.add(n);
        ServerWorld hw = homeWorld();
        if (hw == null) return;
        int fx = oldOriginX(n), fz = oldOriginZ(n), tx = originX(n), tz = originZ(n);
        int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        WorldCare.quiet(true);
        try {
            BlockPos.Mutable src = new BlockPos.Mutable(), dst = new BlockPos.Mutable();
            for (int x = -2; x < YARD + 2; x++) {
                for (int z = -2; z < YARD + 2; z++) {
                    hw.getChunk((fx + x) >> 4, (fz + z) >> 4);
                    hw.getChunk((tx + x) >> 4, (tz + z) >> 4);
                    for (int y = FLOOR - 4; y <= FLOOR + 60; y++) {
                        src.set(fx + x, y, fz + z);
                        BlockState st = hw.getBlockState(src);
                        if (st.isAir()) continue;
                        dst.set(tx + x, y, tz + z);
                        hw.setBlockState(dst, st, flags);
                        BlockEntity be = hw.getBlockEntity(src);
                        if (be != null) {
                            NbtCompound nbt = be.createNbt(hw.getRegistryManager());
                            BlockEntity nb = hw.getBlockEntity(dst);
                            if (nb != null) {
                                nb.read(nbt, hw.getRegistryManager());
                                nb.markDirty();
                            }
                        }
                    }
                }
            }
            net.minecraft.util.math.Box old = new net.minecraft.util.math.Box(fx - 2, FLOOR - 4, fz - 2, fx + YARD + 2, FLOOR + 60, fz + YARD + 2);
            for (net.minecraft.entity.Entity e : hw.getOtherEntities(null, old, e -> !(e instanceof ServerPlayerEntity))) {
                e.refreshPositionAndAngles(e.getX() - fx + tx, e.getY(), e.getZ() - fz + tz, e.getYaw(), e.getPitch());
            }
        } finally {
            WorldCare.quiet(false);
        }
        save();
    }

    /** Where house block (x, y, z) lands in the instance. */
    private static BlockPos map(int[] h, int n, int x, int y, int z) {
        int ox = originX(n) + (YARD - (h[2] - h[0] + 3)) / 2, oz = originZ(n) + (YARD - (h[3] - h[1] + 3)) / 2;
        return new BlockPos(ox + x - (h[0] - 1), FLOOR + y - h[4], oz + z - (h[1] - 1));
    }

    private ServerWorld homeWorld() {
        return server.getWorld(WORLD);
    }

    /** Builds a fresh copy of house h in instance n: yard, fence, the house itself. */
    void build(int[] h, int n) {
        ServerWorld ow = server.getOverworld(), hw = homeWorld();
        if (hw == null) return;
        int x0 = originX(n), z0 = originZ(n);
        int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        WorldCare.quiet(true);
        try {
            for (int x = x0 - 2; x < x0 + YARD + 2; x++) {
                for (int z = z0 - 2; z < z0 + YARD + 2; z++) {
                    boolean edge = x < x0 || z < z0 || x >= x0 + YARD || z >= z0 + YARD;
                    boolean fence = x == x0 - 1 || z == z0 - 1 || x == x0 + YARD || z == z0 + YARD;
                    hw.setBlockState(new BlockPos(x, FLOOR - 3, z), Blocks.STONE.getDefaultState(), flags);
                    hw.setBlockState(new BlockPos(x, FLOOR - 2, z), Blocks.DIRT.getDefaultState(), flags);
                    hw.setBlockState(new BlockPos(x, FLOOR - 1, z), Blocks.DIRT.getDefaultState(), flags);
                    hw.setBlockState(new BlockPos(x, FLOOR, z), edge ? Blocks.COARSE_DIRT.getDefaultState() : Blocks.GRASS_BLOCK.getDefaultState(), flags);
                    if (fence) {
                        hw.setBlockState(new BlockPos(x, FLOOR + 1, z), Blocks.SPRUCE_FENCE.getDefaultState(), flags);
                        for (int y = FLOOR + 2; y <= FLOOR + 5; y++) hw.setBlockState(new BlockPos(x, y, z), Blocks.BARRIER.getDefaultState(), flags);
                    }
                    if (fence && (x - x0) % 12 == 0 && (z - z0) % 12 == 0) hw.setBlockState(new BlockPos(x, FLOOR + 2, z), Blocks.LANTERN.getDefaultState(), flags);
                }
            }
            // The house, block for block (chests come empty).
            for (int x = h[0] - 1; x <= h[2] + 1; x++) {
                for (int z = h[1] - 1; z <= h[3] + 1; z++) {
                    ow.getChunk(x >> 4, z >> 4);
                    for (int y = h[4]; y <= h[5] + 3; y++) {
                        BlockPos src = new BlockPos(x, y, z);
                        BlockState st = ow.getBlockState(src);
                        BlockPos dst = map(h, n, x, y, z);
                        hw.setBlockState(dst, st, flags);
                        BlockEntity be = ow.getBlockEntity(src);
                        if (be != null) {
                            NbtCompound nbt = be.createNbt(ow.getRegistryManager());
                            nbt.remove("Items");
                            nbt.remove("LootTable");
                            BlockEntity nb = hw.getBlockEntity(dst);
                            if (nb != null) nb.read(nbt, hw.getRegistryManager());
                        }
                    }
                }
            }
            HouseFix.stairs(hw, map(h, n, h[0] - 1, h[4], h[1] - 1), map(h, n, h[2] + 1, h[5] + 3, h[3] + 1));
            HouseFix.headroom(hw, map(h, n, h[0] - 1, h[4], h[1] - 1), map(h, n, h[2] + 1, h[5] + 3, h[3] + 1));
            // A gravel path from the front door to the yard gate.
            BlockPos step = map(h, n, h[6], h[4], h[7]);
            for (int z = step.getZ(); z < z0 + YARD; z++) {
                if (hw.getBlockState(new BlockPos(step.getX(), FLOOR + 1, z)).isAir()) hw.setBlockState(new BlockPos(step.getX(), FLOOR, z), Blocks.DIRT_PATH.getDefaultState(), flags);
            }
        } finally {
            WorldCare.quiet(false);
        }
    }

    // ------------------------------------------------------------------ doors and travel

    /** A door in town. Returns true when handled (entering a home or showing a deed). */
    public boolean useDoor(ServerPlayerEntity p, BlockPos pos, boolean sneaking) {
        int home = homeAt(pos);
        if (home < 0) return false;
        Deed own = deed(p, home);
        // With the mod: the door's own screen (go in, knock on a resident's door, or see the deed).
        if (ServerPlayNetworking.canSend(p, Net.DoorView.ID)) {
            sendDoor(p, home);
            return true;
        }
        if (own != null && !sneaking) {
            enter(p, own);
            return true;
        }
        if (sneaking) {
            send(p, home, true);
            return true;
        }
        int[] h = AotRpg.PLACES.homes.get(home);
        if (ring(p, home)) return true;
        p.sendMessage(Text.literal("For sale: ").formatted(Formatting.GRAY).append(Wallet.marks(price(h)))
            .append(Text.literal("  ·  Sneak + use the door to see the deed").formatted(Formatting.DARK_GRAY)), true);
        return false;
    }

    /** The door screen: this house, who lives here (and who's in), and what you can do. */
    public void sendDoor(ServerPlayerEntity p, int home) {
        int[] h = AotRpg.PLACES.homes.get(home);
        List<Net.DoorTenant> ts = new ArrayList<>();
        for (var e : data.owners.entrySet()) {
            for (Deed d : e.getValue()) {
                if (d.home != home) continue;
                ServerPlayerEntity o = null;
                for (ServerPlayerEntity x : server.getPlayerManager().getPlayerList()) if (stem(x).equals(e.getKey())) o = x;
                boolean you = e.getKey().equals(stem(p));
                ts.add(new Net.DoorTenant(o == null ? new UUID(0, 0) : o.getUuid(), d.ownerName, o != null, o != null && !you && AotRpg.PARTIES.same(p.getUuid(), o.getUuid()), you));
            }
        }
        ts.sort((a, b) -> Boolean.compare(b.you(), a.you()) != 0 ? Boolean.compare(b.you(), a.you()) : Boolean.compare(b.online(), a.online()));
        String size = (h[2] - h[0] + 1) + " x " + (h[3] - h[1] + 1) + " · " + Math.max(1, (h[5] - h[4]) / 6) + (h[5] - h[4] >= 12 ? " floors" : " floor");
        ServerPlayNetworking.send(p, new Net.DoorView(home, townOf(h), size, price(h), deed(p, home) != null, deeds(p).size(), MAX_HOMES, ts));
    }

    /** Knocking on one resident's door from the door screen. Party members just walk in. */
    private void knock(ServerPlayerEntity p, int home, String who) {
        ServerPlayerEntity o;
        try {
            o = server.getPlayerManager().getPlayer(UUID.fromString(who));
        } catch (Exception e) {
            return;
        }
        if (o == null || o == p || deed(o, home) == null) {
            Notify.toast(p, Text.literal("Nobody's home").formatted(Formatting.GRAY), Text.literal("They aren't online right now"), 0x8F8A7A, null, "door");
            return;
        }
        if (AotRpg.PARTIES.same(p.getUuid(), o.getUuid())) {
            enter(p, deed(o, home));
            return;
        }
        ring(p, home, o);
    }

    // ------------------------------------------------------------------ the doorbell

    private record Ring(int home, long at) { }

    private final Map<UUID, Ring> rings = new HashMap<>();

    /** Ringing the bell of a house someone lives in (and is online): they get a "let them in". */
    private boolean ring(ServerPlayerEntity p, int home) {
        return ring(p, home, null);
    }

    /** Rings one resident (or every resident who's online, when only is null). */
    private boolean ring(ServerPlayerEntity p, int home, ServerPlayerEntity only) {
        List<ServerPlayerEntity> owners = new ArrayList<>();
        for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
            if (o != p && deed(o, home) != null && (only == null || o == only)) owners.add(o);
        }
        if (owners.isEmpty()) return false;
        long now = System.currentTimeMillis();
        Ring last = rings.get(p.getUuid());
        if (last != null && last.home() == home && now - last.at() < 5000) return true;
        rings.put(p.getUuid(), new Ring(home, now));
        String who = AotRpg.PROFILES.get(p.getUuid()).name;
        p.playSoundToPlayer(SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, 0.8f, 1.4f);
        Notify.toast(p, Text.literal("You knock").formatted(Formatting.GOLD),
            Text.literal("Waiting for " + (only == null ? "someone" : AotRpg.PROFILES.get(only.getUuid()).name) + " to answer..."), 0xE0B96A, "minecraft:bell", "door");
        for (ServerPlayerEntity o : owners) {
            o.playSoundToPlayer(SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, 1f, 1.4f);
            Notify.toast(o, Text.literal(who + " is at your door").formatted(Formatting.GOLD), Text.literal("Click [Let them in] in chat, or /home letin"),
                0xE0B96A, "minecraft:bell", "door");
            o.sendMessage(Text.literal("Ding-dong! " + who + " is at your door in " + townOf(AotRpg.PLACES.homes.get(home)) + ".  ").formatted(Formatting.GOLD)
                .append(Text.literal("[Let them in]").formatted(Formatting.GREEN, Formatting.BOLD)
                    .styled(st -> st.withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.RUN_COMMAND, "/home letin " + p.getName().getString()))
                        .withHoverEvent(new net.minecraft.text.HoverEvent(net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal("Open the door for " + who))))), false);
        }
        return true;
    }

    /** The owner answers the door: the guest who rang (in the last minute and a half) comes in. */
    public void letIn(ServerPlayerEntity owner, ServerPlayerEntity guest) {
        Ring r = rings.get(guest.getUuid());
        if (r == null || System.currentTimeMillis() - r.at() > 90_000) {
            owner.sendMessage(Text.literal("Nobody is waiting at your door.").formatted(Formatting.GRAY), true);
            return;
        }
        Deed d = deed(owner, r.home());
        if (d == null) return;
        rings.remove(guest.getUuid());
        enter(guest, d);
        guest.sendMessage(Text.literal(AotRpg.PROFILES.get(owner.getUuid()).name + " let you in. Any door takes you back out.").formatted(Formatting.GOLD), true);
        owner.sendMessage(Text.literal("You let " + AotRpg.PROFILES.get(guest.getUuid()).name + " in.").formatted(Formatting.GOLD), true);
    }

    /** Leaving a home from anywhere inside it (/home leave). */
    public boolean leaveHome(ServerPlayerEntity p) {
        if (p.getWorld().getRegistryKey() != WORLD) return false;
        int n = instanceAt(p.getBlockPos());
        Deed d = n < 0 ? null : find(data.instances.get(n), n);
        if (d == null) return false;
        leave(p, AotRpg.PLACES.homes.get(d.home));
        return true;
    }

    // ------------------------------------------------------------------ walled-in town homes

    /**
     * A town home without a yard: the grass yard and fence are replaced by a copy of the real street
     * around the house (so the windows look out on town), and an invisible wall stands on the
     * doorstep and just outside the walls, so you stay inside. Doors take you out.
     */
    void enclose(int[] h, Deed d) {
        ServerWorld ow = server.getOverworld(), hw = homeWorld();
        if (hw == null || d.yard) return;
        int n = d.instance;
        int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        WorldCare.quiet(true);
        try {
            // Clear the yard (everything in the cell outside the house box).
            int x0 = originX(n), z0 = originZ(n);
            BlockPos ha = map(h, n, h[0] - 1, h[4], h[1] - 1), hb = map(h, n, h[2] + 1, h[5] + 3, h[3] + 1);
            BlockPos.Mutable m = new BlockPos.Mutable();
            for (int x = x0 - 2; x < x0 + YARD + 2; x++) {
                for (int z = z0 - 2; z < z0 + YARD + 2; z++) {
                    if (x >= ha.getX() && x <= hb.getX() && z >= ha.getZ() && z <= hb.getZ()) continue;
                    for (int y = FLOOR - 4; y <= FLOOR + 8; y++) {
                        m.set(x, y, z);
                        if (!hw.getBlockState(m).isAir()) hw.setBlockState(m, Blocks.AIR.getDefaultState(), flags);
                    }
                }
            }
            // The street around the house, as it stands in town.
            for (int x = h[0] - 1 - BACKDROP; x <= h[2] + 1 + BACKDROP; x++) {
                for (int z = h[1] - 1 - BACKDROP; z <= h[3] + 1 + BACKDROP; z++) {
                    if (x >= h[0] - 1 && x <= h[2] + 1 && z >= h[1] - 1 && z <= h[3] + 1) continue;
                    ow.getChunk(x >> 4, z >> 4);
                    for (int y = h[4] - 4; y <= h[5] + 10; y++) {
                        BlockState st = ow.getBlockState(new BlockPos(x, y, z));
                        if (st.isAir()) continue;
                        hw.setBlockState(map(h, n, x, y, z), st, flags);
                    }
                }
            }
            // The invisible wall: on the doorstep ring and over the roof.
            for (int x = ha.getX(); x <= hb.getX(); x++) {
                for (int z = ha.getZ(); z <= hb.getZ(); z++) {
                    boolean ring = x == ha.getX() || x == hb.getX() || z == ha.getZ() || z == hb.getZ();
                    for (int y = ha.getY(); y <= hb.getY() + 1; y++) {
                        m.set(x, y, z);
                        if ((ring || y == hb.getY() + 1) && hw.getBlockState(m).isAir()) hw.setBlockState(m, Blocks.BARRIER.getDefaultState(), flags);
                    }
                }
            }
        } finally {
            WorldCare.quiet(false);
        }
        d.enclosed = true;
        d.backdrop2 = true;
        save();
        streetWall(h, d);
    }

    /**
     * Closes the copied street in: a ring of tall town houses just beyond it, half-timbered fronts
     * of varied heights facing in, with windows (a few lit), slate roofs and chimneys, standing on
     * solid ground. From any window the street simply carries on to the next row of houses.
     */
    void streetWall(int[] h, Deed d) {
        ServerWorld hw = homeWorld();
        if (hw == null) return;
        int n = d.instance, flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        int ix0 = h[0] - 1 - BACKDROP, ix1 = h[2] + 1 + BACKDROP, iz0 = h[1] - 1 - BACKDROP, iz1 = h[3] + 1 + BACKDROP;
        BlockState[] infill = {Blocks.WHITE_TERRACOTTA.getDefaultState(), Blocks.CALCITE.getDefaultState(),
            Blocks.SMOOTH_SANDSTONE.getDefaultState(), Blocks.MUD_BRICKS.getDefaultState(), Blocks.STONE_BRICKS.getDefaultState()};
        BlockState frame = Blocks.STRIPPED_DARK_OAK_LOG.getDefaultState(), beam = Blocks.DARK_OAK_PLANKS.getDefaultState();
        BlockState glass = Blocks.BLACK_STAINED_GLASS.getDefaultState(), lit = Blocks.YELLOW_STAINED_GLASS.getDefaultState();
        BlockState roof = Blocks.DEEPSLATE_TILES.getDefaultState(), brick = Blocks.BRICKS.getDefaultState();
        WorldCare.quiet(true);
        try {
            for (int x = ix0 - 4; x <= ix1 + 4; x++) {
                for (int z = iz0 - 4; z <= iz1 + 4; z++) {
                    if (x >= ix0 && x <= ix1 && z >= iz0 && z <= iz1) continue;
                    // How far out (0 = the front facing the street) and where along the row.
                    int out = Math.max(Math.max(ix0 - x, x - ix1), Math.max(iz0 - z, z - iz1)) - 1;
                    boolean alongX = z < iz0 || z > iz1;
                    int along = alongX ? x : z;
                    int house = Math.floorDiv(along, 7);
                    int seed = house * 31 + (alongX ? (z < iz0 ? 1 : 2) : (x < ix0 ? 3 : 4)) * 977;
                    int height = 10 + Math.floorMod(seed * 7, 9);
                    BlockState fill = infill[Math.floorMod(seed, infill.length)];
                    int col = Math.floorMod(along, 7);
                    for (int y = h[4] - 4; y <= h[4] + height + 3; y++) {
                        int up = y - h[4];
                        BlockState st;
                        if (up < 0) st = Blocks.STONE.getDefaultState();
                        else if (up > height) {
                            // A slate roof stepping back from the street, a chimney now and then.
                            int step = up - height;
                            if (step <= out) st = roof;
                            else if (out == 2 && col == 3 && Math.floorMod(seed, 3) == 0 && step <= 3) st = brick;
                            else continue;
                        } else if (out > 0) st = fill;
                        else if (col == 0 || up == 0 || up % 5 == 0) st = up % 5 == 0 && col != 0 ? beam : frame;
                        else if ((col == 2 || col == 4) && (up % 5 == 2 || up % 5 == 3) && up > 1) {
                            st = Math.floorMod(seed + up * 13 + col, 7) == 0 ? lit : glass;
                        } else if (up <= 3 && col == 3 && Math.floorMod(seed, 2) == 0) st = up <= 2 ? Blocks.SPRUCE_PLANKS.getDefaultState() : beam;
                        else st = fill;
                        hw.setBlockState(map(h, n, x, y, z), st, flags);
                    }
                }
            }
            // No holes to the void under the street.
            for (int x = ix0; x <= ix1; x++) {
                for (int z = iz0; z <= iz1; z++) {
                    BlockPos under = map(h, n, x, h[4] - 5, z);
                    if (hw.getBlockState(under).isAir()) hw.setBlockState(under, Blocks.STONE.getDefaultState(), flags);
                }
            }
        } finally {
            WorldCare.quiet(false);
        }
        d.streetWall = true;
        save();
    }

    /** Homes walled in before the street copy was widened: copy the ring from 14 out to 28 blocks. */
    void widenBackdrop(int[] h, Deed d) {
        ServerWorld ow = server.getOverworld(), hw = homeWorld();
        if (hw == null) return;
        int n = d.instance, flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        WorldCare.quiet(true);
        try {
            for (int x = h[0] - 1 - BACKDROP; x <= h[2] + 1 + BACKDROP; x++) {
                for (int z = h[1] - 1 - BACKDROP; z <= h[3] + 1 + BACKDROP; z++) {
                    if (x >= h[0] - 1 - OLD_BACKDROP && x <= h[2] + 1 + OLD_BACKDROP && z >= h[1] - 1 - OLD_BACKDROP && z <= h[3] + 1 + OLD_BACKDROP) continue;
                    ow.getChunk(x >> 4, z >> 4);
                    for (int y = h[4] - 4; y <= h[5] + 10; y++) {
                        BlockState st = ow.getBlockState(new BlockPos(x, y, z));
                        if (st.isAir()) continue;
                        hw.setBlockState(map(h, n, x, y, z), st, flags);
                    }
                }
            }
        } finally {
            WorldCare.quiet(false);
        }
        d.backdrop2 = true;
        save();
    }

    /** Buying a yard upgrade for a walled-in home: take the wall and street away, lay the yard back. */
    void unenclose(int[] h, Deed d) {
        ServerWorld hw = homeWorld();
        if (hw == null) return;
        int n = d.instance, x0 = originX(n), z0 = originZ(n);
        int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        BlockPos ha = map(h, n, h[0] - 1, h[4], h[1] - 1), hb = map(h, n, h[2] + 1, h[5] + 3, h[3] + 1);
        WorldCare.quiet(true);
        try {
            BlockPos.Mutable m = new BlockPos.Mutable();
            for (int x = x0 - 2 - BACKDROP; x < x0 + YARD + 2 + BACKDROP; x++) {
                for (int z = z0 - 2 - BACKDROP; z < z0 + YARD + 2 + BACKDROP; z++) {
                    boolean house = x >= ha.getX() && x <= hb.getX() && z >= ha.getZ() && z <= hb.getZ();
                    for (int y = FLOOR - 4; y <= FLOOR + 60; y++) {
                        m.set(x, y, z);
                        BlockState st = hw.getBlockState(m);
                        if (house) {
                            if (st.isOf(Blocks.BARRIER)) hw.setBlockState(m, Blocks.AIR.getDefaultState(), flags);
                        } else if (!st.isAir()) {
                            hw.setBlockState(m, Blocks.AIR.getDefaultState(), flags);
                        }
                    }
                    if (house) continue;
                    boolean inCell = x >= x0 - 2 && x < x0 + YARD + 2 && z >= z0 - 2 && z < z0 + YARD + 2;
                    if (!inCell) continue;
                    boolean edge = x < x0 || z < z0 || x >= x0 + YARD || z >= z0 + YARD;
                    boolean fence = x == x0 - 1 || z == z0 - 1 || x == x0 + YARD || z == z0 + YARD;
                    hw.setBlockState(new BlockPos(x, FLOOR - 3, z), Blocks.STONE.getDefaultState(), flags);
                    hw.setBlockState(new BlockPos(x, FLOOR - 2, z), Blocks.DIRT.getDefaultState(), flags);
                    hw.setBlockState(new BlockPos(x, FLOOR - 1, z), Blocks.DIRT.getDefaultState(), flags);
                    hw.setBlockState(new BlockPos(x, FLOOR, z), edge ? Blocks.COARSE_DIRT.getDefaultState() : Blocks.GRASS_BLOCK.getDefaultState(), flags);
                    if (fence) {
                        hw.setBlockState(new BlockPos(x, FLOOR + 1, z), Blocks.SPRUCE_FENCE.getDefaultState(), flags);
                        for (int y = FLOOR + 2; y <= FLOOR + 5; y++) hw.setBlockState(new BlockPos(x, y, z), Blocks.BARRIER.getDefaultState(), flags);
                    }
                }
            }
        } finally {
            WorldCare.quiet(false);
        }
        d.enclosed = false;
        save();
    }

    /** A door inside a home: the front door leads back to town. */
    public boolean useHomeDoor(ServerPlayerEntity p, BlockPos pos) {
        int n = instanceAt(pos);
        if (n < 0) return false;
        String owner = data.instances.get(n);
        Deed d = find(owner, n);
        if (d == null) return false;
        int[] h = AotRpg.PLACES.homes.get(d.home);
        // Any door of the house itself leads back out to town (for the owner and guests alike).
        BlockPos a = map(h, n, h[0] - 1, h[4], h[1] - 1), b = map(h, n, h[2] + 1, h[5] + 3, h[3] + 1);
        if (pos.getX() < a.getX() || pos.getX() > b.getX() || pos.getZ() < a.getZ() || pos.getZ() > b.getZ()) return false;
        leave(p, h);
        return true;
    }

    private Deed find(String owner, int n) {
        if (owner == null) return null;
        for (Deed d : data.owners.getOrDefault(owner, List.of())) if (d.instance == n) return d;
        return null;
    }

    /** The house deed of the home you are standing in, if it is yours. */
    public Deed deedHere(ServerPlayerEntity p) {
        if (p.getWorld().getRegistryKey() != WORLD) return null;
        int n = instanceAt(p.getBlockPos());
        String owner = n < 0 ? null : data.instances.get(n);
        return owner != null && owner.equals(stem(p)) ? find(owner, n) : null;
    }

    public Deed deedByInstance(int n) {
        return find(data.instances.get(n), n);
    }

    private int instanceAt(BlockPos pos) {
        int ix = Math.floorDiv(pos.getX() - BASE_X2, CELL2), iz = Math.floorDiv(pos.getZ(), CELL2);
        if (ix < 0 || ix >= PER_ROW || iz < 0) return -1;
        return iz * PER_ROW + ix;
    }

    /** Which home stood here in the old layout (to bring anyone left there over to the new one). */
    private int oldInstanceAt(BlockPos pos) {
        int ix = Math.floorDiv(pos.getX() - BASE_X, CELL), iz = Math.floorDiv(pos.getZ(), CELL);
        if (pos.getX() >= BASE_X2 - CELL2 || ix < 0 || ix >= PER_ROW || iz < 0) return -1;
        return iz * PER_ROW + ix;
    }

    public void enter(ServerPlayerEntity p, Deed d) {
        ServerWorld hw = homeWorld();
        if (hw == null) {
            p.sendMessage(Text.literal("The home world is not loaded on this server.").formatted(Formatting.RED), true);
            return;
        }
        int[] h = AotRpg.PLACES.homes.get(d.home);
        migrate(d.instance);
        if (!d.enclosed && !d.yard) enclose(h, d);
        else if (d.enclosed && !d.backdrop2) widenBackdrop(h, d);
        if (d.enclosed && !d.yard && !d.streetWall) streetWall(h, d);
        if (p.getWorld().getRegistryKey() != WORLD) {
            // Come back out on the doorstep, outside (never inside the town copy, or you'd walk straight back in).
            int ccx = (h[0] + h[2]) / 2, ccz = (h[1] + h[3]) / 2;
            int ox = Integer.signum(h[6] - ccx), oz = Integer.signum(h[7] - ccz);
            if (Math.abs(ccx - h[6]) < Math.abs(ccz - h[7])) ox = 0;
            else oz = 0;
            returnTo.put(p.getUuid(), new double[] {h[6] + 0.5 + ox * 1.5, h[4] + 1, h[7] + 0.5 + oz * 1.5, p.getYaw() + 180});
        }
        if (!d.headroomFixed || !d.headroom3) {
            d.headroomFixed = true;
            d.headroom3 = true;
            WorldCare.quiet(true);
            try {
                HouseFix.headroom(hw, map(h, d.instance, h[0] - 1, h[4], h[1] - 1), map(h, d.instance, h[2] + 1, h[5] + 3, h[3] + 1));
            } finally {
                WorldCare.quiet(false);
            }
            save();
        }
        if (!d.stairsFixed) {
            d.stairsFixed = true;
            WorldCare.quiet(true);
            try {
                HouseFix.stairs(hw, map(h, d.instance, h[0] - 1, h[4], h[1] - 1), map(h, d.instance, h[2] + 1, h[5] + 3, h[3] + 1));
            } finally {
                WorldCare.quiet(false);
            }
            save();
        }
        BlockPos inside = map(h, d.instance, h[6], h[4] + 1, h[7]);
        // Step in through the door: one block towards the house from the doorstep.
        int cx = (h[0] + h[2]) / 2, cz = (h[1] + h[3]) / 2;
        int sx = Integer.signum(cx - h[6]), sz = Integer.signum(cz - h[7]);
        if (Math.abs(cx - h[6]) < Math.abs(cz - h[7])) sx = 0;
        else sz = 0;
        ensureBorder(hw, null);
        BlockPos want = inside.add(sx * 2, 0, sz * 2);
        hw.getChunk(want.getX() >> 4, want.getZ() >> 4);
        // A copy that was never built (or got lost) is rebuilt before anyone steps into thin air.
        if (hw.getBlockState(new BlockPos(want.getX(), FLOOR - 1, want.getZ())).isAir()
            && hw.getBlockState(new BlockPos(want.getX(), FLOOR - 3, want.getZ())).isAir()) {
            build(h, d.instance);
            if (d.enclosed) {
                d.enclosed = false;
                enclose(h, d);
            }
            if (d.upgrades.contains(Upgrade.CELLAR.name())) digCellar(h, d, null);
        }
        // Somewhere you actually fit: open at the feet and head, a floor underneath.
        BlockPos at = standable(hw, want) ? want : null;
        for (int r = 1; at == null && r <= 4; r++) {
            for (int dx = -r; dx <= r && at == null; dx++) {
                for (int dz = -r; dz <= r && at == null; dz++) {
                    for (int dy = -1; dy <= 2 && at == null; dy++) {
                        BlockPos c = want.add(dx, dy, dz);
                        if (standable(hw, c)) at = c;
                    }
                }
            }
        }
        if (at == null) at = inside;
        p.fallDistance = 0;
        p.teleport(hw, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, p.getYaw(), 0);
        // A moment's grace while the house loads in around you.
        p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.RESISTANCE, 60, 4, false, false, false));
        p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.SLOW_FALLING, 40, 0, false, false, false));
        ensureBorder(hw, p);
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, SoundCategory.BLOCKS, 0.7f, 1f);
        p.sendMessage(Text.literal("Home").formatted(Formatting.GOLD, Formatting.BOLD)
            .append(Text.literal("  ·  " + townOf(h) + "  ·  any door takes you back out").formatted(Formatting.GRAY)), true);
    }

    /**
     * The home world shares the main world's border by default, and homes are laid out far from its
     * centre: with a map-sized border, everyone at home would be outside it and take damage. The home
     * world gets the widest border there is (and players in it are told).
     */
    private static void ensureBorder(ServerWorld hw, ServerPlayerEntity p) {
        net.minecraft.world.border.WorldBorder b = hw.getWorldBorder();
        boolean changed = false;
        if (b.getSize() < 5.9E7 || b.getCenterX() != 0 || b.getCenterZ() != 0) {
            b.setCenter(0, 0);
            b.setSize(5.9999968E7);
            changed = true;
        }
        if (p != null || changed) {
            var pkt = new net.minecraft.network.packet.s2c.play.WorldBorderInitializeS2CPacket(b);
            if (p != null) p.networkHandler.sendPacket(pkt);
            else for (ServerPlayerEntity o : new java.util.ArrayList<>(hw.getPlayers())) o.networkHandler.sendPacket(pkt);
        }
    }

    private static boolean standable(ServerWorld w, BlockPos p) {
        return w.getBlockState(p).getCollisionShape(w, p).isEmpty() && w.getBlockState(p.up()).getCollisionShape(w, p.up()).isEmpty()
            && !w.getBlockState(p.down()).getCollisionShape(w, p.down()).isEmpty() && w.getFluidState(p).isEmpty();
    }

    /** When each player last came out of their home (so stepping out doesn't pull them straight back in). */
    private final Map<UUID, Long> leftAt = new HashMap<>();

    /**
     * Twice a second: an owner stepping into their house in town (through the door or any way in)
     * goes into their own private copy of it. Every few seconds, town houses near players have their
     * staircases repaired once.
     */
    public void tick(ServerPlayerEntity p, int ticks) {
        // The home world is empty apart from the homes: anyone falling below a home's floor is
        // caught before the void and set back inside it (the house is rebuilt if it's missing).
        if (p.getWorld().getRegistryKey() == WORLD && ticks % 40 == 0) ensureBorder(p.getServerWorld(), null);
        if (p.getWorld().getRegistryKey() == WORLD && ticks % 20 == 7) {
            int old = oldInstanceAt(p.getBlockPos());
            Deed od = old < 0 ? null : find(data.instances.get(old), old);
            if (od != null) {
                enter(p, od);
                return;
            }
        }
        if (p.getWorld().getRegistryKey() == WORLD && p.getY() < FLOOR - 12) {
            int n = instanceAt(p.getBlockPos());
            Deed d = n < 0 ? null : find(data.instances.get(n), n);
            if (d != null) {
                // enter() rebuilds the house only if it's actually missing (never over a lived-in one).
                enter(p, d);
            } else {
                p.fallDistance = 0;
                ServerWorld ow = server.getOverworld();
                BlockPos sp = ow.getSpawnPos();
                p.teleport(ow, sp.getX() + 0.5, ow.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, sp.getX(), sp.getZ()), sp.getZ() + 0.5, p.getYaw(), 0);
            }
            return;
        }
        if (ticks % 10 != 0 || p.getWorld().getRegistryKey() != World.OVERWORLD || !AotRpg.PROFILES.get(p.getUuid()).created) return;
        BlockPos pos = p.getBlockPos();
        int home = homeAt(pos);
        // Walking into a town house no longer takes you anywhere: only using its door does.
        if (ticks % 100 == 0) fixNear(p);
    }

    private void fixNear(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        int pcx = p.getBlockX() >> 4, pcz = p.getBlockZ() >> 4;
        boolean changed = false;
        WorldCare.quiet(true);
        try {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    List<Integer> list = byChunk.get(((long) (pcx + dx) << 32) ^ ((pcz + dz) & 0xFFFFFFFFL));
                    if (list == null) continue;
                    for (int i : list) {
                        if (data.fixedWorld.contains(i) && data.fixedWorld3.contains(i)) continue;
                        int[] h = AotRpg.PLACES.homes.get(i);
                        if (!w.isChunkLoaded(h[0] >> 4, h[1] >> 4) || !w.isChunkLoaded(h[2] >> 4, h[3] >> 4)) continue;
                        BlockPos a = new BlockPos(h[0] - 1, h[4], h[1] - 1), b = new BlockPos(h[2] + 1, h[5] + 3, h[3] + 1);
                        HouseFix.stairs(w, a, b);
                        HouseFix.headroom(w, a, b);
                        data.fixedWorld.add(i);
                        data.fixedWorld3.add(i);
                        changed = true;
                    }
                }
            }
        } finally {
            WorldCare.quiet(false);
        }
        if (changed) save();
    }

    private void leave(ServerPlayerEntity p, int[] h) {
        leftAt.put(p.getUuid(), server.getOverworld().getTime());
        ServerWorld ow = server.getOverworld();
        double[] r = returnTo.remove(p.getUuid());
        if (r != null) p.teleport(ow, r[0], r[1], r[2], (float) r[3], 0);
        else p.teleport(ow, h[6] + 0.5, h[4] + 1, h[7] + 0.5, p.getYaw(), 0);
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_WOODEN_DOOR_OPEN, SoundCategory.BLOCKS, 0.7f, 1f);
    }

    /** /home: your (first) home, or back out. */
    public void command(ServerPlayerEntity p) {
        if (p.getWorld().getRegistryKey() == WORLD) {
            int n = instanceAt(p.getBlockPos());
            Deed d = find(data.instances.get(n), n);
            if (d != null) leave(p, AotRpg.PLACES.homes.get(d.home));
            else {
                BlockPos land = Safe.landing(server.getOverworld(), (int) p.getX(), 100, (int) p.getZ());
                p.teleport(server.getOverworld(), land.getX() + 0.5, land.getY(), land.getZ() + 0.5, 0, 0);
            }
            return;
        }
        List<Deed> list = deeds(p);
        if (list.isEmpty()) {
            p.sendMessage(Text.literal("You don't own a home yet. Sneak + use the door of any town house.").formatted(Formatting.GRAY), true);
            return;
        }
        if (p.getRecentDamageSource() != null) {
            p.sendMessage(Text.literal("You can't go home in the middle of a fight.").formatted(Formatting.RED), true);
            return;
        }
        enter(p, list.get(0));
    }

    /** Only the owner builds and breaks inside their own home and yard. */
    public boolean canBuild(ServerPlayerEntity p, BlockPos pos) {
        int n = instanceAt(pos);
        return n >= 0 && stem(p).equals(data.instances.get(n));
    }

    // ------------------------------------------------------------------ buying and upgrades

    public void action(ServerPlayerEntity p, String action, int home, String arg) {
        if (action.startsWith("admin_")) {
            HomeAdmin.action(p, action, home, arg);
            return;
        }
        if (!AotRpg.PROFILES.get(p.getUuid()).created) return;
        if (action.equals("list")) {
            sendList(p);
            return;
        }
        if (action.equals("view")) {
            if (home >= 0 && deed(p, home) != null) send(p, home, true);
            else if (home < 0 && data.plots.get(-home - 1) != null && stem(p).equals(data.plots.get(-home - 1).stem)) sendPlot(p, -home - 1, true);
            return;
        }
        if (home < 0 && !action.equals("manage") && !action.equals("offers") && !action.equals("stables")) {
            plotAction(p, action, -home - 1, arg);
            return;
        }
        switch (action) {
            case "offers" -> {
                Offer o = HomeAdmin.firstOffer(p);
                if (o == null) p.sendMessage(Text.literal("No offers for you right now.").formatted(Formatting.GRAY), true);
                else if (o.index >= 0) send(p, o.index, true);
                else sendPlot(p, -o.index - 1, true);
            }
            case "acceptoffer" -> {
                Offer o = HomeAdmin.offerFor(p, home);
                if (o == null) return;
                if (deeds(p).size() >= MAX_HOMES) {
                    p.sendMessage(Text.literal("A character can own " + MAX_HOMES + " homes.").formatted(Formatting.RED), true);
                    return;
                }
                if (!AotRpg.WALLET.spendMarks(p, o.price)) {
                    p.sendMessage(Text.literal("You need " + o.price + " Marks.").formatted(Formatting.RED), true);
                    return;
                }
                data.offers.remove(o);
                handOver(p, grant(stem(p), AotRpg.PROFILES.get(p.getUuid()).name, home));
            }
            case "buy" -> buy(p, home);
            case "door" -> sendDoor(p, home);
            case "deed" -> send(p, home, true);
            case "knock" -> knock(p, home, arg);
            case "leave" -> leaveHome(p);
            case "enter" -> {
                Deed d = deed(p, home);
                if (d != null) enter(p, d);
            }
            case "visit" -> visit(p, home, arg);
            case "stables" -> AotRpg.HORSES.openHome(p);
            case "upgrade" -> upgrade(p, home, arg);
            case "sell" -> sell(p, home);
            case "manage" -> {
                int n = instanceAt(p.getBlockPos());
                Deed d = p.getWorld().getRegistryKey() == WORLD ? find(data.instances.get(n), n) : null;
                int plot = p.getWorld().getRegistryKey() == World.OVERWORLD ? HomePlots.plotAt(p.getBlockPos(), HomePlots.LAND) : -1;
                if (d != null) send(p, d.home, true);
                else if (plot >= 0 && HomePlots.ownsAt(p, p.getBlockPos())) sendPlot(p, plot, true);
                else if (!deeds(p).isEmpty()) send(p, deeds(p).get(0).home, true);
                else {
                    for (var e : data.plots.entrySet()) {
                        if (e.getValue().stem.equals(stem(p))) {
                            sendPlot(p, e.getKey(), true);
                            return;
                        }
                    }
                    // Operators without a home of their own get the property list instead.
                    if (p.hasPermissionLevel(2)) {
                        HomeAdmin.send(p, "");
                        return;
                    }
                    p.sendMessage(Text.literal("You don't own a home yet. Use the door of any town house, or the sign of a plot.").formatted(Formatting.GRAY), true);
                }
            }
            default -> { }
        }
    }

    private void buy(ServerPlayerEntity p, int home) {
        if (home < 0 || home >= AotRpg.PLACES.homes.size() || deed(p, home) != null) return;
        if (deeds(p).size() >= MAX_HOMES) {
            p.sendMessage(Text.literal("A character can own " + MAX_HOMES + " homes.").formatted(Formatting.RED), true);
            return;
        }
        if (homeWorld() == null) {
            p.sendMessage(Text.literal("The home world is not loaded on this server.").formatted(Formatting.RED), true);
            return;
        }
        int[] h = AotRpg.PLACES.homes.get(home);
        long price = price(h);
        if (!AotRpg.WALLET.spendMarks(p, price)) {
            p.sendMessage(Text.literal("You need " + price + " Marks.").formatted(Formatting.RED), true);
            return;
        }
        Deed d = grant(stem(p), AotRpg.PROFILES.get(p.getUuid()).name, home);
        handOver(p, d);
    }

    /** A home just bought: the key handed over on screen, then in or stay out (the player's choice). */
    private void handOver(ServerPlayerEntity p, Deed d) {
        int[] h = AotRpg.PLACES.homes.get(d.home);
        if (!ServerPlayNetworking.canSend(p, Net.HomeKey.ID)) {
            Titles.show(p, Text.literal("HOME BOUGHT").formatted(Formatting.GOLD, Formatting.BOLD), Text.literal(townOf(h)).formatted(Formatting.GRAY), 10, 50, 20);
            enter(p, d);
            return;
        }
        String size = (h[2] - h[0] + 1) + " x " + (h[3] - h[1] + 1) + " · " + Math.max(1, (h[5] - h[4]) / 6) + (h[5] - h[4] >= 12 ? " floors" : " floor");
        ServerPlayNetworking.send(p, new Net.HomeKey(d.home, townOf(h), size));
    }

    /** Gives a character (by stem) a deed to a house and builds its copy. */
    Deed grant(String stem, String name, int home) {
        Deed d = new Deed();
        d.stairsFixed = true;
        d.headroomFixed = true;
        d.headroom3 = true;
        d.home = home;
        d.instance = data.nextInstance++;
        d.ownerName = name;
        data.owners.computeIfAbsent(stem, k -> new ArrayList<>()).add(d);
        data.instances.put(d.instance, stem);
        data.moved.add(d.instance);
        save();
        build(AotRpg.PLACES.homes.get(home), d.instance);
        enclose(AotRpg.PLACES.homes.get(home), d);
        countStash(d);
        return d;
    }

    void revoke(String stem, int home) {
        List<Deed> list = data.owners.get(stem);
        if (list == null) return;
        list.removeIf(d -> {
            if (d.home != home) return false;
            data.instances.remove(d.instance);
            return true;
        });
        save();
    }

    /** Names of everyone holding a deed to this house. */
    List<String> ownersOf(int home) {
        List<String> out = new ArrayList<>();
        for (List<Deed> list : data.owners.values()) for (Deed d : list) if (d.home == home) out.add(d.ownerName);
        return out;
    }

    private void sell(ServerPlayerEntity p, int home) {
        Deed d = deed(p, home);
        if (d == null) return;
        if (p.getWorld().getRegistryKey() == WORLD && instanceAt(p.getBlockPos()) == d.instance) leave(p, AotRpg.PLACES.homes.get(home));
        long back = price(AotRpg.PLACES.homes.get(home)) / 2;
        for (String u : d.upgrades) {
            try {
                back += Upgrade.valueOf(u).price / 2;
            } catch (Exception ignored) { }
        }
        data.owners.get(stem(p)).remove(d);
        data.instances.remove(d.instance);
        save();
        AotRpg.WALLET.addMarks(p, back, "sold your home");
        p.sendMessage(Text.literal("Anything left inside is gone with the deed.").formatted(Formatting.GRAY), false);
    }

    private void visit(ServerPlayerEntity p, int home, String who) {
        ServerPlayerEntity host = server.getPlayerManager().getPlayer(who);
        if (host == null || !AotRpg.PARTIES.same(p.getUuid(), host.getUuid())) return;
        Deed d = deed(host, home);
        if (d != null) enter(p, d);
    }

    private void upgrade(ServerPlayerEntity p, int home, String id) {
        Deed d = deed(p, home);
        Upgrade u;
        try {
            u = Upgrade.valueOf(id);
        } catch (Exception e) {
            return;
        }
        if (d == null || d.upgrades.contains(u.name())) return;
        if (u.yard) {
            Notify.toast(p, Text.literal("Yards are for property plots").formatted(Formatting.RED),
                Text.literal("Town homes build inside and down: dig a Cellar"), 0xC0463A);
            return;
        }
        boolean cellar = d.upgrades.contains(Upgrade.CELLAR.name());
        if (u != Upgrade.CELLAR && !cellar) {
            Notify.toast(p, Text.literal("Dig a Cellar first").formatted(Formatting.RED),
                Text.literal(u.title + " is fitted out in a cellar bay"), 0xC0463A);
            return;
        }
        if (!AotRpg.WALLET.spendMarks(p, u.price)) {
            p.sendMessage(Text.literal("You need " + u.price + " Marks.").formatted(Formatting.RED), true);
            return;
        }
        int[] h = AotRpg.PLACES.homes.get(d.home);
        migrate(d.instance);
        if (u == Upgrade.CELLAR) {
            if (!digCellar(h, d, p)) {
                AotRpg.WALLET.addMarks(p, u.price, null);
                Notify.toast(p, Text.literal("No room for a hatch").formatted(Formatting.RED),
                    Text.literal("Clear a spot of floor in the middle of the ground floor"), 0xC0463A);
                return;
            }
            d.upgrades.add(u.name());
            countStash(d);
            Notify.toast(p, Text.literal("Cellar dug").formatted(Formatting.GOLD), Text.literal("The hatch is in the ground floor"), 0xE0B96A,
                "minecraft:ladder", "home_up");
        } else {
            d.upgrades.add(u.name());
            BlockPos a = map(h, d.instance, h[0] - 1, h[4], h[1] - 1), b = map(h, d.instance, h[2] + 1, h[5] + 3, h[3] + 1);
            HomeCellar.bay(homeWorld(), u, (a.getX() + b.getX()) / 2, (a.getZ() + b.getZ()) / 2, FLOOR);
            HomeCellar.hatch(homeWorld(), d.hatchX, d.hatchZ, FLOOR);
            countStash(d);
            Notify.toast(p, Text.literal(u.title + " fitted out").formatted(Formatting.GOLD), Text.literal("Down in your cellar"), 0xE0B96A,
                "minecraft:lantern", "home_up");
        }
        save();
        send(p, home, false);
    }

    /**
     * Digs the cellar under house h (and fits out any bays already bought). The hatch goes in a clear
     * spot of the ground floor near the middle, away from the door. False when there's no such spot.
     */
    boolean digCellar(int[] h, Deed d, ServerPlayerEntity p) {
        ServerWorld hw = homeWorld();
        if (hw == null) return false;
        int n = d.instance;
        BlockPos a = map(h, n, h[0], h[4], h[1]), b = map(h, n, h[2], h[4], h[3]);
        int cx = (a.getX() + b.getX()) / 2, cz = (a.getZ() + b.getZ()) / 2;
        BlockPos door = map(h, n, h[6], h[4], h[7]);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = a.getX() + 1; x <= b.getX() - 1; x++) {
            for (int z = a.getZ() + 1; z <= b.getZ() - 1; z++) {
                if (Math.abs(x - cx) > HomeCellar.HX - 3 || Math.abs(z - cz) > HomeCellar.HZ - 1) continue;
                if (Math.abs(x - door.getX()) + Math.abs(z - door.getZ()) < 4) continue;
                BlockPos f = new BlockPos(x, FLOOR, z);
                BlockState fs = hw.getBlockState(f);
                if (!fs.isFullCube(hw, f) || hw.getBlockEntity(f) != null) continue;
                if (!hw.getBlockState(f.up()).isAir() || !hw.getBlockState(f.up(2)).isAir()) continue;
                // Prefer the aisle (the ladder lands clear of the bays), then closeness to the middle.
                double dd = Math.abs(x - cx) + Math.abs(z - cz) * 1.5 + (Math.abs(z - cz) <= 1 ? 0 : 20);
                if (dd < bestD) {
                    bestD = dd;
                    best = f;
                }
            }
        }
        if (best == null) return false;
        d.hatchX = best.getX();
        d.hatchZ = best.getZ();
        HomeCellar.dig(hw, cx, cz, FLOOR, d.hatchX, d.hatchZ);
        for (String id : d.upgrades) {
            try {
                Upgrade u = Upgrade.valueOf(id);
                if (HomeCellar.bay(u) >= 0 && !(d.yard && (u == Upgrade.FORGE || u == Upgrade.STORAGE))) HomeCellar.bay(hw, u, cx, cz, FLOOR);
            } catch (Exception ignored) { }
        }
        HomeCellar.hatch(hw, d.hatchX, d.hatchZ, FLOOR);
        save();
        return true;
    }

    // ------------------------------------------------------------------ stash

    /**
     * Counts the chests and barrels in a home (the house and its cellar) into its stash: the ones
     * there when it was bought, and the ones each cellar upgrade brings. Chests placed later are
     * just furniture.
     */
    void countStash(Deed d) {
        ServerWorld hw = homeWorld();
        if (hw == null) return;
        int[] h = AotRpg.PLACES.homes.get(d.home);
        int n = d.instance;
        BlockPos a = map(h, n, h[0], h[4], h[1]), b = map(h, n, h[2], h[5] + 3, h[3]);
        int cx = (a.getX() + b.getX()) / 2, cz = (a.getZ() + b.getZ()) / 2;
        java.util.LinkedHashSet<Long> found = new java.util.LinkedHashSet<>();
        if (d.stash != null) found.addAll(d.stash);
        // The house, then the cellar under it.
        scanContainers(hw, a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ(), found);
        if (d.upgrades.contains(Upgrade.CELLAR.name())) {
            // Inside the cellar room's walls only (the street copy reaches below ground around it).
            int fy = HomeCellar.floor(FLOOR);
            scanContainers(hw, cx - HomeCellar.HX, fy + 1, cz - HomeCellar.HZ, cx + HomeCellar.HX, fy + 4, cz + HomeCellar.HZ, found);
        }
        d.stash = new ArrayList<>(found);
        save();
    }

    private static void scanContainers(ServerWorld w, int x0, int y0, int z0, int x1, int y1, int z1, java.util.Set<Long> out) {
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                var chunk = w.getChunk(cx, cz);
                for (BlockPos bp : chunk.getBlockEntityPositions()) {
                    if (bp.getX() < x0 || bp.getX() > x1 || bp.getY() < y0 || bp.getY() > y1 || bp.getZ() < z0 || bp.getZ() > z1) continue;
                    if (Stash.counts(w.getBlockEntity(bp))) out.add(bp.asLong());
                }
            }
        }
    }

    /** Every counted chest and barrel across this character's homes (counted now if never before). */
    public List<BlockPos> stashSpots(ServerPlayerEntity p) {
        List<BlockPos> out = new ArrayList<>();
        for (Deed d : deeds(p)) {
            migrate(d.instance);
            if (d.stash == null || !d.stashV2) {
                d.stash = null;
                d.stashV2 = true;
                countStash(d);
            }
            if (d.stash != null) for (long l : d.stash) out.add(BlockPos.fromLong(l));
        }
        return out;
    }

    public ServerWorld stashWorld() {
        return homeWorld();
    }

    static void border(ServerWorld hw, ServerPlayerEntity p) {
        ensureBorder(hw, p);
    }

    /** Standing in your own home with a stable, or on your own plot's land with one built. */
    public boolean atOwnStable(ServerPlayerEntity p) {
        if (p.getWorld().getRegistryKey() == WORLD) return hasUpgrade(p, p.getBlockPos(), Upgrade.STABLE);
        int idx = HomePlots.plotAt(p.getBlockPos(), HomePlots.LAND);
        PlotDeed d = idx < 0 ? null : data.plots.get(idx);
        return d != null && d.stable && d.stem.equals(stem(p));
    }

    public boolean hasUpgrade(ServerPlayerEntity p, BlockPos pos, Upgrade u) {
        int n = instanceAt(pos);
        Deed d = find(data.instances.get(n), n);
        return d != null && d.upgrades.contains(u.name()) && stem(p).equals(data.instances.get(n));
    }

    private void plotAction(ServerPlayerEntity p, String action, int idx, String arg) {
        if (idx >= AotRpg.PLACES.plots.size()) return;
        Places.PlotInfo plot = AotRpg.PLACES.plots.get(idx);
        switch (action) {
            case "buy" -> HomePlots.buy(p, idx, HomePlots.price(plot));
            case "acceptoffer" -> {
                Offer o = HomeAdmin.offerFor(p, -idx - 1);
                if (o == null) return;
                data.offers.remove(o);
                HomePlots.buy(p, idx, o.price);
            }
            case "stable" -> {
                PlotDeed d = data.plots.get(idx);
                if (d == null || !d.stem.equals(stem(p)) || d.stable) return;
                if (!AotRpg.WALLET.spendMarks(p, Upgrade.STABLE.price)) {
                    p.sendMessage(Text.literal("You need " + Upgrade.STABLE.price + " Marks.").formatted(Formatting.RED), true);
                    return;
                }
                if (!HomePlots.buildStable(server.getOverworld(), plot)) {
                    AotRpg.WALLET.addMarks(p, Upgrade.STABLE.price, null);
                    p.sendMessage(Text.literal("There's no clear corner on your land for a stable.").formatted(Formatting.RED), true);
                    return;
                }
                d.stable = true;
                save();
                Notify.toast(p, Text.literal("Stable built").formatted(Formatting.GOLD), Text.literal("Room for 4 more horses"), 0xE0B96A,
                    "minecraft:hay_block", null);
            }
            case "build" -> {
                // Yard buildings on your land: a fishing pond, a smithy.
                PlotDeed d = data.plots.get(idx);
                if (d == null || !d.stem.equals(stem(p))) return;
                boolean pond = arg.equals("POND");
                if (pond ? d.pond : d.smithy) return;
                int price = pond ? Upgrade.POND.price : 2400;
                if (!AotRpg.WALLET.spendMarks(p, price)) {
                    p.sendMessage(Text.literal("You need " + price + " Marks.").formatted(Formatting.RED), true);
                    return;
                }
                boolean ok = pond ? HomePlots.buildPond(server.getOverworld(), plot) : HomePlots.buildSmithy(server.getOverworld(), plot);
                if (!ok) {
                    AotRpg.WALLET.addMarks(p, price, null);
                    p.sendMessage(Text.literal("There's no clear corner on your land for that.").formatted(Formatting.RED), true);
                    return;
                }
                if (pond) d.pond = true;
                else d.smithy = true;
                save();
                Notify.toast(p, Text.literal(pond ? "Fishing pond dug" : "Smithy built").formatted(Formatting.GOLD),
                    Text.literal(pond ? "Cast a line at home" : "Use the anvil to open the forge"), 0xE0B96A,
                    pond ? "minecraft:fishing_rod" : "minecraft:anvil", null);
            }
            case "sell" -> {
                PlotDeed d = data.plots.get(idx);
                if (d == null || !d.stem.equals(stem(p))) return;
                HomePlots.revoke(idx);
                AotRpg.WALLET.addMarks(p, HomePlots.price(plot) / 2, "sold your property");
            }
            default -> { }
        }
        sendPlot(p, idx, false);
    }

    /** The deed of a property plot. */
    public void sendPlot(ServerPlayerEntity p, int idx, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.HomeView.ID) || idx < 0 || idx >= AotRpg.PLACES.plots.size()) return;
        Places.PlotInfo plot = AotRpg.PLACES.plots.get(idx);
        PlotDeed d = data.plots.get(idx);
        Offer o = HomeAdmin.offerFor(p, -idx - 1);
        String size = (plot.x1() - plot.x0() + 1) + " x " + (plot.z1() - plot.z0() + 1) + " · " + plot.size() + " " + plot.kind() + " plot";
        List<Net.HomeUpgrade> ups = List.of(new Net.HomeUpgrade("STABLE", "Stable", "A stall and run on your land: room for 4 more horses.",
                Upgrade.STABLE.price, d != null && d.stable),
            new Net.HomeUpgrade("POND", "Fishing Pond", "A reed-lined pond in a corner of your land to fish in.", Upgrade.POND.price, d != null && d.pond),
            new Net.HomeUpgrade("SMITHY", "Smithy", "An open forge with an anvil (opens the forge), furnace and grindstone.", 2400, d != null && d.smithy));
        ServerPlayNetworking.send(p, new Net.HomeView(-idx - 1, HomePlots.label(plot), size, HomePlots.price(plot),
            d != null && d.stem.equals(stem(p)), 0, 1, ups, List.of(), open, "plot", o == null ? -1 : o.price,
            d == null ? "" : d.ownerName));
    }

    public void send(ServerPlayerEntity p, int home, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.HomeView.ID) || home < 0 || home >= AotRpg.PLACES.homes.size()) return;
        int[] h = AotRpg.PLACES.homes.get(home);
        Deed d = deed(p, home);
        List<Net.HomeUpgrade> ups = new ArrayList<>();
        boolean cellar = d != null && d.upgrades.contains(Upgrade.CELLAR.name());
        for (Upgrade u : Upgrade.values()) {
            boolean owned = d != null && d.upgrades.contains(u.name());
            // Yards are for plots: a town home only lists the yard buildings it already has.
            if (u.yard && !owned) continue;
            String desc = !owned && u != Upgrade.CELLAR && !u.yard && !cellar ? "Needs the Cellar. " + u.desc : u.desc;
            ups.add(new Net.HomeUpgrade(u.name(), u.title, desc, u.price, owned));
        }
        List<String> visits = new ArrayList<>();
        for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
            if (o != p && AotRpg.PARTIES.same(p.getUuid(), o.getUuid()) && deed(o, home) != null) visits.add(o.getName().getString());
        }
        String size = (h[2] - h[0] + 1) + " x " + (h[3] - h[1] + 1) + " · " + Math.max(1, (h[5] - h[4]) / 6) + (h[5] - h[4] >= 12 ? " floors" : " floor");
        Offer o = HomeAdmin.offerFor(p, home);
        ServerPlayNetworking.send(p, new Net.HomeView(home, townOf(h), size, price(h), d != null, deeds(p).size(), MAX_HOMES, ups, visits, open,
            "home", o == null ? -1 : o.price, ""));
    }

    /**
     * Every home and property this character owns, as cards: "home|kind|title|size|built|total|here".
     * Plots use -(index + 1) for home.
     */
    public void sendList(ServerPlayerEntity p) {
        if (!ServerPlayNetworking.canSend(p, Net.HomeList.ID)) return;
        List<String> cards = new ArrayList<>();
        int inside = p.getWorld().getRegistryKey() == WORLD ? instanceAt(p.getBlockPos()) : -1;
        for (Deed d : deeds(p)) {
            if (d.home < 0 || d.home >= AotRpg.PLACES.homes.size()) continue;
            int[] h = AotRpg.PLACES.homes.get(d.home);
            String size = (h[2] - h[0] + 1) + " x " + (h[3] - h[1] + 1) + " · " + Math.max(1, (h[5] - h[4]) / 6) + (h[5] - h[4] >= 12 ? " floors" : " floor");
            int built = 0, total = 0;
            for (Upgrade u : Upgrade.values()) {
                if (u.yard && !d.upgrades.contains(u.name())) continue;
                total++;
                if (d.upgrades.contains(u.name())) built++;
            }
            cards.add(d.home + "|home|" + townOf(h) + "|" + size + "|" + built + "|" + total + "|" + (inside == d.instance ? 1 : 0));
        }
        String me = stem(p);
        for (var e : data.plots.entrySet()) {
            if (!me.equals(e.getValue().stem) || e.getKey() >= AotRpg.PLACES.plots.size()) continue;
            Places.PlotInfo pl = AotRpg.PLACES.plots.get(e.getKey());
            PlotDeed d = e.getValue();
            int built = (d.stable ? 1 : 0) + (d.pond ? 1 : 0) + (d.smithy ? 1 : 0);
            boolean here = p.getWorld().getRegistryKey() == World.OVERWORLD && HomePlots.plotAt(p.getBlockPos(), HomePlots.LAND) == e.getKey();
            String size = (pl.x1() - pl.x0() + 1) + " x " + (pl.z1() - pl.z0() + 1) + " · " + pl.size() + " " + pl.kind() + " plot";
            cards.add((-e.getKey() - 1) + "|plot|" + HomePlots.label(pl).replace('|', '/') + "|" + size + "|" + built + "|3|" + (here ? 1 : 0));
        }
        ServerPlayNetworking.send(p, new Net.HomeList(cards, MAX_HOMES));
    }

    /** Your homes and property on the map: town house doors and plot centres. */
    public void markers(ServerPlayerEntity p, List<Net.Marker> list) {
        for (Deed d : deeds(p)) {
            if (d.home < 0 || d.home >= AotRpg.PLACES.homes.size()) continue;
            int[] h = AotRpg.PLACES.homes.get(d.home);
            list.add(new Net.Marker("home", "Your home · " + townOf(h), h[6], h[4] + 1, h[7], 0xF2C14E));
        }
        String me = stem(p);
        for (var e : data.plots.entrySet()) {
            if (!me.equals(e.getValue().stem) || e.getKey() >= AotRpg.PLACES.plots.size()) continue;
            Places.PlotInfo pl = AotRpg.PLACES.plots.get(e.getKey());
            list.add(new Net.Marker("home", "Your property · #" + pl.id(), (pl.x0() + pl.x1()) / 2, pl.y(), (pl.z0() + pl.z1()) / 2, 0xF2C14E));
        }
    }

    public void forget(ServerPlayerEntity p) {
        returnTo.remove(p.getUuid());
    }

    /** Sneak + use anywhere on a plot (or its sign): the plot's deed. */
    public boolean usePlot(ServerPlayerEntity p, BlockPos pos, boolean sign) {
        int idx = HomePlots.plotAt(pos, sign ? 4 : 0);
        if (idx < 0) return false;
        sendPlot(p, idx, true);
        return true;
    }

    public boolean ownsPlotAt(ServerPlayerEntity p, BlockPos pos) {
        return HomePlots.ownsAt(p, pos);
    }

    public static boolean isDoor(BlockState s) {
        return s.getBlock() instanceof DoorBlock;
    }

    /** A saddled horse bonded to the owner, for the stable. */
    static void horse(ServerWorld w, double x, double y, double z, ServerPlayerEntity owner) {
        HorseEntity horse = EntityType.HORSE.create(w);
        if (horse == null) return;
        horse.refreshPositionAndAngles(x, y, z, 0, 0);
        horse.bondWithPlayer(owner);
        horse.saddle(new ItemStack(Items.SADDLE), null);
        horse.setPersistent();
        w.spawnEntity(horse);
    }
}
