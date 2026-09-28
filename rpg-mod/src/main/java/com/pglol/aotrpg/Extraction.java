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
    private static final int EXTRACT_TICKS = 160, EXIT_RADIUS = 4, BARRELS = 12, ISLAND = 600;
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
        final List<BlockPos> exits = new ArrayList<>();
        final List<BlockPos> barrels = new ArrayList<>();
        final Map<UUID, Integer> extracting = new HashMap<>();
    }

    static final class Data {
        int balloonVersion;
        Set<Integer> built = new HashSet<>();
        /** Island centres, found once: the most land-rich spot near the origin. */
        Map<String, int[]> centres = new HashMap<>();
        /** Barrels placed by runs, per island (cleared if a restart cut a run short). */
        Map<String, List<Long>> leftover = new HashMap<>();
    }

    private static final int BALLOON_VERSION = 3;
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
        if (data.balloonVersion != BALLOON_VERSION) data.built.clear();
        data.balloonVersion = BALLOON_VERSION;
        for (Island i : Island.values()) {
            ServerWorld w = server.getWorld(i.world);
            if (w == null) continue;
            for (long l : data.leftover.getOrDefault(i.id, List.of())) {
                BlockPos bp = BlockPos.fromLong(l);
                if (w.getBlockState(bp).isOf(Blocks.BARREL)) {
                    if (w.getBlockEntity(bp) instanceof BarrelBlockEntity b) b.clear();
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

    /** The island's centre: the spot with the most land around it, found from the terrain noise once. */
    private BlockPos centre(Island i, ServerWorld w) {
        int[] c = data.centres.get(i.id);
        if (c == null) {
            var gen = w.getChunkManager().getChunkGenerator();
            var noise = w.getChunkManager().getNoiseConfig();
            int sea = w.getSeaLevel(), bx = 0, bz = 0, best = -1;
            for (int gx = -5; gx <= 5; gx++) {
                for (int gz = -5; gz <= 5; gz++) {
                    int cx = gx * 480, cz = gz * 480, land = 0;
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
            data.centres.put(i.id, c);
            save();
            AotRpg.LOG.info("Extraction island {} centred at {}, {}", i.id, bx, bz);
        }
        return new BlockPos(c[0], 0, c[1]);
    }

    /** Which level titans on this island are (null world or off the islands: the open world's level). */
    public static int levelIn(World w, double x, double z) {
        Island i = w == null ? null : Island.of(w);
        return i != null ? i.level() : AotRpg.PLACES.levelAt(x, z);
    }

    /** Solid, dry ground near x, z (tries a few spots around it), or null. */
    private static BlockPos land(ServerWorld w, int x, int z, Random r, int spread) {
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

    private ServerWorld sky() {
        return AotRpg.HOMES.stashWorld();
    }

    private static BlockPos origin(int slot) {
        return LOBBY.add(-slot * SLOT, 0, 0);
    }

    private static int slotAt(double x) {
        return (int) Math.round((LOBBY.getX() - x) / SLOT);
    }

    public static boolean inLobby(ServerPlayerEntity p) {
        return p.getWorld().getRegistryKey() == Homes.WORLD && p.getX() < LOBBY.getX() + SLOT / 2.0;
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
            var d = EntityType.TEXT_DISPLAY.create(w);
            if (d == null) continue;
            net.minecraft.nbt.NbtCompound tag = new net.minecraft.nbt.NbtCompound();
            tag.putString("text", "{\"text\":\"" + l[1] + "\",\"color\":\"" + l[2] + "\",\"bold\":true}");
            tag.putString("billboard", "center");
            tag.putInt("background", 0x60000000);
            d.readNbt(tag);
            d.refreshPositionAndAngles(b.getX() + 0.5, b.getY() + 1.35, b.getZ() + 0.5, 0, 0);
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
            ServerPlayerEntity lead = server.getPlayerManager().getPlayer(party.leader);
            if (lead != null && lead != p && inLobby(lead)) return slotAt(lead.getX());
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
        Direction face = w.getBlockState(seat).get(StairsBlock.FACING).getOpposite();
        Vec3d facing = new Vec3d(face.getOffsetX(), 0, face.getOffsetZ());
        List<Net.Shot> shots = Cinematics.boarding(Vec3d.ofBottomCenter(o.up()), Vec3d.ofBottomCenter(seat), facing, returning);
        List<ServerPlayerEntity> aboard = aboard(slot);
        String sub = returning ? "Back aboard" : aboard.size() > 1 ? aboard.size() + " aboard" : "Waiting to drop";
        int t = Cinematics.play(List.of(p), shots, returning ? "HOME AIR" : "ALOFT", sub, 0xE0B96A);
        walkingTo.put(p.getUuid(), seat);
        sitBy.put(p.getUuid(), ticks + Math.max(20, t - 76));
        // The walk starts in time to sit down under the last shot.
        if (ServerPlayNetworking.canSend(p, Net.Autopilot.ID)) {
            ServerPlayNetworking.send(p, new Net.Autopilot(seat.getX() + 0.5, seat.getY(), seat.getZ() + 0.5, Math.max(0, (t - 120) * 50)));
        }
        for (ServerPlayerEntity m : aboard) {
            if (m == p) continue;
            Notify.toast(m, Text.literal(pr.name + " climbs aboard").formatted(Formatting.GOLD), null, 0xE0B96A, "minecraft:ladder", null);
            m.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_ATTACK_WEAK, SoundCategory.PLAYERS, 0.6f, 0.8f);
        }
        broadcast(slot);
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
        for (ServerPlayerEntity o : w.getPlayers()) if (inLobby(o) && slotAt(o.getX()) == slot) out.add(o);
        return out;
    }

    /** Seats a player on the nearest free bench spot. */
    private void sit(ServerPlayerEntity p) {
        ServerWorld w = sky();
        if (w == null || p.hasVehicle()) return;
        BlockPos o = origin(slotAt(p.getX()));
        List<BlockPos> spots = new ArrayList<>();
        for (int x = -3; x <= 3; x++) {
            spots.add(o.add(x, 1, -BASKET + 1));
            spots.add(o.add(x, 1, BASKET - 1));
        }
        spots.sort((a, b) -> Double.compare(p.squaredDistanceTo(Vec3d.ofCenter(a)), p.squaredDistanceTo(Vec3d.ofCenter(b))));
        for (BlockPos s : spots) {
            if (sitOn(p, s)) return;
        }
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
        p.setYaw(face.asRotation());
        p.setHeadYaw(face.asRotation());
        return true;
    }

    /** The balloon breathes: the burner roars now and then, clouds stream past, empty seats go. */
    private void lobbyTick(int ticks) {
        ServerWorld w = sky();
        if (w == null) return;
        Map<Integer, List<ServerPlayerEntity>> bySlot = new HashMap<>();
        for (ServerPlayerEntity p : w.getPlayers()) {
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
            for (Entity e : w.iterateEntities()) {
                if (e instanceof ArmorStandEntity a && a.getCommandTags().contains(SEAT) && !a.hasPassengers()) a.discard();
            }
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
        if (p.getWorld().getRegistryKey() != Homes.WORLD || !inLobby(pos)) {
            Run r = runOf(p.getUuid());
            if (r != null && r.barrels.contains(pos) && p.getServerWorld().getBlockEntity(pos) instanceof BarrelBlockEntity b) {
                p.openHandledScreen(b);
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
        if (seat != null) {
            p.stopRiding();
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
        p.getInventory().offerOrDrop(new ItemStack(item, k.count()));
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
        BlockPos c = centre(island, iw);
        Run run = new Run();
        run.island = island;
        run.endsAt = System.currentTimeMillis() + RUN_MS + 15_000;
        double base = rnd.nextDouble() * Math.PI * 2;
        BlockPos drop = land(iw, c.getX() + (int) (Math.cos(base) * 260), c.getZ() + (int) (Math.sin(base) * 260), rnd, 60);
        if (drop == null) drop = land(iw, c.getX(), c.getZ(), rnd, 120);
        if (drop == null) drop = new BlockPos(c.getX(), iw.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()), c.getZ());
        // The exits, round the far side of the island from the drop.
        for (int i = 0; i < 3; i++) {
            double a = base + Math.PI + (i - 1) * 1.0 + (rnd.nextDouble() - 0.5) * 0.4;
            double d = 230 + rnd.nextInt(170);
            BlockPos e = land(iw, c.getX() + (int) (Math.cos(a) * d), c.getZ() + (int) (Math.sin(a) * d), rnd, 40);
            if (e != null) run.exits.add(e);
        }
        if (run.exits.isEmpty()) run.exits.add(drop);
        // Supply barrels through the jungle (or the crags, or the snow).
        List<Long> left = data.leftover.computeIfAbsent(island.id, k -> new ArrayList<>());
        for (int i = 0; i < BARRELS; i++) {
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
        int ticks = Cinematics.play(squad, Cinematics.drop(Vec3d.ofBottomCenter(o)), "DROP", island.title, island.color & 0xFFFFFF);
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
            if (!landed.isEmpty()) {
                Vec3d ground = Vec3d.ofBottomCenter(at);
                Cinematics.play(landed, Cinematics.landing(ground, ground.add(0, 44, 0)), island.title.toUpperCase(), "Find the flares", island.color & 0xFFFFFF);
            }
        });
    }

    /** A supply barrel: gear for the island's level, gas, blades, and now and then a refueler. */
    private static void fill(BarrelBlockEntity b, int level, Random r) {
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
        lobbyTick(ticks);
        if (ticks % 10 == 0) lobbyState();
        islandTick(ticks);
        if (runs.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Run r : new ArrayList<>(runs)) {
            ServerWorld iw = server.getWorld(r.island.world);
            for (UUID id : new ArrayList<>(r.members)) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                if (p == null) continue;
                if (now > r.endsAt) {
                    missing(r, p);
                    continue;
                }
                if (p.getWorld() != iw) continue;
                if (ticks % 10 == 0) flares(r, p, ticks);
                if (ticks % 20 == 0) shore(r, p, iw);
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

    /** The island's edge: the sea mist turns you back. */
    private void shore(Run r, ServerPlayerEntity p, ServerWorld iw) {
        BlockPos c = centre(r.island, iw);
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
            for (ServerPlayerEntity p : w.getPlayers()) {
                if (runOf(p.getUuid()) != null) divers.add(p);
                else if (ticks % 100 == 0 && !p.isSpectator() && !p.isCreative()) toLobby(p);
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
                w.spawnParticles(island.aura(), t.getX(), t.getY() + t.getHeight() * 0.6, t.getZ(), 6,
                    t.getWidth() * 0.4, t.getHeight() * 0.3, t.getWidth() * 0.4, 0);
            }
            if (divers.isEmpty() || ticks % 200 != 0) continue;
            int cap = Math.min(8, island.titans + divers.size());
            if (titans.size() >= cap) continue;
            ServerPlayerEntity p = divers.get(w.getRandom().nextInt(divers.size()));
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

    /** Titans that wander onto an island by any other way (natural spawns) aren't kept. */
    public static boolean stray(Entity e) {
        if (e.getWorld().isClient || Island.of(e.getWorld()) == null) return false;
        return AotRpg.isTitan(e) && !e.getCommandTags().contains(TITAN);
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
        long salvage = 25 + gear * 4L + r.island.level();
        Stash.earn(p, salvage);
        AotRpg.TASKS.count(p, "extractions", 1);
        // Lifted off the flare, then back aboard (the balloon's own return shot), then the reward.
        int t = Cinematics.play(List.of(p), Cinematics.extract(p.getPos()), "EXTRACTED", r.island.title, 0x5BD35B);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, t + 40, 4, false, false));
        int g = gear;
        AotRpg.SCHEDULER.later(t, () -> {
            if (p.isDisconnected()) return;
            toLobby(p, true);
            Reveal.show(p, "EXTRACTED", r.island.title + "  ·  +" + salvage + " Salvage", "aot_rpg:gas_refueler", g >= 6 ? 4 : g >= 3 ? 3 : 2);
        });
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
        ServerWorld w = server.getWorld(r.island.world);
        List<Long> left = data.leftover.getOrDefault(r.island.id, new ArrayList<>());
        for (BlockPos bp : r.barrels) {
            if (w != null && w.getBlockState(bp).isOf(Blocks.BARREL)) {
                if (w.getBlockEntity(bp) instanceof BarrelBlockEntity b) b.clear();
                w.setBlockState(bp, Blocks.AIR.getDefaultState());
            }
            left.remove(bp.asLong());
        }
        save();
    }

    /** Died out there: the gear stays where they fell, and they wake in the balloon. */
    private final Set<UUID> fallen = new HashSet<>();

    public void onDeath(ServerPlayerEntity p) {
        Run r = runOf(p.getUuid());
        if (r == null) return;
        r.members.remove(p.getUuid());
        r.extracting.remove(p.getUuid());
        fallen.add(p.getUuid());
    }

    /** After a respawn: true when they were sent back to the balloon. */
    public boolean respawn(ServerPlayerEntity p) {
        if (!fallen.remove(p.getUuid())) return false;
        toLobby(p);
        Titles.show(p, Text.literal("KILLED IN ACTION").formatted(Formatting.DARK_RED, Formatting.BOLD),
            Text.literal("Your gear lies where you fell").formatted(Formatting.RED), 10, 60, 20);
        return true;
    }

    /** Joining: someone who logged out mid-run (or whose run a restart ended) starts in the balloon. */
    public void joined(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (pr.inRun && runOf(p.getUuid()) == null || inLobby(p)) toLobby(p);
    }

    /** Leaving mid-run takes you out of it (you'll be back in the balloon next time, with what you carry). */
    public void forget(ServerPlayerEntity p) {
        Run r = runOf(p.getUuid());
        if (r != null) {
            r.members.remove(p.getUuid());
            r.extracting.remove(p.getUuid());
        }
        fallen.remove(p.getUuid());
        walkingTo.remove(p.getUuid());
        sitBy.remove(p.getUuid());
        p.stopRiding();
    }

    // ------------------------------------------------------------------ lobby actions

    public void action(ServerPlayerEntity p, String action, String arg) {
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
                p.stopRiding();
                broadcast(slot);
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
