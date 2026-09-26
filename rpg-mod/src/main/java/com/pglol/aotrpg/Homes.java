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

    public enum Upgrade {
        STABLE("Stable", 2500, "A paddock and stable in your yard: room for 4 more horses."),
        FORGE("Forge", 3000, "Anvil, forge and tools: upgrade and craft gear."),
        GARDEN("Garden", 1500, "Tilled beds with water for growing ingredients."),
        POND("Fishing Pond", 1200, "A stocked pond to fish in at home."),
        STORAGE("Storage Shed", 1000, "A shed with four large chests for your valuables.");

        public final String title, desc;
        public final int price;

        Upgrade(String title, int price, String desc) {
            this.title = title;
            this.price = price;
            this.desc = desc;
        }
    }

    public static final class Deed {
        public int home;
        public int instance;
        public List<String> upgrades = new ArrayList<>();
        public String ownerName = "";
        /** Staircases repaired (homes bought before the fix). */
        public boolean stairsFixed;
        /** Bandit raids: days played since the last one, and when. */
        public int daysPlayed;
        public long lastDay, lastRaid;
    }

    /** An exclusive plot: one owner, the house is built on the plot in the real world. */
    public static final class PlotDeed {
        public String stem;
        public String ownerName = "";
        public int template = -1;
        /** A stable built on the plot (room for more horses). */
        public boolean stable;
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
        return BASE_X + (n % PER_ROW) * CELL;
    }

    private static int originZ(int n) {
        return (n / PER_ROW) * CELL;
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
        if (own != null && !sneaking) {
            enter(p, own);
            return true;
        }
        if (sneaking) {
            send(p, home, true);
            return true;
        }
        int[] h = AotRpg.PLACES.homes.get(home);
        p.sendMessage(Text.literal("For sale: ").formatted(Formatting.GRAY).append(Wallet.marks(price(h)))
            .append(Text.literal("  ·  Sneak + use the door to see the deed").formatted(Formatting.DARK_GRAY)), true);
        return false;
    }

    /** A door inside a home: the front door leads back to town. */
    public boolean useHomeDoor(ServerPlayerEntity p, BlockPos pos) {
        int n = instanceAt(pos);
        if (n < 0) return false;
        String owner = data.instances.get(n);
        Deed d = find(owner, n);
        if (d == null) return false;
        int[] h = AotRpg.PLACES.homes.get(d.home);
        BlockPos front = map(h, n, h[6], h[4] + 1, h[7]);
        if (front.getSquaredDistance(pos) > 9) return false;
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
        int ix = Math.floorDiv(pos.getX() - BASE_X, CELL), iz = Math.floorDiv(pos.getZ(), CELL);
        if (ix < 0 || ix >= PER_ROW || iz < 0) return -1;
        return iz * PER_ROW + ix;
    }

    public void enter(ServerPlayerEntity p, Deed d) {
        ServerWorld hw = homeWorld();
        if (hw == null) {
            p.sendMessage(Text.literal("The home world is not loaded on this server.").formatted(Formatting.RED), true);
            return;
        }
        if (p.getWorld().getRegistryKey() != WORLD) returnTo.put(p.getUuid(), new double[] {p.getX(), p.getY(), p.getZ(), p.getYaw()});
        int[] h = AotRpg.PLACES.homes.get(d.home);
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
        p.teleport(hw, inside.getX() + 0.5 + sx * 2, inside.getY(), inside.getZ() + 0.5 + sz * 2, p.getYaw(), 0);
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, SoundCategory.BLOCKS, 0.7f, 1f);
        p.sendMessage(Text.literal("Home").formatted(Formatting.GOLD, Formatting.BOLD)
            .append(Text.literal("  ·  " + townOf(h) + "  ·  the front door leads back out").formatted(Formatting.GRAY)), true);
    }

    private void leave(ServerPlayerEntity p, int[] h) {
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
        if (home < 0 && !action.equals("manage") && !action.equals("offers") && !action.equals("stables")) {
            plotAction(p, action, -home - 1);
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
                enter(p, grant(stem(p), AotRpg.PROFILES.get(p.getUuid()).name, home));
            }
            case "buy" -> buy(p, home);
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
                    p.sendMessage(Text.literal("You don't own a home yet. Sneak + use the door of any town house, or the sign of a plot.").formatted(Formatting.GRAY), true);
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
        Titles.show(p, Text.literal("HOME BOUGHT").formatted(Formatting.GOLD, Formatting.BOLD),
            Text.literal(townOf(h)).formatted(Formatting.GRAY), 10, 50, 20);
        enter(p, d);
    }

    /** Gives a character (by stem) a deed to a house and builds its copy. */
    Deed grant(String stem, String name, int home) {
        Deed d = new Deed();
        d.stairsFixed = true;
        d.home = home;
        d.instance = data.nextInstance++;
        d.ownerName = name;
        data.owners.computeIfAbsent(stem, k -> new ArrayList<>()).add(d);
        data.instances.put(d.instance, stem);
        save();
        build(AotRpg.PLACES.homes.get(home), d.instance);
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
        if (!AotRpg.WALLET.spendMarks(p, u.price)) {
            p.sendMessage(Text.literal("You need " + u.price + " Marks.").formatted(Formatting.RED), true);
            return;
        }
        d.upgrades.add(u.name());
        save();
        HomeYard.build(homeWorld(), u, originX(d.instance), FLOOR, originZ(d.instance), YARD, p);
        p.sendMessage(Text.literal(u.title + " built in your yard.").formatted(Formatting.GOLD), false);
        send(p, home, false);
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

    private void plotAction(ServerPlayerEntity p, String action, int idx) {
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
            Upgrade.STABLE.price, d != null && d.stable));
        ServerPlayNetworking.send(p, new Net.HomeView(-idx - 1, HomePlots.label(plot), size, HomePlots.price(plot),
            d != null && d.stem.equals(stem(p)), 0, 1, ups, List.of(), open, "plot", o == null ? -1 : o.price,
            d == null ? "" : d.ownerName));
    }

    public void send(ServerPlayerEntity p, int home, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.HomeView.ID) || home < 0 || home >= AotRpg.PLACES.homes.size()) return;
        int[] h = AotRpg.PLACES.homes.get(home);
        Deed d = deed(p, home);
        List<Net.HomeUpgrade> ups = new ArrayList<>();
        for (Upgrade u : Upgrade.values()) ups.add(new Net.HomeUpgrade(u.name(), u.title, u.desc, u.price, d != null && d.upgrades.contains(u.name())));
        List<String> visits = new ArrayList<>();
        for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
            if (o != p && AotRpg.PARTIES.same(p.getUuid(), o.getUuid()) && deed(o, home) != null) visits.add(o.getName().getString());
        }
        String size = (h[2] - h[0] + 1) + " x " + (h[3] - h[1] + 1) + " · " + Math.max(1, (h[5] - h[4]) / 6) + (h[5] - h[4] >= 12 ? " floors" : " floor");
        Offer o = HomeAdmin.offerFor(p, home);
        ServerPlayNetworking.send(p, new Net.HomeView(home, townOf(h), size, price(h), d != null, deeds(p).size(), MAX_HOMES, ups, visits, open,
            "home", o == null ? -1 : o.price, ""));
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
