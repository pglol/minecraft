package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
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
 * Extraction. Choosing it puts you in your squad's hot air balloon, drifting above the clouds:
 * everyone who comes aboard takes a seat on the benches while the burner roars, and on it are the
 * deployment board, your stash (your homes' chests and barrels, plus rows bought with Salvage) and
 * a refueler. Deploy and you all jump, sky-diving down onto one of the islands: the Verdant Reach
 * (a vast rainforest), the Ashen Crags, or Frostfell. Titans roam there, a few at a time; supply
 * barrels hold gear, gas and the odd refueler; three flares burn at the exits. Stand in one for
 * eight seconds to get out with everything you carry. Die and it stays where you fell; still out
 * when the clock runs down and you're missing in action, and it's all lost.
 */
public final class Extraction {
    /** The balloons, in the home world far from any home: one per squad, 512 blocks apart. */
    static final BlockPos LOBBY = new BlockPos(-400_000, 170, 0);
    private static final int SLOT = 512;
    private static final long RUN_MS = 20 * 60_000L;
    private static final int EXTRACT_TICKS = 160, EXIT_RADIUS = 4, BARRELS = 12, ISLAND = 480;
    private static final String SEAT = "aot_seat", TITAN = "aot_ex";

    /** The islands. */
    public enum Island {
        VERDANT("verdant", "Verdant Reach", 8, 16, 3, 0xFF5BD35B),
        ASHEN("ashen", "Ashen Crags", 16, 26, 4, 0xFFE0823A),
        FROSTFELL("frostfell", "Frostfell", 26, 40, 4, 0xFF9AD0FF);

        public final String id, title;
        public final int min, max, titans, color;
        public final RegistryKey<World> world;

        Island(String id, String title, int min, int max, int titans, int color) {
            this.id = id;
            this.title = title;
            this.min = min;
            this.max = max;
            this.titans = titans;
            this.color = color;
            this.world = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("aot_rpg", id));
        }

        int level() {
            return (min + max) / 2;
        }

        /** What drifts off its titans: moss in the rainforest, red dust in the crags, snow up north. */
        ParticleEffect aura() {
            return switch (this) {
                case VERDANT -> new BlockStateParticleEffect(ParticleTypes.FALLING_DUST, Blocks.MOSS_BLOCK.getDefaultState());
                case ASHEN -> new BlockStateParticleEffect(ParticleTypes.FALLING_DUST, Blocks.RED_SAND.getDefaultState());
                case FROSTFELL -> ParticleTypes.SNOWFLAKE;
            };
        }

        static Island of(String id) {
            for (Island i : values()) if (i.id.equals(id)) return i;
            return null;
        }

        static Island of(World w) {
            for (Island i : values()) if (w.getRegistryKey() == i.world) return i;
            return null;
        }
    }

    static final class Run {
        Island island;
        final LinkedHashSet<UUID> members = new LinkedHashSet<>();
        long endsAt;
        /** The island's match this squad joined (its exits and barrels, shared with every squad in it). */
        Session session;
        List<BlockPos> exits = new ArrayList<>();
        List<BlockPos> barrels = new ArrayList<>();
        final Map<UUID, Integer> extracting = new HashMap<>();
        /** Each diver's tasks for this run, and the barrels they've opened. */
        final Map<UUID, List<Task>> tasks = new HashMap<>();
        final Map<UUID, Set<BlockPos>> opened = new HashMap<>();
        /** Map marks: one per diver, seen by the whole squad. */
        final Map<UUID, BlockPos> marks = new HashMap<>();
    }

    /** A run task: paid out only if you get out alive with it done. */
    static final class Task {
        final String id, text;
        final int goal;
        int progress;

        Task(String id, String text, int goal) {
            this.id = id;
            this.text = text;
            this.goal = goal;
        }
    }

    /** Hot zones: richer caches, more titans, and a name on the map. */
    static final class Poi {
        String name;
        BlockPos at;
        final List<BlockPos> caches = new ArrayList<>();
        boolean woke, done;
    }

    private static final String[][] POI_NAMES = {
        {"Overgrown Supply Depot", "Titan Nest", "Sunken Chapel", "Vine-Choked Watchtower"},
        {"Collapsed Mine", "Scout Outpost Ruins", "Titan Nest", "Burnt Waystation"},
        {"Frozen Garrison", "Ice Cave", "Titan Nest", "Buried Supply Sled"}};

    /**
     * A match on an island: it starts with the first squad to drop and runs as long as anyone is
     * out there. Squads that drop later join it in progress: the same flares, the same barrels
     * (some already picked through), and whoever else is still out on the island.
     */
    static final class Session {
        Island island;
        long startedAt;
        /** Which instance of the island this match has to itself, its middle there, and its id. */
        int shard;
        BlockPos centre;
        final String id = Long.toString(System.nanoTime() ^ (long) (Math.random() * Long.MAX_VALUE), 36);
        /** The match's events: when the next comes, and a supply drop on its way down (or landed). */
        long nextEventAt;
        UUID dropEntity;
        BlockPos dropAt;
        boolean dropOpened;
        final List<BlockPos> exits = new ArrayList<>();
        final List<BlockPos> barrels = new ArrayList<>();
        final List<Poi> pois = new ArrayList<>();
        final List<RunObjectives.Objective> objectives = new ArrayList<>();
    }

    /**
     * Every match running, each on its own instance of its island: a region of the island's world
     * far from any other (see centre), so any number of games run at once without meeting.
     */
    private final List<Session> sessions = new ArrayList<>();
    /** Squads that deploy within this long of a match starting share it (at most MATCH_SQUADS). */
    private static final long JOIN_WINDOW_MS = 120_000;
    private static final int MATCH_SQUADS = 4, SHARD_SPACING = 8000, PREGEN_SHARDS = 3;

    static final class Data {
        int balloonVersion;
        Set<Integer> built = new HashSet<>();
        /** Island centres, found once: the most land-rich spot near the origin. */
        Map<String, int[]> centres = new HashMap<>();
        /** Barrels placed by runs, per island (cleared if a restart cut a run short). */
        Map<String, List<Long>> leftover = new HashMap<>();
        /** How far each island's terrain has been generated ahead of time (chunks walked). */
        Map<String, Integer> pregen = new HashMap<>();
    }

    /** Loads a chunk briefly so it generates off the main thread (then saves and unloads). */
    private static final net.minecraft.server.world.ChunkTicketType<net.minecraft.util.math.ChunkPos> PREGEN =
        net.minecraft.server.world.ChunkTicketType.create("aot_pregen", java.util.Comparator.comparingLong(net.minecraft.util.math.ChunkPos::toLong), 60);

    /**
     * The islands are generated ahead of time, a chunk a tick, in the background: jungle terrain
     * is heavy to make, and making it while someone lands in it is what made runs lag. Pauses
     * whenever the server is busy.
     */
    private void pregen(int ticks) {
        if (server.getAverageTickTime() > 35) return;
        int r = ISLAND / 16 + 3, side = r * 2 + 1;
        for (int shard = 0; shard < PREGEN_SHARDS; shard++) for (Island i : Island.values()) {
            ServerWorld w = server.getWorld(i.world);
            if (w == null) continue;
            String key = shardKey(i, shard);
            int idx = data.pregen.getOrDefault(key, 0);
            if (idx >= side * side) continue;
            BlockPos c = centre(i, w, shard);
            int cx = c.getX() >> 4, cz = c.getZ() >> 4;
            // Skip the corners outside the island's circle, then ask for one chunk.
            while (idx < side * side) {
                int dx = idx % side - r, dz = idx / side - r;
                idx++;
                if (dx * dx + dz * dz > r * r) continue;
                var pos = new net.minecraft.util.math.ChunkPos(cx + dx, cz + dz);
                w.getChunkManager().addTicket(PREGEN, pos, 0, pos);
                break;
            }
            data.pregen.put(key, idx);
            if (ticks % 400 == 0) save();
            return;
        }
    }

    private static final int BALLOON_VERSION = 5;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private Data data = new Data();
    private Path file;
    private MinecraftServer server;
    private final List<Run> runs = new ArrayList<>();
    /** Which balloon each squad rides: owner (leader or solo player) -> slot. */
    private final Map<UUID, Integer> slots = new HashMap<>();
    private static Extraction self;

    public void open(MinecraftServer server) {
        this.server = server;
        self = this;
        runs.clear();
        slots.clear();
        sessions.clear();
        file = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("extraction.json");
        try {
            if (Files.exists(file)) data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read extraction.json", e);
        }
        if (data == null) data = new Data();
        if (data.built == null) data.built = new HashSet<>();
        if (data.centres == null) data.centres = new HashMap<>();
        if (data.leftover == null) data.leftover = new HashMap<>();
        if (data.pregen == null) data.pregen = new HashMap<>();
        lobbyWorld = server.getWorld(SKY) != null ? SKY : Homes.WORLD;
        if (data.balloonVersion != BALLOON_VERSION) data.built.clear();
        data.balloonVersion = BALLOON_VERSION;
        for (Island i : Island.values()) {
            ServerWorld w = server.getWorld(i.world);
            if (w == null) continue;
            for (long l : data.leftover.getOrDefault(i.id, List.of())) {
                BlockPos bp = BlockPos.fromLong(l);
                var st = w.getBlockState(bp);
                if (st.isOf(Blocks.BARREL) || st.isOf(Blocks.LODESTONE) || st.isOf(Blocks.CAMPFIRE) || st.isOf(Blocks.CHEST) || st.isOf(Blocks.TARGET)) {
                    if (w.getBlockEntity(bp) instanceof net.minecraft.inventory.Inventory b) b.clear();
                    w.setBlockState(bp, Blocks.AIR.getDefaultState());
                }
            }
        }
        data.leftover.clear();
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

    // ------------------------------------------------------------------ the islands

    private static String shardKey(Island i, int shard) {
        return shard == 0 ? i.id : i.id + ":" + shard;
    }

    /**
     * The centre of one instance of an island: the spot with the most land around it, near that
     * instance's own origin (instances lie SHARD_SPACING apart), found from the terrain noise once.
     */
    private BlockPos centre(Island i, ServerWorld w, int shard) {
        String key = shardKey(i, shard);
        int ox = (shard % 16) * SHARD_SPACING, oz = (shard / 16) * SHARD_SPACING;
        int[] c = data.centres.get(key);
        if (c == null) {
            var gen = w.getChunkManager().getChunkGenerator();
            var noise = w.getChunkManager().getNoiseConfig();
            int sea = w.getSeaLevel(), bx = ox, bz = oz, best = -1;
            for (int gx = -5; gx <= 5; gx++) {
                for (int gz = -5; gz <= 5; gz++) {
                    int cx = ox + gx * 480, cz = oz + gz * 480, land = 0;
                    for (int sx = -3; sx <= 3; sx++) {
                        for (int sz = -3; sz <= 3; sz++) {
                            int h = gen.getHeight(cx + sx * 90, cz + sz * 90, Heightmap.Type.WORLD_SURFACE_WG, w, noise);
                            if (h > sea + 2) land++;
                        }
                    }
                    // Land, but not too far from the middle.
                    int score = land * 10 - Math.abs(gx) - Math.abs(gz);
                    if (score > best) {
                        best = score;
                        bx = cx;
                        bz = cz;
                    }
                }
            }
            c = new int[] {bx, bz};
            data.centres.put(key, c);
            save();
            AotRpg.LOG.info("Extraction island {} (instance {}) centred at {}, {}", i.id, shard, bx, bz);
        }
        return new BlockPos(c[0], 0, c[1]);
    }

    /** Which level titans on this island are (null world or off the islands: the open world's level). */
    public static int levelIn(World w, double x, double z) {
        Island i = w == null ? null : Island.of(w);
        return i != null ? i.level() : AotRpg.PLACES.levelAt(x, z);
    }

    /** Solid, dry ground near x, z (tries a few spots around it), or null. */
    static BlockPos land(ServerWorld w, int x, int z, Random r, int spread) {
        for (int t = 0; t < 12; t++) {
            int px = x + (t == 0 ? 0 : r.nextInt(spread * 2 + 1) - spread), pz = z + (t == 0 ? 0 : r.nextInt(spread * 2 + 1) - spread);
            w.getChunk(px >> 4, pz >> 4);
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, px, pz);
            BlockPos at = new BlockPos(px, y, pz);
            if (w.getFluidState(at.down()).isEmpty() && y > w.getSeaLevel()) return at;
        }
        return null;
    }

    // ------------------------------------------------------------------ the balloons

    /** The balloons' own sky, a world of their own (the home world only if it's missing). */
    static final RegistryKey<World> SKY = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("aot_rpg", "sky"));
    private static RegistryKey<World> lobbyWorld = Homes.WORLD;

    public static RegistryKey<World> lobbyWorld() {
        return lobbyWorld;
    }

    private ServerWorld sky() {
        ServerWorld w = server.getWorld(SKY);
        return w != null ? w : AotRpg.HOMES.stashWorld();
    }

    private static BlockPos origin(int slot) {
        return LOBBY.add(-slot * SLOT, 0, 0);
    }

    private static int slotAt(double x) {
        return (int) Math.round((LOBBY.getX() - x) / SLOT);
    }

    public static boolean inLobby(ServerPlayerEntity p) {
        return p.getWorld().getRegistryKey() == lobbyWorld && p.getX() < LOBBY.getX() + SLOT / 2.0;
    }

    public static boolean inLobby(BlockPos pos) {
        return pos.getX() < LOBBY.getX() + SLOT / 2;
    }

    // Offsets in a balloon (from its origin, the middle of the basket floor).
    private static final BlockPos BOARD = new BlockPos(4, 1, 0), STASH = new BlockPos(-4, 1, 0), FUEL = new BlockPos(-4, 1, 2),
        BENCH = new BlockPos(-4, 1, -2), ANVIL = new BlockPos(4, 1, -2), FURNACE = new BlockPos(4, 1, -3), GRIND = new BlockPos(4, 1, 2),
        ENTRY = new BlockPos(0, 1, 1);
    private static final int BASKET = 5, ENVELOPE = 11, ENVELOPE_Y = 18;

    /** Builds a hot air balloon: a wicker basket with benches, ropes up to a striped envelope, the burner between. */
    private void buildBalloon(int slot) {
        ServerWorld w = sky();
        if (w == null || data.built.contains(slot)) return;
        BlockPos o = origin(slot);
        int f = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        WorldCare.quiet(true);
        try {
            // Clear the space first (an older build, or anything else).
            for (BlockPos bp : BlockPos.iterate(o.add(-16, -4, -16), o.add(16, ENVELOPE_Y + ENVELOPE + 2, 16))) {
                if (!w.getBlockState(bp).isAir()) w.setBlockState(bp, Blocks.AIR.getDefaultState(), f);
            }
            // The basket: a woven floor, wicker walls, a rail round the top.
            for (int x = -BASKET; x <= BASKET; x++) {
                for (int z = -BASKET; z <= BASKET; z++) {
                    boolean wall = Math.abs(x) == BASKET || Math.abs(z) == BASKET;
                    w.setBlockState(o.add(x, -1, z), Blocks.STRIPPED_SPRUCE_WOOD.getDefaultState(), f);
                    w.setBlockState(o.add(x, 0, z), wall ? Blocks.STRIPPED_OAK_WOOD.getDefaultState()
                        : (x + z) % 2 == 0 ? Blocks.SPRUCE_PLANKS.getDefaultState() : Blocks.BAMBOO_MOSAIC.getDefaultState(), f);
                    if (wall) {
                        w.setBlockState(o.add(x, 1, z), (x + z) % 2 == 0 ? Blocks.STRIPPED_OAK_WOOD.getDefaultState() : Blocks.BAMBOO_BLOCK.getDefaultState(), f);
                        w.setBlockState(o.add(x, 2, z), Blocks.SPRUCE_FENCE.getDefaultState(), f);
                    }
                }
            }
            for (int x = -BASKET; x <= BASKET; x++) for (int z = -BASKET; z <= BASKET; z++) {
                if (Math.abs(x) != BASKET && Math.abs(z) != BASKET) continue;
                BlockPos fp = o.add(x, 2, z);
                w.setBlockState(fp, Block.postProcessState(w.getBlockState(fp), w, fp), f);
            }
            // Benches along the north and south walls, backs to the wicker.
            for (int x = -3; x <= 3; x++) {
                w.setBlockState(o.add(x, 1, -BASKET + 1), Blocks.DARK_OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.NORTH), f);
                w.setBlockState(o.add(x, 1, BASKET - 1), Blocks.DARK_OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.SOUTH), f);
            }
            // The board, the stash and a refueler.
            w.setBlockState(o.add(BOARD), Blocks.LECTERN.getDefaultState().with(net.minecraft.block.LecternBlock.FACING, Direction.WEST), f);
            w.setBlockState(o.add(STASH), Blocks.ENDER_CHEST.getDefaultState().with(net.minecraft.block.EnderChestBlock.FACING, Direction.EAST), f);
            if (Refueler.BLOCK != null) w.setBlockState(o.add(FUEL), Refueler.BLOCK.getDefaultState(), f);
            // The workbench for field kit, and the forge's anvil.
            w.setBlockState(o.add(BENCH), Blocks.CRAFTING_TABLE.getDefaultState(), f);
            w.setBlockState(o.add(ANVIL), Blocks.ANVIL.getDefaultState().with(net.minecraft.block.AnvilBlock.FACING, Direction.NORTH), f);
            // The forge corner: anvil, a lit blast furnace, a grindstone.
            w.setBlockState(o.add(FURNACE), Blocks.BLAST_FURNACE.getDefaultState().with(net.minecraft.block.AbstractFurnaceBlock.FACING, Direction.WEST)
                .with(net.minecraft.block.AbstractFurnaceBlock.LIT, true), f);
            w.setBlockState(o.add(GRIND), Blocks.GRINDSTONE.getDefaultState().with(net.minecraft.block.GrindstoneBlock.FACE, net.minecraft.block.enums.BlockFace.FLOOR), f);
            labels(w, o);
            // Sandbags hanging off the rail.
            for (int[] s : new int[][] {{BASKET + 1, -2}, {BASKET + 1, 2}, {-BASKET - 1, -2}, {-BASKET - 1, 2}, {0, BASKET + 1}, {0, -BASKET - 1}}) {
                w.setBlockState(o.add(s[0], 1, s[1]), Blocks.CHAIN.getDefaultState(), f);
                w.setBlockState(o.add(s[0], 0, s[1]), Blocks.BROWN_WOOL.getDefaultState(), f);
            }
            // The envelope: a tall balloon of red and cream gores, open at the bottom.
            int ey = ENVELOPE_Y;
            for (int x = -ENVELOPE - 1; x <= ENVELOPE + 1; x++) {
                for (int y = -ENVELOPE - 3; y <= ENVELOPE + 1; y++) {
                    for (int z = -ENVELOPE - 1; z <= ENVELOPE + 1; z++) {
                        // Stretched a little tall, narrowing to the mouth.
                        double sy = y < 0 ? y / 1.25 : y / 1.05;
                        double d = Math.sqrt(x * x + sy * sy + z * z);
                        if (d > ENVELOPE + 0.5 || d < ENVELOPE - 0.7) continue;
                        if (y < -ENVELOPE + 2 && x * x + z * z < 12) continue; // the mouth
                        double ang = Math.atan2(z, x);
                        int gore = (int) Math.floor((ang + Math.PI) / (Math.PI * 2) * 12);
                        BlockState s = y > ENVELOPE - 2 ? Blocks.YELLOW_WOOL.getDefaultState()
                            : Math.abs(y) <= 1 ? Blocks.YELLOW_WOOL.getDefaultState()
                            : gore % 2 == 0 ? Blocks.RED_WOOL.getDefaultState() : Blocks.WHITE_WOOL.getDefaultState();
                        w.setBlockState(o.add(x, ey + y, z), s, f);
                    }
                }
            }
            // Ropes from the basket's corners up to the envelope's skirt.
            int skirt = ey - (int) (ENVELOPE * 1.25) + 2;
            for (int[] c : new int[][] {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                Vec3d a = new Vec3d(c[0] * BASKET, 3, c[1] * BASKET), b = new Vec3d(c[0] * 3.5, skirt, c[1] * 3.5);
                for (int k = 0; k <= 24; k++) {
                    Vec3d p = a.lerp(b, k / 24.0);
                    BlockPos bp = o.add((int) Math.round(p.x), (int) Math.round(p.y), (int) Math.round(p.z));
                    if (w.getBlockState(bp).isAir()) w.setBlockState(bp, Blocks.CHAIN.getDefaultState(), f);
                }
            }
            // The burner, hung in the middle under the mouth.
            for (int y = 5; y < skirt; y++) w.setBlockState(o.add(0, y, 0), Blocks.CHAIN.getDefaultState(), f);
            w.setBlockState(o.add(0, 4, 0), Blocks.CAMPFIRE.getDefaultState().with(net.minecraft.block.CampfireBlock.SIGNAL_FIRE, true), f);
            w.setBlockState(o.add(0, 3, 0), Blocks.IRON_TRAPDOOR.getDefaultState(), f);
            data.built.add(slot);
            save();
        } finally {
            WorldCare.quiet(false);
        }
    }

    /** Floating names over each station, so there's no hunting for the forge. */
    private void labels(ServerWorld w, BlockPos o) {
        for (Entity e : w.getOtherEntities(null, new Box(o).expand(8), e -> e.getCommandTags().contains("aot_label"))) e.discard();
        Object[][] list = {{STASH, "Stash", "gold"}, {BENCH, "Workbench", "yellow"}, {ANVIL, "Forge", "red"}, {FUEL, "Refuel", "aqua"},
            {BOARD, "Lobby", "green"}};
        for (Object[] l : list) {
            BlockPos b = o.add((BlockPos) l[0]);
            // A name floating over the station: an invisible marker stand showing its name.
            ArmorStandEntity d = new ArmorStandEntity(EntityType.ARMOR_STAND, w);
            net.minecraft.nbt.NbtCompound tag = new net.minecraft.nbt.NbtCompound();
            tag.putBoolean("Marker", true);
            tag.putBoolean("Invisible", true);
            d.readCustomDataFromNbt(tag);
            d.setInvisible(true);
            d.setNoGravity(true);
            d.setInvulnerable(true);
            d.setSilent(true);
            Formatting col = Formatting.byName((String) l[2]);
            d.setCustomName(Text.literal((String) l[1]).formatted(col == null ? Formatting.GOLD : col, Formatting.BOLD));
            d.setCustomNameVisible(true);
            d.refreshPositionAndAngles(b.getX() + 0.5, b.getY() + 0.9, b.getZ() + 0.5, 0, 0);
            d.addCommandTag("aot_label");
            w.spawnEntity(d);
        }
    }

    /** Joined a party whose leader is aboard a balloon: straight up to join them. */
    public void joinedParty(ServerPlayerEntity p, UUID leader) {
        ServerPlayerEntity lead = server.getPlayerManager().getPlayer(leader);
        if (lead == null || lead == p || !inLobby(lead) || runOf(p.getUuid()) != null || runOf(leader) != null) return;
        if (inLobby(p) && slotAt(p.getX()) == slotAt(lead.getX())) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        pr.mode = DeathCare.EXTRACTION;
        AotRpg.PROFILES.save(p.getUuid());
        toLobby(p);
    }

    /** Which balloon this player boards: their squad leader's if the leader is aboard one, else their own. */
    private int slotFor(ServerPlayerEntity p) {
        Parties.Party party = AotRpg.PARTIES.of(p.getUuid());
        if (party != null) {
            // The leader's balloon if they're aboard, else any squadmate's: a party always rides together.
            ServerPlayerEntity lead = server.getPlayerManager().getPlayer(party.leader);
            if (lead != null && lead != p && inLobby(lead)) return slotAt(lead.getX());
            for (UUID m : party.members) {
                ServerPlayerEntity o = server.getPlayerManager().getPlayer(m);
                if (o != null && o != p && inLobby(o)) {
                    int at = slotAt(o.getX());
                    slots.put(party.leader, at);
                    return at;
                }
            }
        }
        UUID owner = party != null ? party.leader : p.getUuid();
        Integer s = slots.get(owner);
        if (s != null) return s;
        Set<Integer> used = new HashSet<>(slots.values());
        int n = 0;
        while (used.contains(n)) n++;
        slots.put(owner, n);
        return n;
    }

    /** Aboard the balloon (from the open world, or back from a run). */
    public void toLobby(ServerPlayerEntity p) {
        toLobby(p, false);
    }

    /** Where each boarding player is being walked to (their seat), and when they sit regardless. */
    private final Map<UUID, BlockPos> walkingTo = new HashMap<>();
    private final Map<UUID, Integer> sitBy = new HashMap<>();
    private int ticks;

    /**
     * Boarding: you arrive in the basket and the camera takes over (a different opening for a first
     * boarding and a return from a run) while your character walks to a free seat on the benches and
     * sits. The lobby opens once the camera hands back.
     */
    public void toLobby(ServerPlayerEntity p, boolean returning) {
        ServerWorld w = sky();
        if (w == null) return;
        // Nobody arrives aboard still bleeding on the ground, nor with anything in their pockets:
        // it all goes to the stash, and the loadout is picked from there.
        AotRpg.DOWNED.release(p);
        LootBox.forget(p.getUuid());
        Stash.bankAll(p);
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (p.getWorld().getRegistryKey() == World.OVERWORLD && !pr.inRun) pr.openWorldPos = new double[] {p.getX(), p.getY(), p.getZ(), p.getYaw()};
        pr.inRun = false;
        AotRpg.PROFILES.save(p.getUuid());
        int slot = slotFor(p);
        buildBalloon(slot);
        BlockPos o = origin(slot);
        Homes.border(w, p);
        p.stopRiding();
        p.fallDistance = 0;
        BlockPos seat = freeSeat(slot, p);
        BlockPos in = o.add(ENTRY);
        float yaw = seat == null ? 0 : (float) Math.toDegrees(Math.atan2(-(seat.getX() - in.getX()), seat.getZ() - in.getZ()));
        p.teleport(w, in.getX() + 0.5, in.getY(), in.getZ() + 0.5, yaw, 0);
        p.playSoundToPlayer(SoundEvents.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.AMBIENT, 0.5f, 0.6f);
        Lobby lb = lobby(slot);
        lb.ready.remove(p.getUuid());
        lb.countdownAt = 0;
        if (seat == null) {
            broadcast(slot);
            return;
        }
        // Straight onto the bench: no cutscene, you're simply aboard.
        List<ServerPlayerEntity> aboard = aboard(slot);
        sitOn(p, seat);
        for (ServerPlayerEntity m : aboard) {
            if (m == p) continue;
            Notify.toast(m, Text.literal(pr.name + " climbs aboard").formatted(Formatting.GOLD), null, 0xE0B96A, "minecraft:ladder", null);
            m.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_ATTACK_WEAK, SoundCategory.PLAYERS, 0.6f, 0.8f);
        }
        broadcast(slot);
    }

    /**
     * Up off the bench, onto the floor right in front of it, placed there by the server so the
     * client and the server agree where you are (a plain dismount could leave them apart, and then
     * every click fails the reach check).
     */
    private void stand(ServerPlayerEntity p) {
        Entity seat = p.getVehicle();
        p.stopRiding();
        if (seat == null) return;
        BlockPos s = seat.getBlockPos();
        BlockState st = p.getWorld().getBlockState(s);
        Direction face = st.getBlock() instanceof StairsBlock ? st.get(StairsBlock.FACING).getOpposite() : Direction.SOUTH;
        BlockPos to = s.offset(face);
        p.networkHandler.requestTeleport(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, face.asRotation(), 0);
        p.fallDistance = 0;
    }

    /** A bench spot nobody is sitting on or walking to (nearest the entry). */
    private BlockPos freeSeat(int slot, ServerPlayerEntity p) {
        ServerWorld w = sky();
        BlockPos o = origin(slot);
        List<BlockPos> spots = new ArrayList<>();
        for (int x : new int[] {0, -1, 1, -2, 2, -3, 3}) {
            spots.add(o.add(x, 1, -BASKET + 1));
            spots.add(o.add(x, 1, BASKET - 1));
        }
        // Squadmates sit side by side: the free seat nearest one of them (seated or on the way) first.
        List<BlockPos> mates = new ArrayList<>();
        Parties.Party party = AotRpg.PARTIES.of(p.getUuid());
        if (party != null) {
            for (UUID m : party.members) {
                if (m.equals(p.getUuid())) continue;
                BlockPos to = walkingTo.get(m);
                ServerPlayerEntity mate = server.getPlayerManager().getPlayer(m);
                if (to != null) mates.add(to);
                else if (mate != null && inLobby(mate) && mate.getVehicle() != null && mate.getVehicle().getCommandTags().contains(SEAT)) mates.add(mate.getVehicle().getBlockPos());
            }
        }
        if (!mates.isEmpty()) {
            spots.sort(java.util.Comparator.comparingDouble(sp -> {
                double best = Double.MAX_VALUE;
                for (BlockPos m : mates) {
                    // Same bench counts for far more than across the basket.
                    double d = Math.abs(sp.getX() - m.getX()) + (sp.getZ() == m.getZ() ? 0 : 20);
                    best = Math.min(best, d);
                }
                return best;
            }));
        }
        for (BlockPos s : spots) {
            if (!(w.getBlockState(s).getBlock() instanceof StairsBlock)) continue;
            if (walkingTo.containsValue(s) && !s.equals(walkingTo.get(p.getUuid()))) continue;
            boolean taken = false;
            for (ArmorStandEntity a : w.getEntitiesByClass(ArmorStandEntity.class, new Box(s).expand(0.1), e -> e.getCommandTags().contains(SEAT))) {
                if (a.hasPassengers() && a.getFirstPassenger() != p) taken = true;
            }
            if (!taken) return s;
        }
        return null;
    }

    private List<ServerPlayerEntity> aboard(int slot) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        ServerWorld w = sky();
        if (w == null) return out;
        for (ServerPlayerEntity o : new java.util.ArrayList<>(w.getPlayers())) if (inLobby(o) && slotAt(o.getX()) == slot) out.add(o);
        return out;
    }

    /** Sits on this bench block if nobody is on it. */
    private boolean sitOn(ServerPlayerEntity p, BlockPos s) {
        ServerWorld w = sky();
        if (!(w.getBlockState(s).getBlock() instanceof StairsBlock)) return false;
        Box b = new Box(s).expand(0.1);
        for (ArmorStandEntity a : w.getEntitiesByClass(ArmorStandEntity.class, b, e -> e.getCommandTags().contains(SEAT))) {
            if (a.hasPassengers()) return false;
            a.discard();
        }
        ArmorStandEntity seat = new ArmorStandEntity(EntityType.ARMOR_STAND, w);
        net.minecraft.nbt.NbtCompound tag = new net.minecraft.nbt.NbtCompound();
        tag.putBoolean("Marker", true);
        tag.putBoolean("Invisible", true);
        seat.readCustomDataFromNbt(tag);
        seat.setInvisible(true);
        seat.setNoGravity(true);
        seat.setInvulnerable(true);
        seat.setSilent(true);
        seat.addCommandTag(SEAT);
        Direction face = w.getBlockState(s).get(StairsBlock.FACING).getOpposite();
        seat.refreshPositionAndAngles(s.getX() + 0.5, s.getY() + 0.45, s.getZ() + 0.5, face.asRotation(), 0);
        w.spawnEntity(seat);
        p.startRiding(seat, true);
        // Tell the client again once it surely knows the seat (right after a dimension change the
        // seat can arrive after the ride, and a client that thinks it's standing can't use anything).
        for (int d : new int[] {3, 20}) {
            AotRpg.SCHEDULER.later(d, () -> {
                if (!p.isDisconnected() && p.getVehicle() == seat) {
                    p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket(seat));
                }
            });
        }
        p.setYaw(face.asRotation());
        p.setHeadYaw(face.asRotation());
        return true;
    }

    /** The balloon breathes: the burner roars now and then, clouds stream past, empty seats go. */
    private void lobbyTick(int ticks) {
        ServerWorld w = sky();
        if (w == null) return;
        Map<Integer, List<ServerPlayerEntity>> bySlot = new HashMap<>();
        for (ServerPlayerEntity p : new java.util.ArrayList<>(w.getPlayers())) {
            if (!inLobby(p)) continue;
            int slot = slotAt(p.getX());
            bySlot.computeIfAbsent(slot, k -> new ArrayList<>()).add(p);
            BlockPos o = origin(slot);
            // Over the side: caught and set back in the basket.
            if (p.getY() < o.getY() - 8) {
                p.fallDistance = 0;
                p.teleport(w, o.getX() + 0.5, o.getY() + 1, o.getZ() + 0.5, p.getYaw(), 0);
            }
        }
        Random r = w.getRandom();
        for (var e : bySlot.entrySet()) {
            BlockPos o = origin(e.getKey());
            double ox = o.getX() + 0.5, oy = o.getY(), oz = o.getZ() + 0.5;
            for (ServerPlayerEntity p : e.getValue()) {
                // Clouds drifting by, above and below, as if the balloon were sailing on.
                if (ticks % 4 == 0) {
                    for (int i = 0; i < 6; i++) {
                        double cx = ox - 40 + r.nextDouble() * 10, cy = oy - 30 + r.nextDouble() * 60, cz = oz - 45 + r.nextDouble() * 90;
                        if (Math.abs(cy - oy - 8) < 14 && Math.abs(cz - oz) < 16) continue;
                        w.spawnParticles(p, ParticleTypes.CLOUD, true, cx, cy, cz, 0, 1, 0, (r.nextDouble() - 0.5) * 0.05, 0.35);
                    }
                    // A sea of cloud far below.
                    for (int i = 0; i < 4; i++) {
                        w.spawnParticles(p, ParticleTypes.CLOUD, true, ox - 60 + r.nextDouble() * 120, oy - 40 - r.nextDouble() * 6, oz - 60 + r.nextDouble() * 120,
                            0, 1, 0, 0, 0.15);
                    }
                }
                // The burner's flame, and every few seconds a roar.
                if (ticks % 3 == 0) w.spawnParticles(p, ParticleTypes.FLAME, true, ox, oy + 5.2, oz, 3, 0.15, 0.4, 0.15, 0.02);
                if (ticks % 140 == 0) {
                    w.spawnParticles(p, ParticleTypes.FLAME, true, ox, oy + 6, oz, 60, 0.3, 1.8, 0.3, 0.08);
                    w.spawnParticles(p, ParticleTypes.LARGE_SMOKE, true, ox, oy + 8, oz, 10, 0.5, 1.5, 0.5, 0.02);
                    p.playSoundToPlayer(SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.AMBIENT, 0.7f, 0.5f);
                    p.playSoundToPlayer(SoundEvents.BLOCK_FIRE_AMBIENT, SoundCategory.AMBIENT, 1f, 0.6f);
                }
                if (ticks % 160 == 40) p.playSoundToPlayer(SoundEvents.ITEM_ELYTRA_FLYING, SoundCategory.AMBIENT, 0.08f, 0.5f);
                if (ticks % 200 == 100) p.playSoundToPlayer(SoundEvents.BLOCK_WOOD_HIT, SoundCategory.AMBIENT, 0.3f, 0.5f);
            }
        }
        if (ticks % 40 == 0) {
            List<Entity> empty = new ArrayList<>();
            for (Entity e : w.iterateEntities()) {
                if (e instanceof ArmorStandEntity a && a.getCommandTags().contains(SEAT) && !a.hasPassengers()) empty.add(a);
            }
            for (Entity e : empty) e.discard();
            // Balloons nobody is aboard are free for the next squad.
            slots.entrySet().removeIf(en -> !bySlot.containsKey(en.getValue())
                && (server.getPlayerManager().getPlayer(en.getKey()) == null || !inLobby(server.getPlayerManager().getPlayer(en.getKey()))));
        }
    }

    /** Up from the balloon into the open world, where the character was before. */
    public void toOpenWorld(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        ServerWorld ow = server.getOverworld();
        double[] at = pr.openWorldPos;
        pr.openWorldPos = null;
        AotRpg.PROFILES.save(p.getUuid());
        p.stopRiding();
        if (at != null && at.length >= 4) p.teleport(ow, at[0], at[1], at[2], (float) at[3], 0);
        else {
            BlockPos sp = ow.getSpawnPos();
            p.teleport(ow, sp.getX() + 0.5, ow.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, sp.getX(), sp.getZ()), sp.getZ() + 0.5, p.getYaw(), 0);
        }
    }

    /**
     * The balloon's blocks: a bench or the board seats you (and the lobby opens), the ender chest
     * opens the stash, the workbench the field kit, the anvil the forge.
     */
    public boolean use(ServerPlayerEntity p, BlockPos pos) {
        // At home: the chests and barrels counted into the stash open the stash itself.
        if (p.getWorld().getRegistryKey() == Homes.WORLD && !inLobby(pos) && Stash.counts(p.getWorld().getBlockEntity(pos))
            && AotRpg.HOMES.canBuild(p, pos) && AotRpg.HOMES.stashSpots(p).contains(pos)) {
            Stash.show(p, 0);
            return true;
        }
        if (p.getWorld().getRegistryKey() != lobbyWorld || !inLobby(pos)) {
            Run r = runOf(p.getUuid());
            if (r != null && RunObjectives.use(this, r.session, p, pos)) return true;
            if (r != null && r.barrels.contains(pos) && p.getServerWorld().getBlockEntity(pos) instanceof BarrelBlockEntity) {
                boolean cache = false;
                for (Poi poi : r.session.pois) if (poi.caches.contains(pos)) cache = true;
                if (r.opened.computeIfAbsent(p.getUuid(), k -> new HashSet<>()).add(pos.toImmutable())) {
                    bump(r, p.getUuid(), "barrels", 1);
                    if (cache) bump(r, p.getUuid(), "cache", 1);
                }
                LootBox.show(p, pos, cache ? "Cache" : "Supply Barrel");
                return true;
            }
            // Any other chest or barrel out on an island is searched the same way.
            if (r != null && Island.of(p.getWorld()) != null && p.getServerWorld().getBlockEntity(pos) instanceof net.minecraft.inventory.Inventory
                && (p.getWorld().getBlockState(pos).isOf(Blocks.BARREL) || p.getWorld().getBlockState(pos).getBlock() instanceof net.minecraft.block.ChestBlock)) {
                LootBox.show(p, pos, p.getWorld().getBlockState(pos).isOf(Blocks.BARREL) ? "Barrel" : "Crate");
                return true;
            }
            return false;
        }
        int slot = slotAt(pos.getX());
        BlockPos rel = pos.subtract(origin(slot));
        if (rel.equals(STASH)) {
            Stash.show(p, 0);
            return true;
        }
        if (rel.equals(BENCH)) {
            bench(p, true);
            return true;
        }
        if (rel.equals(ANVIL) || rel.equals(FURNACE) || rel.equals(GRIND)) {
            AotRpg.FORGE.open(p);
            return true;
        }
        if (rel.equals(FUEL)) return false;
        BlockPos seat = p.getWorld().getBlockState(pos).getBlock() instanceof StairsBlock ? pos : rel.equals(BOARD) ? freeSeat(slot, p) : null;
        if (seat != null && p.getVehicle() == null) {
            sitOn(p, seat);
            broadcast(slot);
        }
        return true;
    }

    // ------------------------------------------------------------------ the lobby

    /** A balloon's lobby: the island picked, squad fill, who's ready, and the countdown once all are. */
    static final class Lobby {
        String island = Island.VERDANT.id;
        boolean fill = true;
        final Set<UUID> ready = new HashSet<>();
        long countdownAt;
    }

    private static final long COUNTDOWN_MS = 10_000;
    public static final int SQUAD = 3;
    private final Map<Integer, Lobby> lobbies = new HashMap<>();

    private Lobby lobby(int slot) {
        return lobbies.computeIfAbsent(slot, k -> new Lobby());
    }

    /** Who runs a balloon: the squad leader or owner aboard, else whoever boarded first. */
    private UUID leader(int slot) {
        List<ServerPlayerEntity> on = aboard(slot);
        for (var e : slots.entrySet()) {
            if (e.getValue() != slot) continue;
            for (ServerPlayerEntity m : on) if (m.getUuid().equals(e.getKey())) return m.getUuid();
        }
        return on.isEmpty() ? null : on.get(0).getUuid();
    }

    /** Sends the lobby to everyone aboard. */
    private void broadcast(int slot) {
        for (ServerPlayerEntity m : aboard(slot)) sendLobby(m);
    }

    private static String pr(ServerPlayerEntity p) {
        return AotRpg.PROFILES.get(p.getUuid()).name;
    }

    public void sendLobby(ServerPlayerEntity p) {
        if (!ServerPlayNetworking.canSend(p, Net.LobbyView.ID) || !inLobby(p)) return;
        int slot = slotAt(p.getX());
        Lobby lb = lobby(slot);
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        List<Net.LobbyIsland> is = new ArrayList<>();
        for (Island i : Island.values()) if (server.getWorld(i.world) != null) is.add(new Net.LobbyIsland(i.id, i.title, i.min, i.max, i.color));
        UUID lead = leader(slot);
        List<Net.LobbyMember> squad = new ArrayList<>();
        for (ServerPlayerEntity m : aboard(slot)) {
            Profile mp = AotRpg.PROFILES.get(m.getUuid());
            squad.add(new Net.LobbyMember(mp.name, mp.level, lb.ready.contains(m.getUuid()), m == p, m.getUuid().equals(lead)));
        }
        long cd = lb.countdownAt == 0 ? -1 : Math.max(0, lb.countdownAt - System.currentTimeMillis());
        int[] use = Stash.usage(p);
        ServerPlayNetworking.send(p, new Net.LobbyView(is, lb.island, lb.fill, p.getUuid().equals(lead), squad, cd, pr.salvage, use[0], use[1],
            pr.stashRows >= Stash.MAX_ROWS ? -1 : Stash.rowCost(pr), social(p, slot)));
    }

    /** Friends (account wide) and recent teammates, online first, for the lobby's social tab. */
    private List<Net.LobbyFriend> social(ServerPlayerEntity p, int slot) {
        var friends = AotRpg.PROFILES.account(p.getUuid()).friends;
        Map<UUID, Net.LobbyFriend> out = new java.util.LinkedHashMap<>();
        java.util.function.BiConsumer<UUID, String> add = (id, name) -> {
            if (id.equals(p.getUuid()) || out.containsKey(id)) return;
            boolean friend = friends.containsKey(id.toString());
            boolean recent = false;
            for (String r : AotRpg.PROFILES.get(p.getUuid()).recentMates) if (r.startsWith(id.toString())) recent = true;
            ServerPlayerEntity o = server.getPlayerManager().getPlayer(id);
            String where = o == null ? "offline" : inLobby(o) && slotAt(o.getX()) == slot ? "aboard" : runOf(id) != null ? "in a run" : "online";
            String n = o != null ? AotRpg.PROFILES.get(id).name : name;
            out.put(id, new Net.LobbyFriend(id, n, friend, recent, where));
        };
        for (String r : AotRpg.PROFILES.get(p.getUuid()).recentMates) {
            int bar = r.indexOf('|');
            try {
                add.accept(UUID.fromString(r.substring(0, bar)), r.substring(bar + 1));
            } catch (Exception ignored) { }
        }
        for (var e : friends.entrySet()) {
            try {
                add.accept(UUID.fromString(e.getKey()), e.getValue());
            } catch (Exception ignored) { }
        }
        List<Net.LobbyFriend> list = new ArrayList<>(out.values());
        list.sort((a, b) -> Integer.compare(rank(a.where()), rank(b.where())));
        return list.size() > 24 ? list.subList(0, 24) : list;
    }

    private static int rank(String where) {
        return switch (where) {
            case "aboard" -> 0;
            case "online" -> 1;
            case "in a run" -> 2;
            default -> 3;
        };
    }

    /** A mark on the island map, for the squad: one each, placed or taken back. */
    private void mark(ServerPlayerEntity p, String action, String arg) {
        Run r = runOf(p.getUuid());
        if (r == null) return;
        if (action.equals("unmark")) {
            r.marks.remove(p.getUuid());
            return;
        }
        String[] xz = arg.split(",");
        if (xz.length != 2) return;
        int x, z;
        try {
            x = Integer.parseInt(xz[0].trim());
            z = Integer.parseInt(xz[1].trim());
        } catch (NumberFormatException e) {
            return;
        }
        ServerWorld w = p.getServerWorld();
        if (Island.of(w) == null) return;
        int y = w.isChunkLoaded(x >> 4, z >> 4) ? w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) : (int) p.getY();
        r.marks.put(p.getUuid(), new BlockPos(x, y, z));
        String name = pr(p);
        for (UUID id : r.members) {
            ServerPlayerEntity m = server.getPlayerManager().getPlayer(id);
            if (m == null) continue;
            m.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.PLAYERS, 0.8f, 1.6f);
            if (m != p) m.sendMessage(Text.literal(name + " marked a spot").formatted(Formatting.AQUA), true);
        }
    }

    /** The squad's marks as light columns, for the squad only. */
    private void markBeams(Run r, ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        DustParticleEffect cyan = new DustParticleEffect(new Vector3f(0.3f, 0.85f, 1f), 1.6f);
        for (BlockPos m : r.marks.values()) {
            double d2 = p.squaredDistanceTo(Vec3d.ofBottomCenter(m));
            if (d2 > 320 * 320) continue;
            for (int y = 0; y < 30; y += d2 > 100 * 100 ? 6 : 3) w.spawnParticles(p, cyan, true, m.getX() + 0.5, m.getY() + y, m.getZ() + 0.5, 1, 0.05, 0.3, 0.05, 0);
        }
    }

    /** Twice a second: seats for those walking to them, ready checks, fill, and the countdown. */
    private void lobbyState() {
        long now = System.currentTimeMillis();
        for (var e : new ArrayList<>(walkingTo.entrySet())) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null || !inLobby(p)) {
                walkingTo.remove(e.getKey());
                sitBy.remove(e.getKey());
                continue;
            }
            BlockPos s = e.getValue();
            double dx = p.getX() - s.getX() - 0.5, dz = p.getZ() - s.getZ() - 0.5;
            if (dx * dx + dz * dz < 1.3 || ticks >= sitBy.getOrDefault(e.getKey(), 0)) {
                walkingTo.remove(e.getKey());
                sitBy.remove(e.getKey());
                sitOn(p, s);
            }
        }
        for (var en : new ArrayList<>(lobbies.entrySet())) {
            int slot = en.getKey();
            Lobby lb = en.getValue();
            List<ServerPlayerEntity> on = aboard(slot);
            if (on.isEmpty()) {
                lobbies.remove(slot);
                continue;
            }
            lb.ready.removeIf(id -> on.stream().noneMatch(m -> m.getUuid().equals(id)));
            boolean all = on.stream().allMatch(m -> lb.ready.contains(m.getUuid()) && m.hasVehicle());
            if (!all) {
                if (lb.countdownAt != 0) {
                    lb.countdownAt = 0;
                    broadcast(slot);
                }
                continue;
            }
            if (lb.countdownAt == 0) {
                if (lb.fill && on.size() < SQUAD) fillFrom(slot, lb, on.size());
                lb.countdownAt = now + COUNTDOWN_MS;
                for (ServerPlayerEntity m : aboard(slot)) m.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.MASTER, 0.8f, 1.2f);
                broadcast(slot);
            } else if (now >= lb.countdownAt) {
                lb.countdownAt = 0;
                lb.ready.clear();
                deploy(slot);
            } else {
                long left = (lb.countdownAt - now + 999) / 1000;
                if (left <= 3) for (ServerPlayerEntity m : on) m.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.MASTER, 0.8f, 1.5f);
            }
        }
    }

    /** Squad fill: other ready balloons with fill on, bound for the same island, come aboard this one. */
    private void fillFrom(int slot, Lobby lb, int size) {
        for (var en : new ArrayList<>(lobbies.entrySet())) {
            if (en.getKey() == slot || size >= SQUAD) continue;
            Lobby o = en.getValue();
            List<ServerPlayerEntity> them = aboard(en.getKey());
            if (!o.fill || !o.island.equals(lb.island) || them.isEmpty() || size + them.size() > SQUAD) continue;
            if (!them.stream().allMatch(m -> o.ready.contains(m.getUuid()))) continue;
            for (ServerPlayerEntity m : them) {
                BlockPos seat = freeSeat(slot, m);
                if (seat == null) continue;
                m.stopRiding();
                sitOn(m, seat);
                lb.ready.add(m.getUuid());
                Notify.toast(m, Text.literal("Squad found").formatted(Formatting.GOLD), null, 0xE0B96A, "minecraft:spyglass", null);
                size++;
            }
            o.ready.clear();
        }
    }

    // ------------------------------------------------------------------ the workbench

    /** Field kit for the next run, paid in Marks (and Salvage for the rarer pieces). */
    private record Kit(String id, String title, String item, int count, long marks, long salvage) { }

    private static final List<Kit> KITS = List.of(
        new Kit("gas", "Gas Canister", "dannys-aot:gas_canister", 1, 80, 0),
        new Kit("blades", "Blade Components", "dannys-aot:blade_component", 8, 40, 0),
        new Kit("ice", "Ice Burst Clusters", "dannys-aot:ice_burst_cluster", 16, 30, 0),
        new Kit("spears", "Thunder Spears", "dannys-aot:thunder_spear", 2, 150, 10),
        new Kit("rations", "Field Rations", "minecraft:bread", 4, 20, 0),
        new Kit("refueler", "Gas Refueler", "aot_rpg:gas_refueler", 1, 0, 60));

    private void bench(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.BenchView.ID)) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        List<Net.BenchRecipe> list = new ArrayList<>();
        for (Kit k : KITS) {
            if (!net.minecraft.registry.Registries.ITEM.containsId(Identifier.of(k.item()))) continue;
            boolean ok = pr.marks >= k.marks() && pr.salvage >= k.salvage() && (!k.id().equals("refueler") || pr.has(Skill.ENG_WORKSHOP));
            list.add(new Net.BenchRecipe(k.id(), k.title(), k.item(), k.count(), k.marks(), k.salvage(), ok));
        }
        ServerPlayNetworking.send(p, new Net.BenchView(list, pr.marks, pr.salvage, open));
    }

    private void craft(ServerPlayerEntity p, String id) {
        Kit k = null;
        for (Kit x : KITS) if (x.id().equals(id)) k = x;
        if (k == null) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (k.id().equals("refueler") && !pr.has(Skill.ENG_WORKSHOP)) {
            Notify.toast(p, Text.literal("Engineers only").formatted(Formatting.RED), null, 0xC0463A, "aot_rpg:gas_refueler", null);
            return;
        }
        if (pr.salvage < k.salvage() || pr.marks < k.marks()) return;
        if (k.marks() > 0 && !AotRpg.WALLET.spendMarks(p, k.marks())) return;
        pr.salvage -= k.salvage();
        AotRpg.PROFILES.save(p.getUuid());
        var item = net.minecraft.registry.Registries.ITEM.get(Identifier.of(k.item()));
        Stash.store(p, new ItemStack(item, k.count()));
        p.playSoundToPlayer(SoundEvents.BLOCK_SMITHING_TABLE_USE, SoundCategory.BLOCKS, 0.8f, 1.2f);
        bench(p, false);
    }

    // ------------------------------------------------------------------ runs

    private Run runOf(UUID id) {
        for (Run r : runs) if (r.members.contains(id)) return r;
        return null;
    }

    public static boolean inRun(UUID id) {
        return self != null && self.runOf(id) != null;
    }

    /** The countdown ran out: everyone aboard jumps for the island picked. */
    private void deploy(int slot) {
        Lobby lb = lobby(slot);
        Island island = Island.of(lb.island);
        ServerWorld iw = island == null ? null : server.getWorld(island.world);
        List<ServerPlayerEntity> squad = new ArrayList<>();
        for (ServerPlayerEntity m : aboard(slot)) {
            if (DeathCare.EXTRACTION.equals(AotRpg.PROFILES.get(m.getUuid()).mode) && runOf(m.getUuid()) == null) squad.add(m);
        }
        if (iw == null || squad.isEmpty()) return;
        ServerPlayerEntity p = squad.get(0);
        Random rnd = p.getRandom();
        // Join a match that has only just started on this island (a few squads share one), else
        // open a fresh instance of the island all to this squad's match.
        long nowMs = System.currentTimeMillis();
        Session ses = null;
        for (Session o : sessions) {
            if (o.island != island || nowMs - o.startedAt > JOIN_WINDOW_MS) continue;
            int squads = 0;
            for (Run x : runs) if (x.session == o) squads++;
            if (squads < MATCH_SQUADS) {
                ses = o;
                break;
            }
        }
        boolean fresh = ses == null;
        if (fresh) {
            java.util.Set<Integer> used = new HashSet<>();
            for (Session o : sessions) if (o.island == island) used.add(o.shard);
            int shard = 0;
            while (used.contains(shard)) shard++;
            ses = new Session();
            ses.island = island;
            ses.shard = shard;
            ses.centre = centre(island, iw, shard);
        }
        BlockPos c = ses.centre;
        Run run = new Run();
        run.island = island;
        run.endsAt = System.currentTimeMillis() + RUN_MS + 15_000;
        double base = rnd.nextDouble() * Math.PI * 2;
        BlockPos drop = land(iw, c.getX() + (int) (Math.cos(base) * 260), c.getZ() + (int) (Math.sin(base) * 260), rnd, 60);
        if (drop == null) drop = land(iw, c.getX(), c.getZ(), rnd, 120);
        if (drop == null) drop = new BlockPos(c.getX(), iw.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()), c.getZ());
        if (fresh) {
            ses.startedAt = System.currentTimeMillis();
            sessions.add(ses);
            // The exits, spread round the island.
            for (int i = 0; i < 3; i++) {
                double a = base + Math.PI + (i - 1) * 1.6 + (rnd.nextDouble() - 0.5) * 0.4;
                double d = 230 + rnd.nextInt(170);
                BlockPos e = land(iw, c.getX() + (int) (Math.cos(a) * d), c.getZ() + (int) (Math.sin(a) * d), rnd, 40);
                if (e != null) ses.exits.add(e);
            }
            if (ses.exits.isEmpty()) ses.exits.add(drop);
            // Two hot zones: three rich caches each, and titans that wake when someone comes near.
            List<String> names = new ArrayList<>(List.of(POI_NAMES[island.ordinal()]));
            for (int i = 0; i < 2; i++) {
                double a = base + i * Math.PI + (rnd.nextDouble() - 0.5);
                double d = 110 + rnd.nextInt(170);
                BlockPos at = land(iw, c.getX() + (int) (Math.cos(a) * d), c.getZ() + (int) (Math.sin(a) * d), rnd, 30);
                if (at == null) continue;
                Poi poi = new Poi();
                poi.name = names.remove(rnd.nextInt(names.size()));
                poi.at = at;
                for (int k = 0; k < 3; k++) {
                    BlockPos bp = land(iw, at.getX() + rnd.nextInt(13) - 6, at.getZ() + rnd.nextInt(13) - 6, rnd, 4);
                    if (bp == null || !iw.getBlockState(bp).isReplaceable() && !iw.getBlockState(bp).isAir()) continue;
                    iw.setBlockState(bp, Blocks.BARREL.getDefaultState().with(net.minecraft.block.BarrelBlock.FACING, Direction.UP));
                    if (iw.getBlockEntity(bp) instanceof BarrelBlockEntity b) {
                        fill(b, island.level() + 3, rnd);
                        fill(b, island.level() + 3, rnd);
                    }
                    poi.caches.add(bp);
                    ses.barrels.add(bp);
                    data.leftover.computeIfAbsent(island.id, k2 -> new ArrayList<>()).add(bp.asLong());
                }
                ses.pois.add(poi);
            }
            RunObjectives.create(this, iw, ses, c, rnd);
            // Marleyans are already out here: a patrol or two, and one sitting on a hot zone.
            for (int i = 0; i < 2; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, d = 120 + rnd.nextInt(200);
                BlockPos at = land(iw, c.getX() + (int) (Math.cos(a) * d), c.getZ() + (int) (Math.sin(a) * d), rnd, 30);
                if (at != null) Troops.squad(iw, at, 3 + rnd.nextInt(2), island.level(), ses.id, c, ISLAND, rnd);
            }
            if (!ses.pois.isEmpty()) {
                Poi guard = ses.pois.get(rnd.nextInt(ses.pois.size()));
                Troops.squad(iw, guard.at, 3, island.level(), ses.id, guard.at, 40, rnd);
            }
            ses.nextEventAt = System.currentTimeMillis() + (150 + rnd.nextInt(90)) * 1000L;
        }
        run.session = ses;
        run.exits = ses.exits;
        run.barrels = ses.barrels;
        // Supply barrels through the jungle (or the crags, or the snow); a few fresh ones when joining late.
        List<Long> left = data.leftover.computeIfAbsent(island.id, k -> new ArrayList<>());
        for (int i = 0; i < (fresh ? BARRELS : 4); i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = 20 + rnd.nextInt(420);
            BlockPos bp = land(iw, c.getX() + (int) (Math.cos(a) * d), c.getZ() + (int) (Math.sin(a) * d), rnd, 20);
            if (bp == null || !iw.getBlockState(bp).isReplaceable() && !iw.getBlockState(bp).isAir()) continue;
            iw.setBlockState(bp, Blocks.BARREL.getDefaultState().with(net.minecraft.block.BarrelBlock.FACING, Direction.UP));
            if (iw.getBlockEntity(bp) instanceof BarrelBlockEntity b) fill(b, island.level(), rnd);
            run.barrels.add(bp);
            left.add(bp.asLong());
        }
        save();
        runs.add(run);
        for (ServerPlayerEntity m : squad) {
            run.members.add(m.getUuid());
            run.tasks.put(m.getUuid(), rollTasks(rnd));
            Profile mp = AotRpg.PROFILES.get(m.getUuid());
            mp.inRun = true;
            // Everyone you drop with goes to the top of your recent teammates.
            for (ServerPlayerEntity o : squad) {
                if (o == m) continue;
                String id = o.getUuid().toString();
                mp.recentMates.removeIf(r -> r.startsWith(id));
                mp.recentMates.add(0, id + "|" + AotRpg.PROFILES.get(o.getUuid()).name);
            }
            while (mp.recentMates.size() > 12) mp.recentMates.remove(mp.recentMates.size() - 1);
            AotRpg.PROFILES.save(m.getUuid());
        }
        // The drop: burner, over the rail, pulling away as they go; then from the ground, the squad falling in.
        BlockPos o = origin(slot);
        for (ServerPlayerEntity m : squad) m.playSoundToPlayer(SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.MASTER, 0.9f, 0.5f);
        int ticks = Cinematics.cut(squad, Cinematics.drop(Vec3d.ofBottomCenter(o)), "", "", island.color & 0xFFFFFF);
        BlockPos at = drop;
        AotRpg.SCHEDULER.later(ticks, () -> {
            int k = 0;
            List<ServerPlayerEntity> landed = new ArrayList<>();
            for (ServerPlayerEntity m : squad) {
                if (m.isDisconnected() || !run.members.contains(m.getUuid())) continue;
                double ox = (k % 3 - 1) * 3, oz = (k / 3) * 3;
                k++;
                m.stopRiding();
                m.teleport(iw, at.getX() + 0.5 + ox, at.getY() + 45, at.getZ() + 0.5 + oz, m.getYaw(), 50);
                m.fallDistance = 0;
                m.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 20 * 14, 0, false, false));
                m.playSoundToPlayer(SoundEvents.ITEM_ELYTRA_FLYING, SoundCategory.MASTER, 0.6f, 1f);
                landed.add(m);
            }
            // Where you are, said once and small, as you fall.
            long in = (System.currentTimeMillis() - run.session.startedAt) / 60_000;
            for (ServerPlayerEntity m : landed) {
                Notify.toast(m, Text.literal(island.title).formatted(Formatting.BOLD), Text.literal(in < 1 ? "Find the flares" : in + " min in"),
                    island.color & 0xFFFFFF, "minecraft:compass", null);
            }
        });
    }

    /** Two tasks for a run, picked from the pool. */
    private static List<Task> rollTasks(Random r) {
        List<Task> pool = new ArrayList<>(List.of(
            new Task("barrels", "Search 3 supply barrels", 3),
            new Task("titans", "Slay 2 titans", 2),
            new Task("cache", "Loot a hot zone cache", 1),
            new Task("gear", "Get out with 4 pieces of gear", 4)));
        List<Task> out = new ArrayList<>();
        for (int i = 0; i < 2 && !pool.isEmpty(); i++) out.add(pool.remove(r.nextInt(pool.size())));
        return out;
    }

    private static void bump(Run r, UUID id, String task, int by) {
        for (Task t : r.tasks.getOrDefault(id, List.of())) {
            if (!t.id.equals(task) || t.progress >= t.goal) continue;
            t.progress = Math.min(t.goal, t.progress + by);
        }
    }

    /** A titan fell to someone out on a run. */
    public void onTitanKill(ServerPlayerEntity killer, Entity dead) {
        for (Session ses : sessions) RunObjectives.titanDied(this, ses, dead);
        if (dead.getCommandTags().contains(ABNORMAL) && dead.getWorld() instanceof ServerWorld w) {
            // The abnormal's hoard: gear bursting out where it fell.
            Island i = Island.of(w);
            int lvl = i == null ? 20 : i.level() + 4;
            for (int k = 0; k < 3; k++) {
                ItemStack g = Gear.roll(w.random, Gear.rollRarity(w.random, 4), lvl);
                if (g.isEmpty()) continue;
                net.minecraft.entity.ItemEntity ie = new net.minecraft.entity.ItemEntity(w, dead.getX(), dead.getY() + 2, dead.getZ(), g,
                    (w.random.nextDouble() - 0.5) * 0.6, 0.6, (w.random.nextDouble() - 0.5) * 0.6);
                w.spawnEntity(ie);
            }
            w.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, dead.getX(), dead.getY() + 3, dead.getZ(), 120, 2, 3, 2, 0.6);
            if (killer != null) Reveal.show(killer, "ABNORMAL SLAIN", "Its hoard spills out", "minecraft:nether_star", 5);
        }
        Run r = killer == null ? null : runOf(killer.getUuid());
        if (r != null) bump(r, killer.getUuid(), "titans", 1);
    }

    /** A supply barrel: gear for the island's level, gas, blades, and now and then a refueler. */
    static void fill(BarrelBlockEntity b, int level, Random r) {
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
        this.ticks = ticks;
        pregen(ticks);
        if (ticks % 10 == 5) specTick();
        if (ticks % 20 == 11 && !rejoining.isEmpty()) {
            long now = System.currentTimeMillis();
            for (var e : new ArrayList<>(rejoining.entrySet())) {
                if (now < e.getValue().deadline) continue;
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
                if (p != null) rejoin(p, true);
                else rejoining.remove(e.getKey());
            }
        }
        if (ticks % 200 == 7) finishedBy.keySet().removeIf(r -> !runs.contains(r) && runs.stream().noneMatch(o -> o.session == r.session));
        if (ticks % 10 == 0) {
            for (Session ses : sessions) {
                ServerWorld sw = server.getWorld(ses.island.world);
                if (sw == null) continue;
                List<ServerPlayerEntity> divers = new ArrayList<>();
                for (Run r : runs) {
                    if (r.session != ses) continue;
                    for (UUID id : r.members) {
                        ServerPlayerEntity m = server.getPlayerManager().getPlayer(id);
                        if (m != null && m.getWorld() == sw && m.isAlive()) divers.add(m);
                    }
                }
                RunObjectives.tick(this, sw, ses, divers, ticks);
                if (ticks % 20 == 0) events(sw, ses, divers);
                if (ses.dropEntity != null || ses.dropAt != null) supplyTick(sw, ses, ticks);
            }
        }
        for (Island island : Island.values()) {
            ServerWorld iw = server.getWorld(island.world);
            if (iw == null) continue;
            Troops.tick(iw, ticks);
            if (ticks % 20 == 3) Troops.titans(iw);
        }
        lobbyTick(ticks);
        if (ticks % 40 == 17 && lobbyWorld != Homes.WORLD) {
            ServerWorld hw = server.getWorld(Homes.WORLD);
            if (hw != null) for (ServerPlayerEntity p : new ArrayList<>(hw.getPlayers())) {
                if (p.getX() < LOBBY.getX() + SLOT / 2.0 && p.isAlive() && !p.isSpectator()) toLobby(p);
            }
        }
        if (ticks % 10 == 0) lobbyState();
        islandTick(ticks);
        if (runs.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Run r : new ArrayList<>(runs)) {
            ServerWorld iw = server.getWorld(r.island.world);
            for (UUID id : new ArrayList<>(r.members)) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                if (p == null) {
                    // Away when the clock ran out: missing in action, settled when they come back.
                    if (now > r.endsAt) {
                        r.members.remove(id);
                        missedOffline.add(id);
                    }
                    continue;
                }
                if (now > r.endsAt) {
                    missing(r, p);
                    continue;
                }
                if (p.getWorld() != iw) continue;
                if (ticks % 10 == 0) flares(r, p, ticks);
                if (ticks % 20 == 0) shore(r, p, iw);
                if (ticks % 20 == 10) hotZones(r, p, iw);
                if (ticks % 10 == 5) sendView(r, p, now);
                if (ticks % 20 == 3 && !r.marks.isEmpty()) markBeams(r, p);
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

    // ------------------------------------------------------------------ match events

    static final String ABNORMAL = "aot_abnormal";

    /** Tells everyone in a match something big is happening, with which way and how far. */
    private static void announce(List<ServerPlayerEntity> divers, String title, BlockPos at, int color, String icon,
                                 net.minecraft.sound.SoundEvent sound, float pitch) {
        for (ServerPlayerEntity p : divers) {
            double dx = at.getX() - p.getX(), dz = at.getZ() - p.getZ();
            String[] dirs = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
            String dir = dirs[Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(-dx, dz)) / 45.0), 8)];
            Notify.toast(p, Text.literal(title).formatted(Formatting.BOLD), Text.literal(dir + "  ·  " + (int) Math.sqrt(dx * dx + dz * dz) + "m"),
                color, icon, null);
            p.playSoundToPlayer(sound, SoundCategory.MASTER, 1f, pitch);
        }
    }

    /** Every few minutes something happens in a match: a supply drop, a Marleyan landing, an abnormal. */
    private void events(ServerWorld w, Session ses, List<ServerPlayerEntity> divers) {
        long now = System.currentTimeMillis();
        if (divers.isEmpty() || ses.nextEventAt == 0 || now < ses.nextEventAt) return;
        Random r = w.random;
        ses.nextEventAt = now + (160 + r.nextInt(120)) * 1000L;
        BlockPos c = ses.centre;
        int roll = r.nextInt(10);
        if (roll < 4 && ses.dropEntity == null && (ses.dropAt == null || ses.dropOpened)) {
            // A supply drop: a crate coming down slow under a red smoke trail, full of the good stuff.
            double a = r.nextDouble() * Math.PI * 2, d = 60 + r.nextInt(200);
            BlockPos ground = land(w, c.getX() + (int) (Math.cos(a) * d), c.getZ() + (int) (Math.sin(a) * d), r, 30);
            if (ground == null) return;
            BlockPos sky = ground.up(70);
            w.setBlockState(sky, Blocks.BARREL.getDefaultState().with(net.minecraft.block.BarrelBlock.FACING, Direction.UP), Block.NOTIFY_LISTENERS);
            var fb = net.minecraft.entity.FallingBlockEntity.spawnFromBlock(w, sky, w.getBlockState(sky));
            fb.dropItem = false;
            fb.setNoGravity(true);
            ses.dropEntity = fb.getUuid();
            ses.dropAt = ground;
            ses.dropOpened = false;
            Troops.lure(ses.id, ground);
            announce(divers, "SUPPLY DROP", ground, 0xE0463A, "minecraft:barrel", SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.6f);
            for (ServerPlayerEntity p : divers) p.playSoundToPlayer(SoundEvents.BLOCK_BELL_USE, SoundCategory.MASTER, 0.8f, 0.7f);
        } else if (roll < 7) {
            // A Marleyan landing: a squad put down near someone, horn blaring.
            ServerPlayerEntity p = divers.get(r.nextInt(divers.size()));
            double a = r.nextDouble() * Math.PI * 2, d = 55 + r.nextInt(40);
            BlockPos at = land(w, (int) (p.getX() + Math.cos(a) * d), (int) (p.getZ() + Math.sin(a) * d), r, 15);
            if (at == null || Troops.count(ses.id) > 14) return;
            Troops.squad(w, at, 4 + r.nextInt(2), ses.island.level() + 2, ses.id, c, ISLAND, r);
            announce(divers, "MARLEYAN LANDING", at, 0xC0463A, "minecraft:crossbow", SoundEvents.EVENT_RAID_HORN.value(), 1f);
        } else {
            // An abnormal: one titan bigger, faster and meaner than the rest, carrying a hoard.
            List<EntityType<?>> kinds = TitanTypes.ordinary();
            if (kinds.isEmpty()) return;
            ServerPlayerEntity p = divers.get(r.nextInt(divers.size()));
            double a = r.nextDouble() * Math.PI * 2, d = 80 + r.nextInt(40);
            BlockPos at = land(w, (int) (p.getX() + Math.cos(a) * d), (int) (p.getZ() + Math.sin(a) * d), r, 15);
            if (at == null) return;
            Entity t = kinds.get(r.nextInt(kinds.size())).create(w);
            if (!(t instanceof net.minecraft.entity.mob.MobEntity mob)) return;
            t.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, r.nextFloat() * 360, 0);
            mob.initialize(w, w.getLocalDifficulty(at), net.minecraft.entity.SpawnReason.EVENT, null);
            mob.setPersistent();
            var scale = mob.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_SCALE);
            if (scale != null) scale.setBaseValue(1.45);
            var hp = mob.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH);
            if (hp != null) hp.setBaseValue(hp.getBaseValue() * 2.2);
            var sp = mob.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED);
            if (sp != null) sp.setBaseValue(sp.getBaseValue() * 1.35);
            mob.setHealth(mob.getMaxHealth());
            t.addCommandTag(TITAN);
            t.addCommandTag("aot_titan");
            t.addCommandTag(ABNORMAL);
            t.setCustomName(Text.literal("Abnormal").formatted(Formatting.DARK_RED, Formatting.BOLD));
            w.spawnEntity(t);
            announce(divers, "ABNORMAL SIGHTED", at, 0x9A1A1A, "minecraft:wither_skeleton_skull", SoundEvents.ENTITY_RAVAGER_ROAR, 0.5f);
        }
    }

    /** The supply drop coming down (slowly, trailing smoke), landing, and its beacon until someone opens it. */
    private void supplyTick(ServerWorld w, Session ses, int ticks) {
        if (ses.dropEntity != null) {
            Entity e = w.getEntity(ses.dropEntity);
            if (e instanceof net.minecraft.entity.FallingBlockEntity fb && fb.isAlive()) {
                fb.setVelocity(0, -0.28, 0);
                fb.velocityModified = true;
                if (ticks % 2 == 0) w.spawnParticles(new net.minecraft.particle.DustParticleEffect(new org.joml.Vector3f(0.9f, 0.15f, 0.1f), 2.5f),
                    fb.getX(), fb.getY() + 1.2, fb.getZ(), 3, 0.3, 0.3, 0.3, 0);
                if (fb.getY() <= ses.dropAt.getY() + 0.6) {
                    fb.discard();
                    land(w, ses);
                }
                return;
            }
            if (ticks % 20 == 0) {
                // Lost track of it (a chunk unloaded under it): it lands now.
                if (e != null) e.discard();
                land(w, ses);
            }
            return;
        }
        if (ses.dropAt == null || ses.dropOpened) return;
        // A red smoke column over it, seen from anywhere on the island, until someone opens it.
        if (ticks % 4 == 0) {
            for (int k = 0; k < 3; k++) w.spawnParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, ses.dropAt.getX() + 0.5, ses.dropAt.getY() + 1.2, ses.dropAt.getZ() + 0.5,
                1, 0.1, 0.1, 0.1, 0.02);
            w.spawnParticles(new net.minecraft.particle.DustParticleEffect(new org.joml.Vector3f(0.95f, 0.2f, 0.15f), 2f),
                ses.dropAt.getX() + 0.5, ses.dropAt.getY() + 2 + w.random.nextDouble() * 6, ses.dropAt.getZ() + 0.5, 2, 0.15, 0.6, 0.15, 0);
        }
        if (!(w.getBlockEntity(ses.dropAt) instanceof BarrelBlockEntity b) || b.isEmpty()) {
            ses.dropOpened = true;
            Troops.lure(ses.id, null);
        }
    }

    /** The drop hits the ground: a thump, dust, and a crate of the good stuff. */
    private void land(ServerWorld w, Session ses) {
        ses.dropEntity = null;
        BlockPos at = ses.dropAt;
        w.setBlockState(at, Blocks.BARREL.getDefaultState().with(net.minecraft.block.BarrelBlock.FACING, Direction.UP));
        if (w.getBlockEntity(at) instanceof BarrelBlockEntity b) {
            b.clear();
            int lvl = ses.island.level() + 4;
            for (int k = 0; k < 3; k++) {
                ItemStack g = Gear.roll(w.random, Gear.rollRarity(w.random, 3), lvl);
                if (!g.isEmpty()) b.setStack(k * 3, g);
            }
            net.minecraft.item.Item gas = AotItems.exact("gas_canister"), spears = AotItems.exact("thunder_spear");
            if (gas != null) b.setStack(12, new ItemStack(gas, 2));
            if (spears != null && w.random.nextBoolean()) b.setStack(14, new ItemStack(spears, 1 + w.random.nextInt(2)));
            if (Refueler.ITEM != null && w.random.nextFloat() < 0.4f) b.setStack(16, new ItemStack(Refueler.ITEM));
        }
        ses.barrels.add(at);
        track(ses.island, at);
        w.playSound(null, at, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.BLOCKS, 1.2f, 1.4f);
        w.playSound(null, at, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.BLOCKS, 1.4f, 0.6f);
        w.spawnParticles(ParticleTypes.EXPLOSION, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 3, 1, 0.2, 1, 0);
        w.spawnParticles(ParticleTypes.CLOUD, at.getX() + 0.5, at.getY() + 0.2, at.getZ() + 0.5, 40, 2, 0.1, 2, 0.08);
        // A recovery team is on its way for it.
        if (w.random.nextFloat() < 0.7f && Troops.count(ses.id) < 14) {
            double a = w.random.nextDouble() * Math.PI * 2;
            BlockPos t = land(w, at.getX() + (int) (Math.cos(a) * 45), at.getZ() + (int) (Math.sin(a) * 45), w.random, 10);
            if (t != null) Troops.squad(w, t, 3, ses.island.level() + 2, ses.id, at, 30, w.random);
        }
    }

    /** The island's edge: the sea mist turns you back. */
    private void shore(Run r, ServerPlayerEntity p, ServerWorld iw) {
        BlockPos c = r.session.centre;
        double dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ(), d = Math.sqrt(dx * dx + dz * dz);
        if (d < ISLAND) return;
        p.setVelocity(-dx / d * 1.2, 0.4, -dz / d * 1.2);
        p.velocityModified = true;
        p.sendMessage(Text.literal("The sea mist turns you back").formatted(Formatting.AQUA), true);
        iw.spawnParticles(p, ParticleTypes.CLOUD, true, p.getX() + dx / d * 3, p.getY() + 1, p.getZ() + dz / d * 3, 30, 2, 1.5, 2, 0.02);
    }

    /**
     * Titans on the islands: only ours, and only a few at a time (a handful plus one per diver),
     * wandering in out of sight and gone again when nobody is near. Each sheds its island.
     */
    private void islandTick(int ticks) {
        if (ticks % 20 != 0) return;
        for (Island island : Island.values()) {
            ServerWorld w = server.getWorld(island.world);
            if (w == null) continue;
            List<ServerPlayerEntity> divers = new ArrayList<>();
            for (ServerPlayerEntity p : new java.util.ArrayList<>(w.getPlayers())) {
                if (runOf(p.getUuid()) != null) divers.add(p);
                // Anyone left on an island outside a run goes back aboard (never the dead, nor someone watching their fall).
                else if (ticks % 100 == 0 && p.isAlive() && !p.isSpectator() && !p.isCreative() && !watching.containsKey(p.getUuid())) toLobby(p);
            }
            List<Entity> titans = new ArrayList<>();
            for (Entity e : w.iterateEntities()) if (e.getCommandTags().contains(TITAN) && e.isAlive()) titans.add(e);
            for (Entity t : titans) {
                boolean near = false;
                for (ServerPlayerEntity p : divers) if (p.squaredDistanceTo(t) < 180 * 180) near = true;
                if (!near) {
                    t.discard();
                    continue;
                }
                if (ticks % 60 == 0) w.spawnParticles(island.aura(), t.getX(), t.getY() + t.getHeight() * 0.6, t.getZ(), 4,
                    t.getWidth() * 0.4, t.getHeight() * 0.3, t.getWidth() * 0.4, 0);
            }
            if (divers.isEmpty() || ticks % 200 != 0) continue;
            ServerPlayerEntity p = divers.get(w.getRandom().nextInt(divers.size()));
            // Each match (instance) keeps its own titan count.
            Session here = sessionAt(w, p.getX(), p.getZ());
            if (here == null) continue;
            double reach = ISLAND + 200;
            int mine = 0, theirs = 0;
            for (ServerPlayerEntity d : divers) if (sessionAt(w, d.getX(), d.getZ()) == here) mine++;
            for (Entity t : titans) {
                double tx = t.getX() - here.centre.getX(), tz = t.getZ() - here.centre.getZ();
                if (tx * tx + tz * tz < reach * reach) theirs++;
            }
            int cap = Math.min(8, island.titans + mine);
            if (theirs >= cap) continue;
            List<EntityType<?>> kinds = TitanTypes.ordinary();
            if (kinds.isEmpty()) continue;
            double a = w.getRandom().nextDouble() * Math.PI * 2, d = 70 + w.getRandom().nextInt(50);
            BlockPos at = land(w, (int) (p.getX() + Math.cos(a) * d), (int) (p.getZ() + Math.sin(a) * d), w.getRandom(), 10);
            if (at == null) continue;
            Entity t = kinds.get(w.getRandom().nextInt(kinds.size())).create(w);
            if (t == null) continue;
            t.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, w.getRandom().nextFloat() * 360, 0);
            if (t instanceof net.minecraft.entity.mob.MobEntity mob) {
                mob.initialize(w, w.getLocalDifficulty(at), net.minecraft.entity.SpawnReason.EVENT, null);
                mob.setPersistent();
            }
            t.addCommandTag(TITAN);
            t.addCommandTag("aot_titan");
            w.spawnEntity(t);
        }
    }

    /** Titans guarding a hot zone, woken when someone comes near. */
    private void wake(ServerWorld w, Poi poi, int n) {
        spawnTitans(w, poi.at, n, 20, null);
    }

    /** n titans around a spot (within spread), tagged for an objective if given. Returns how many came. */
    static int spawnTitans(ServerWorld w, BlockPos around, int n, int spread, String tag) {
        List<EntityType<?>> kinds = TitanTypes.ordinary();
        if (kinds.isEmpty()) return 0;
        int made = 0;
        for (int i = 0; i < n; i++) {
            BlockPos at = land(w, around.getX() + w.getRandom().nextInt(spread * 2 + 1) - spread,
                around.getZ() + w.getRandom().nextInt(spread * 2 + 1) - spread, w.getRandom(), 10);
            if (at == null) continue;
            Entity t = kinds.get(w.getRandom().nextInt(kinds.size())).create(w);
            if (t == null) continue;
            t.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, w.getRandom().nextFloat() * 360, 0);
            if (t instanceof net.minecraft.entity.mob.MobEntity mob) {
                mob.initialize(w, w.getLocalDifficulty(at), net.minecraft.entity.SpawnReason.EVENT, null);
                mob.setPersistent();
            }
            t.addCommandTag(TITAN);
            t.addCommandTag("aot_titan");
            if (tag != null) t.addCommandTag(tag);
            if (w.spawnEntity(t)) made++;
        }
        return made;
    }

    /** Remembers a block a run placed, so a restart that cuts the run short still clears it. */
    void track(Island island, BlockPos bp) {
        data.leftover.computeIfAbsent(island.id, k -> new ArrayList<>()).add(bp.asLong());
    }

    /** Hot zones: titans wake when a diver comes within 60 blocks; a zone is done once its caches are empty. */
    private void hotZones(Run r, ServerPlayerEntity p, ServerWorld w) {
        for (Poi poi : r.session.pois) {
            if (!poi.woke && p.squaredDistanceTo(Vec3d.ofCenter(poi.at)) < 60 * 60) {
                poi.woke = true;
                wake(w, poi, 3);
                Notify.toast(p, Text.literal(poi.name).formatted(Formatting.RED, Formatting.BOLD), Text.literal("Titans stir"), 0xE03A3A,
                    "minecraft:skeleton_skull", null);
                p.playSoundToPlayer(SoundEvents.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 0.5f, 0.6f);
            }
            if (!poi.done) {
                boolean empty = true;
                for (BlockPos bp : poi.caches) if (w.getBlockEntity(bp) instanceof BarrelBlockEntity b && !b.isEmpty()) empty = false;
                poi.done = empty;
            }
        }
    }

    /** The run as the diver's HUD and island map show it. */
    private void sendView(Run r, ServerPlayerEntity p, long now) {
        if (!ServerPlayNetworking.canSend(p, Net.RunView.ID)) return;
        ServerWorld w = p.getServerWorld();
        BlockPos c = r.session.centre;
        List<Net.RunPoint> pts = new ArrayList<>();
        for (BlockPos e : r.exits) pts.add(new Net.RunPoint("exit", "Flare", e.getX(), e.getZ(), false));
        for (Poi poi : r.session.pois) pts.add(new Net.RunPoint("poi", poi.name, poi.at.getX(), poi.at.getZ(), poi.done));
        for (RunObjectives.Objective o : r.session.objectives) pts.add(new Net.RunPoint("obj", o.name, o.at.getX(), o.at.getZ(), o.done));
        for (UUID id : r.members) {
            ServerPlayerEntity m = server.getPlayerManager().getPlayer(id);
            if (m == null || m == p || m.getWorld() != p.getWorld()) continue;
            pts.add(new Net.RunPoint("mate", AotRpg.PROFILES.get(id).name, m.getBlockX(), m.getBlockZ(), false));
        }
        for (var e : r.marks.entrySet()) {
            boolean mine = e.getKey().equals(p.getUuid());
            pts.add(new Net.RunPoint(mine ? "mymark" : "mark", mine ? "Your mark" : AotRpg.PROFILES.get(e.getKey()).name + "'s mark",
                e.getValue().getX(), e.getValue().getZ(), false));
        }
        List<Net.RunTask> ts = new ArrayList<>();
        for (Task t : r.tasks.getOrDefault(p.getUuid(), List.of())) ts.add(new Net.RunTask(t.text, t.progress, t.goal));
        ts.addAll(RunObjectives.lines(r.session, p));
        int left = (int) Math.max(0, (r.endsAt - now) / 1000);
        int mins = (int) ((now - r.session.startedAt) / 60_000);
        ServerPlayNetworking.send(p, new Net.RunView(r.island.id, r.island.title, r.island.color, c.getX(), c.getZ(), ISLAND, left, mins, pts, ts));
    }

    private static void clearView(ServerPlayerEntity p) {
        if (ServerPlayNetworking.canSend(p, Net.RunView.ID)) {
            ServerPlayNetworking.send(p, new Net.RunView("", "", 0, 0, 0, 0, 0, 0, List.of(), List.of()));
        }
    }

    /**
     * Titans that wander onto an island by any other way (natural spawns) aren't kept. Only whole
     * titans: their eye, nape and hand hitboxes are titan entities of their own, spawned untagged
     * by the titan itself, and removing them left island titans impossible to cut.
     */
    public static boolean stray(Entity e) {
        if (e.getWorld().isClient || Island.of(e.getWorld()) == null) return false;
        // Things lying on an island belong to the match they were dropped in; any other match's
        // (one long over, loaded back from disk) are cleared away.
        if (Troops.is(e)) return !Troops.known(e);
        if (e instanceof net.minecraft.entity.ItemEntity && self != null) {
            String mine = null;
            for (String t : e.getCommandTags()) if (t.startsWith("aot_ses:")) mine = t.substring(8);
            if (mine == null) {
                Session s = self.sessionAt(e.getWorld(), e.getX(), e.getZ());
                if (s == null) return true;
                e.addCommandTag("aot_ses:" + s.id);
                return false;
            }
            for (Session s : self.sessions) if (s.id.equals(mine)) return false;
            return true;
        }
        if (!(e instanceof net.minecraft.entity.mob.MobEntity) || !TitanLevels.root(e) || e.getVehicle() != null) return false;
        return !e.getCommandTags().contains(TITAN);
    }

    /** The flares: tall columns of light over each exit, seen only by the squad. */
    private void flares(Run r, ServerPlayerEntity p, int ticks) {
        ServerWorld w = p.getServerWorld();
        DustParticleEffect red = new DustParticleEffect(new Vector3f(1f, 0.25f, 0.15f), 2.2f);
        for (BlockPos e : r.exits) {
            double x = e.getX() + 0.5, z = e.getZ() + 0.5;
            double d2 = p.squaredDistanceTo(x, e.getY(), z);
            if (d2 > 360 * 360) continue;
            // A light column (fewer, bigger puffs further off), the ring only up close.
            int step = d2 > 120 * 120 ? 8 : 5;
            for (int y = 0; y < 60; y += step) w.spawnParticles(p, red, true, x, e.getY() + y + (ticks % 20) / 10.0, z, 1, 0.15, 0.3, 0.15, 0);
            if (d2 > 48 * 48) continue;
            w.spawnParticles(p, ParticleTypes.FLAME, false, x, e.getY() + 0.3, z, 2, 0.4, 0.1, 0.4, 0.01);
            for (int i = 0; i < 12; i++) {
                double a = i * Math.PI / 6 + ticks * 0.05;
                w.spawnParticles(p, ParticleTypes.END_ROD, false, x + Math.cos(a) * EXIT_RADIUS, e.getY() + 0.2, z + Math.sin(a) * EXIT_RADIUS, 1, 0, 0, 0, 0);
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
        int dir = Math.floorMod((int) Math.round(ang / 45.0), 8);
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
        bump(r, p.getUuid(), "gear", gear);
        RunObjectives.extracted(this, r.session, p);
        int doneTasks = 0;
        for (Task tk : r.tasks.getOrDefault(p.getUuid(), List.of())) if (tk.progress >= tk.goal) doneTasks++;
        long salvage = 25 + gear * 4L + r.island.level() + doneTasks * 15L;
        if (doneTasks > 0) AotRpg.WALLET.addMarks(p, 150L * doneTasks, "run tasks");
        clearView(p);
        Stash.earn(p, salvage);
        AotRpg.TASKS.count(p, "extractions", 1);
        // Lifted off the flare, then back aboard (the balloon's own return shot), then the reward.
        int t = Cinematics.cut(List.of(p), Cinematics.extract(p.getPos()), "", "", 0x5BD35B);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, t + 40, 4, false, false));
        int g = gear;
        AotRpg.SCHEDULER.later(t, () -> {
            if (p.isDisconnected()) return;
            toLobby(p, true);
            Reveal.show(p, "EXTRACTED", r.island.title + "  ·  +" + salvage + " Salvage", "aot_rpg:gas_refueler", g >= 6 ? 4 : g >= 3 ? 3 : 2);
        });
    }

    private void missing(Run r, ServerPlayerEntity p) {
        clearView(p);
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
        // The match goes on while any squad is still out there.
        for (Run o : runs) if (o.session == r.session) return;
        sessions.remove(r.session);
        ServerWorld w = server.getWorld(r.island.world);
        if (w != null) RunObjectives.clear(w, r.session);
        if (w != null) Troops.clear(w, r.session.id);
        // The instance is wiped for the next match: every barrel and block the match placed, and
        // everything left lying about (gear from the fallen, dropped loot) and its titans.
        List<Long> left = data.leftover.getOrDefault(r.island.id, new ArrayList<>());
        BlockPos c = r.session.centre;
        double reach = ISLAND + 200;
        for (var it = left.iterator(); it.hasNext(); ) {
            BlockPos bp = BlockPos.fromLong(it.next());
            if (c != null && bp.getSquaredDistance(c.getX(), bp.getY(), c.getZ()) > reach * reach && !r.barrels.contains(bp)) continue;
            it.remove();
            if (w == null) continue;
            var st = w.getBlockState(bp);
            if (st.isOf(Blocks.BARREL) || st.isOf(Blocks.LODESTONE) || st.isOf(Blocks.CAMPFIRE) || st.isOf(Blocks.CHEST) || st.isOf(Blocks.TARGET)) {
                if (w.getBlockEntity(bp) instanceof net.minecraft.inventory.Inventory b) b.clear();
                w.setBlockState(bp, Blocks.AIR.getDefaultState());
            }
        }
        for (BlockPos bp : r.barrels) {
            if (w != null && w.getBlockState(bp).isOf(Blocks.BARREL)) {
                if (w.getBlockEntity(bp) instanceof BarrelBlockEntity b) b.clear();
                w.setBlockState(bp, Blocks.AIR.getDefaultState());
            }
        }
        if (w != null && c != null) {
            Box box = new Box(c.getX() - reach, w.getBottomY(), c.getZ() - reach, c.getX() + reach, w.getTopY(), c.getZ() + reach);
            for (Entity e : w.getEntitiesByClass(Entity.class, box, e -> e instanceof net.minecraft.entity.ItemEntity
                || e instanceof net.minecraft.entity.projectile.PersistentProjectileEntity || e instanceof net.minecraft.entity.FallingBlockEntity
                || e.getCommandTags().contains(TITAN) || Troops.is(e))) e.discard();
        }
        save();
    }

    /** The match whose instance this spot is in (on its island's world), or null. */
    private Session sessionAt(World w, double x, double z) {
        Island i = Island.of(w);
        if (i == null) return null;
        double reach = ISLAND + 200;
        for (Session s : sessions) {
            if (s.island != i || s.centre == null) continue;
            double dx = x - s.centre.getX(), dz = z - s.centre.getZ();
            if (dx * dx + dz * dz < reach * reach) return s;
        }
        return null;
    }

    /** Someone on a run used an entity (an escort, say). True when handled. */
    public boolean useEntity(ServerPlayerEntity p, Entity e) {
        Run r = runOf(p.getUuid());
        return r != null && RunObjectives.useEntity(r.session, p, e);
    }

    // ------------------------------------------------------------------ falling and watching

    /** A fallen diver watching the match: their match, their squad, who they're watching, their game mode before. */
    private static final class Spec {
        Session session;
        Run squad;
        UUID watching;
        net.minecraft.world.GameMode before;
    }

    private final Map<UUID, Spec> specs = new HashMap<>();
    /** A squad wiped out: whose squad finished them (their spectators watch that squad next). */
    private final Map<Run, Run> finishedBy = new HashMap<>();

    /**
     * A killing blow on a run: no death screen and no respawn. The gear drops where they fell and
     * they're straight into watching their squad. False: the death is handled here.
     */
    public boolean allowDeath(ServerPlayerEntity p, net.minecraft.entity.damage.DamageSource source) {
        Run r = runOf(p.getUuid());
        if (r == null || p.isSpectator()) return true;
        p.setHealth(p.getMaxHealth());
        Run killers = source.getAttacker() instanceof ServerPlayerEntity k ? runOf(k.getUuid()) : null;
        UUID id = p.getUuid();
        // Done a tick later: this may be the end of a bleed-out, in the middle of the downed list.
        AotRpg.SCHEDULER.later(1, () -> {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(id);
            if (pl != null && runOf(id) == r) fall(pl, r, killers);
        });
        return false;
    }

    private void fall(ServerPlayerEntity p, Run r, Run killers) {
        AotRpg.DOWNED.release(p);
        p.setInvulnerable(false);
        // Everything carried drops where they fell (the satchel is always safe).
        AotRpg.LOADOUT.unsheathAll(p);
        p.getInventory().dropAll();
        p.setHealth(p.getMaxHealth());
        p.extinguish();
        p.clearStatusEffects();
        p.fallDistance = 0;
        clearView(p);
        r.members.remove(p.getUuid());
        r.extracting.remove(p.getUuid());
        if (r.members.isEmpty() && killers != null && killers != r) finishedBy.put(r, killers);
        Spec sp = new Spec();
        sp.session = r.session;
        sp.squad = r;
        sp.before = p.interactionManager.getGameMode();
        specs.put(p.getUuid(), sp);
        p.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
        p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_DEATH, SoundCategory.PLAYERS, 1f, 0.8f);
        Titles.show(p, Text.literal("KILLED IN ACTION").formatted(Formatting.DARK_RED, Formatting.BOLD),
            Text.literal("Your gear lies where you fell").formatted(Formatting.RED), 5, 50, 15);
        for (UUID m : r.members) {
            ServerPlayerEntity mate = server.getPlayerManager().getPlayer(m);
            if (mate != null) Notify.toast(mate, Text.literal(AotRpg.PROFILES.get(p.getUuid()).name + " has fallen").formatted(Formatting.RED),
                Text.literal("They're watching you now"), 0xC0463A, "minecraft:skeleton_skull", null);
        }
        retarget(p, sp, 1);
    }

    /** Who this spectator can watch, in order: their squad, then whoever wiped it out, then anyone left. */
    private List<ServerPlayerEntity> watchable(ServerPlayerEntity p, Spec sp) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        Run follow = sp.squad;
        java.util.Set<Run> tried = new HashSet<>();
        while (follow != null && tried.add(follow)) {
            for (UUID m : follow.members) {
                ServerPlayerEntity t = server.getPlayerManager().getPlayer(m);
                if (t != null && t.isAlive() && !t.isSpectator() && t.getWorld() == p.getWorld()) out.add(t);
            }
            if (!out.isEmpty()) return out;
            follow = finishedBy.get(follow);
        }
        for (Run o : runs) {
            if (o.session != sp.session) continue;
            for (UUID m : o.members) {
                ServerPlayerEntity t = server.getPlayerManager().getPlayer(m);
                if (t != null && t.isAlive() && !t.isSpectator() && t.getWorld() == p.getWorld()) out.add(t);
            }
        }
        return out;
    }

    /** Onto the next (dir 1) or previous (-1) player to watch; nobody left and the match is over. */
    private void retarget(ServerPlayerEntity p, Spec sp, int dir) {
        List<ServerPlayerEntity> can = watchable(p, sp);
        if (can.isEmpty()) {
            matchOver(sp.session);
            return;
        }
        int at = -1;
        for (int i = 0; i < can.size(); i++) if (can.get(i).getUuid().equals(sp.watching)) at = i;
        ServerPlayerEntity t = can.get(Math.floorMod(at + dir, can.size()));
        sp.watching = t.getUuid();
        p.setCameraEntity(t);
        sendSpec(p, t, sp);
    }

    private void sendSpec(ServerPlayerEntity p, ServerPlayerEntity t, Spec sp) {
        if (!ServerPlayNetworking.canSend(p, Net.SpecView.ID)) return;
        boolean mine = t != null && sp.squad.members.contains(t.getUuid());
        String who = t == null ? "" : AotRpg.PROFILES.get(t.getUuid()).name;
        ServerPlayNetworking.send(p, new Net.SpecView(t != null, who, mine ? "Your squad" : "The squad that got you"));
    }

    /** Everyone in the match has fallen (or got out): the watchers go back aboard. */
    private void matchOver(Session s) {
        for (var e : new ArrayList<>(specs.entrySet())) {
            if (e.getValue().session != s) continue;
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p != null) {
                stopWatching(p);
                Titles.show(p, Text.literal("MATCH OVER").formatted(Formatting.GOLD, Formatting.BOLD), Text.literal("Nobody's left out there"), 5, 50, 15);
            } else specs.remove(e.getKey());
        }
    }

    /** Back aboard the balloon, in their own game mode again. */
    private void stopWatching(ServerPlayerEntity p) {
        Spec sp = specs.remove(p.getUuid());
        if (sp == null) return;
        p.setCameraEntity(p);
        p.changeGameMode(sp.before == null || sp.before == net.minecraft.world.GameMode.SPECTATOR ? net.minecraft.world.GameMode.SURVIVAL : sp.before);
        if (ServerPlayNetworking.canSend(p, Net.SpecView.ID)) ServerPlayNetworking.send(p, new Net.SpecView(false, "", ""));
        toLobby(p, true);
    }

    public static boolean watching(UUID id) {
        return self != null && self.specs.containsKey(id);
    }

    /** Twice a second: keep each watcher on someone still out there. */
    private void specTick() {
        for (var e : new ArrayList<>(specs.entrySet())) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            Spec sp = e.getValue();
            if (p == null) continue;
            ServerPlayerEntity t = sp.watching == null ? null : server.getPlayerManager().getPlayer(sp.watching);
            boolean ok = t != null && t.isAlive() && !t.isSpectator() && t.getWorld() == p.getWorld() && runOf(t.getUuid()) != null;
            if (!ok) retarget(p, sp, 1);
            else if (p.getCameraEntity() != t) p.setCameraEntity(t);
        }
    }

    /** A watcher's controls: next, previous, or back to the balloon. */
    public void specAction(ServerPlayerEntity p, String action) {
        Spec sp = specs.get(p.getUuid());
        if (sp == null) {
            TeamWatch.action(p, action);
            return;
        }
        switch (action) {
            case "spec_next" -> retarget(p, sp, 1);
            case "spec_prev" -> retarget(p, sp, -1);
            case "spec_leave" -> stopWatching(p);
            default -> { }
        }
    }

    /** Died out there: the gear stays where they fell, and they wake in the balloon. */
    /** Fallen divers: where they fell (world and spot), waiting for their respawn. */
    private final Map<UUID, Object[]> fallen = new HashMap<>();
    /** Those watching the island from above after falling: their game mode before, to give back. */
    private final Map<UUID, net.minecraft.world.GameMode> watching = new HashMap<>();

    public void onDeath(ServerPlayerEntity p) {
        Run r = runOf(p.getUuid());
        if (r == null) return;
        clearView(p);
        r.members.remove(p.getUuid());
        r.extracting.remove(p.getUuid());
        fallen.put(p.getUuid(), new Object[] {p.getServerWorld().getRegistryKey(), p.getPos()});
    }

    /**
     * After a respawn: a calm last look. Set down (as a spectator) over where they fell, the camera
     * drifts slowly up and away over the island, "KILLED IN ACTION", then back aboard the balloon.
     * Nothing moves them during the respawn itself (a change of world right then is unsafe).
     */
    @SuppressWarnings("unchecked")
    public boolean respawn(ServerPlayerEntity p) {
        Object[] at = fallen.remove(p.getUuid());
        if (at == null) {
            Profile pr = AotRpg.PROFILES.get(p.getUuid());
            if (!pr.inRun && Island.of(p.getWorld()) == null) return false;
            // Died on a run we no longer know about (a restart between): aboard, a moment after the respawn.
            UUID id = p.getUuid();
            AotRpg.SCHEDULER.later(3, () -> {
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(id);
                if (pl != null && pl.isAlive()) toLobby(pl, true);
            });
            return true;
        }
        UUID id = p.getUuid();
        AotRpg.SCHEDULER.later(2, () -> {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(id);
            if (pl == null) return;
            ServerWorld w = server.getWorld((net.minecraft.registry.RegistryKey<World>) at[0]);
            Vec3d spot = (Vec3d) at[1];
            if (w == null) {
                toLobby(pl, true);
                return;
            }
            watching.put(id, pl.interactionManager.getGameMode());
            pl.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
            pl.teleport(w, spot.x, spot.y + 3, spot.z, pl.getYaw(), 60);
            int t = Cinematics.cut(List.of(pl), Cinematics.fallen(spot), "Killed in action", "", 0xA02020);
            pl.playSoundToPlayer(SoundEvents.BLOCK_BELL_RESONATE, SoundCategory.AMBIENT, 0.6f, 0.5f);
            AotRpg.SCHEDULER.later(t, () -> {
                ServerPlayerEntity back = server.getPlayerManager().getPlayer(id);
                net.minecraft.world.GameMode mode = watching.remove(id);
                if (back == null) return;
                back.changeGameMode(mode == null || mode == net.minecraft.world.GameMode.SPECTATOR ? net.minecraft.world.GameMode.SURVIVAL : mode);
                toLobby(back, true);
            });
        });
        return true;
    }

    /** Divers whose run ran out while they were logged out: missing in action when they return. */
    private final Set<UUID> missedOffline = new HashSet<>();

    /**
     * Coming back (a disconnect, a crash, a restart): put them back where they were if that still
     * makes sense, otherwise somewhere safe in their game mode.
     *   - still in a run: right where they logged out (or dropped onto the island if they left
     *     mid-deploy), the run's HUD back up;
     *   - their run ran out while away: missing in action, back aboard;
     *   - a run that no longer exists (a restart), or stranded on an island: back aboard;
     *   - Extraction mode anywhere else: aboard their balloon; Open World mode but aboard: back to
     *     where they were in the open world.
     * The dead are left to the respawn, which does the same once they're up.
     */
    public void joined(ServerPlayerEntity p) {
        if (!p.isAlive()) return;
        UUID id = p.getUuid();
        // Was watching the match when they left (or the server went down): back aboard, themselves again.
        if (specs.containsKey(id)) {
            stopWatching(p);
            return;
        }
        if (p.isSpectator() && Island.of(p.getWorld()) != null && !p.hasPermissionLevel(2)) p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
        Profile pr = AotRpg.PROFILES.get(id);
        if (missedOffline.remove(id)) {
            Leavers.record(p);
            p.getInventory().clear();
            toLobby(p, true);
            Titles.show(p, Text.literal("MISSING IN ACTION").formatted(Formatting.DARK_RED, Formatting.BOLD),
                Text.literal("The run ended while you were away").formatted(Formatting.RED), 10, 70, 20);
            return;
        }
        Run r = runOf(id);
        if (r != null) {
            ServerWorld iw = server.getWorld(r.island.world);
            if (iw == null) {
                r.members.remove(id);
                toLobby(p, true);
                return;
            }
            // Asked first: back into the match, or leave it. Safe (and out of the way) while they choose.
            Rejoining q = new Rejoining();
            q.run = r;
            q.before = p.interactionManager.getGameMode();
            q.deadline = System.currentTimeMillis() + 45_000;
            rejoining.put(id, q);
            p.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
            long left = Math.max(0, (r.endsAt - System.currentTimeMillis()) / 60_000);
            if (ServerPlayNetworking.canSend(p, Net.Rejoin.ID)) {
                ServerPlayNetworking.send(p, new Net.Rejoin("run", r.island.title, "Your squad's run is still going · about " + left + " min left", 45));
            } else {
                rejoin(p, true);
            }
            return;
        }
        if (pr.inRun || Island.of(p.getWorld()) != null) {
            toLobby(p, true);
            return;
        }
        if (DeathCare.EXTRACTION.equals(pr.mode)) toLobby(p);
        else if (inLobby(p)) toOpenWorld(p);
    }

    private static final class Rejoining {
        Run run;
        net.minecraft.world.GameMode before;
        long deadline;
    }

    /** Divers back from a dropped connection, deciding whether to rejoin. */
    private final Map<UUID, Rejoining> rejoining = new HashMap<>();

    /** Their answer (or the clock running out, which rejoins them). */
    public boolean rejoin(ServerPlayerEntity p, boolean yes) {
        UUID id = p.getUuid();
        Rejoining q = rejoining.remove(id);
        if (q == null) return false;
        p.changeGameMode(q.before == null || q.before == net.minecraft.world.GameMode.SPECTATOR ? net.minecraft.world.GameMode.SURVIVAL : q.before);
        Run r = q.run;
        if (!runs.contains(r) || !r.members.contains(id)) {
            toLobby(p, true);
            return true;
        }
        if (!yes) {
            // Walking out on the squad: MIA, everything carried is lost.
            r.members.remove(id);
            r.extracting.remove(id);
            clearView(p);
            p.getInventory().clear();
            toLobby(p, true);
            Leavers.record(p);
            for (UUID m : r.members) {
                ServerPlayerEntity mate = server.getPlayerManager().getPlayer(m);
                if (mate != null) Notify.toast(mate, Text.literal(AotRpg.PROFILES.get(id).name + " left the run").formatted(Formatting.GRAY), null, 0x8F8A7A, null, null);
            }
            return true;
        }
        ServerWorld iw = server.getWorld(r.island.world);
        if (iw == null) {
            toLobby(p, true);
            return true;
        }
        {
            if (p.getWorld() != iw) {
                // Left in the middle of the drop: they land now.
                BlockPos c = r.session.centre;
                BlockPos at = land(iw, c.getX(), c.getZ(), p.getRandom(), 200);
                if (at == null) at = new BlockPos(c.getX(), iw.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()), c.getZ());
                p.stopRiding();
                p.teleport(iw, at.getX() + 0.5, at.getY() + 40, at.getZ() + 0.5, p.getYaw(), 40);
                p.fallDistance = 0;
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 20 * 12, 0, false, false));
            }
            Notify.toast(p, Text.literal("Back in the run").formatted(Formatting.GOLD, Formatting.BOLD), Text.literal(r.island.title), r.island.color, "minecraft:compass", null);
            sendView(r, p, System.currentTimeMillis());
        }
        return true;
    }

    /** Logging out: a diver stays in their run (the clock keeps going), everything else lets go. */
    public void forget(ServerPlayerEntity p) {
        Run r = runOf(p.getUuid());
        if (r != null) r.extracting.remove(p.getUuid());
        // Dropped again while deciding: themselves again (they'll be asked next time).
        Rejoining q = rejoining.remove(p.getUuid());
        if (q != null) p.changeGameMode(q.before == null || q.before == net.minecraft.world.GameMode.SPECTATOR ? net.minecraft.world.GameMode.SURVIVAL : q.before);
        // Logging out while watching the fall: their own game mode back, aboard next time.
        net.minecraft.world.GameMode mode = watching.remove(p.getUuid());
        if (mode != null) {
            p.changeGameMode(mode == net.minecraft.world.GameMode.SPECTATOR ? net.minecraft.world.GameMode.SURVIVAL : mode);
            AotRpg.PROFILES.get(p.getUuid()).inRun = true;
        }
        walkingTo.remove(p.getUuid());
        sitBy.remove(p.getUuid());
        p.stopRiding();
    }

    // ------------------------------------------------------------------ lobby actions

    public void action(ServerPlayerEntity p, String action, String arg) {
        if (action.startsWith("spec_")) {
            specAction(p, action);
            return;
        }
        if (action.equals("rejoin_yes") || action.equals("rejoin_no")) {
            if (!rejoin(p, action.equals("rejoin_yes"))) AotRpg.RAID_BOSSES.rejoin(p, action.equals("rejoin_yes"));
            return;
        }
        if (action.equals("mark") || action.equals("unmark")) {
            mark(p, action, arg);
            return;
        }
        if (!inLobby(p)) return;
        int slot = slotAt(p.getX());
        Lobby lb = lobby(slot);
        boolean lead = p.getUuid().equals(leader(slot));
        switch (action) {
            case "open" -> sendLobby(p);
            case "ready" -> {
                if (!lb.ready.remove(p.getUuid())) {
                    if (!p.hasVehicle()) {
                        BlockPos seat = freeSeat(slot, p);
                        if (seat != null) sitOn(p, seat);
                    }
                    lb.ready.add(p.getUuid());
                    p.playSoundToPlayer(SoundEvents.ITEM_ARMOR_EQUIP_IRON.value(), SoundCategory.PLAYERS, 0.8f, 1.1f);
                }
                broadcast(slot);
            }
            case "island" -> {
                if (lead && Island.of(arg) != null && lb.countdownAt == 0) {
                    lb.island = arg;
                    broadcast(slot);
                }
            }
            case "fill" -> {
                if (lead && lb.countdownAt == 0) {
                    lb.fill = !lb.fill;
                    broadcast(slot);
                }
            }
            case "walk" -> {
                lb.ready.remove(p.getUuid());
                stand(p);
                broadcast(slot);
            }
            case "station" -> {
                // A station clicked (sent straight from the client, so it works whatever the reach check thinks).
                try {
                    BlockPos pos = BlockPos.fromLong(Long.parseLong(arg));
                    if (inLobby(pos) && p.squaredDistanceTo(Vec3d.ofCenter(pos)) < 10 * 10) use(p, pos);
                } catch (NumberFormatException ignored) { }
            }
            case "leave" -> {
                lb.ready.remove(p.getUuid());
                AotRpg.MODES.choose(p, DeathCare.STORY);
            }
            case "stash" -> Stash.show(p, 0);
            case "stash_page" -> {
                try {
                    Stash.show(p, Integer.parseInt(arg));
                } catch (NumberFormatException ignored) { }
            }
            case "expand" -> {
                Stash.expand(p);
                sendLobby(p);
            }
            case "bench" -> craft(p, arg);
            case "bag" -> AotRpg.SATCHEL.send(p, false);
            case "invite", "friend" -> {
                UUID id;
                try {
                    id = UUID.fromString(arg);
                } catch (Exception e) {
                    return;
                }
                if (action.equals("friend")) AotRpg.SOCIAL.action(p, "friend_add", id);
                else {
                    ServerPlayerEntity t = server.getPlayerManager().getPlayer(id);
                    if (t == null) return;
                    AotRpg.PARTIES.invite(p, t);
                    Notify.toast(t, Text.literal(pr(p) + " wants you on their balloon").formatted(Formatting.GOLD),
                        Text.literal("Accept the party invite to board"), 0xE0B96A, "minecraft:paper", null);
                }
                sendLobby(p);
            }
            default -> { }
        }
    }
}
