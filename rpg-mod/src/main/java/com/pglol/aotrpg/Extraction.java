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

    private static final int BALLOON_VERSION = 1;
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
    private static final BlockPos BOARD = new BlockPos(4, 1, 0), STASH = new BlockPos(-4, 1, 0), FUEL = new BlockPos(-4, 1, 2);
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
            w.setBlockState(o.add(-4, 1, -2), Blocks.BARREL.getDefaultState().with(net.minecraft.block.BarrelBlock.FACING, Direction.UP), f);
            w.setBlockState(o.add(4, 1, 2), Blocks.CARTOGRAPHY_TABLE.getDefaultState(), f);
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
        p.teleport(w, o.getX() + 0.5, o.getY() + 1, o.getZ() + 0.5, 0, 0);
        p.playSoundToPlayer(SoundEvents.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.AMBIENT, 0.5f, 0.6f);
        // Aboard: a seat on the benches, a sweep round the balloon, and the squad sees you climb in.
        AotRpg.SCHEDULER.later(2, () -> {
            if (p.isDisconnected() || !inLobby(p)) return;
            sit(p);
            List<ServerPlayerEntity> aboard = aboard(slot);
            Cinematics.intro(List.of(p), new Vec3d(o.getX() + 0.5, o.getY() + 1.5, o.getZ() + 0.5), aboard, "ALOFT",
                aboard.size() > 1 ? aboard.size() + " aboard" : "Waiting to drop", 0xE0B96A);
            for (ServerPlayerEntity m : aboard) {
                if (m == p) continue;
                Notify.toast(m, Text.literal(pr.name + " climbs aboard").formatted(Formatting.GOLD), null, 0xE0B96A, "minecraft:ladder", null);
                m.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_ATTACK_WEAK, SoundCategory.PLAYERS, 0.6f, 0.8f);
            }
        });
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

    /** The balloon's blocks: the board opens deployment, the ender chest the stash, a bench seats you. */
    public boolean use(ServerPlayerEntity p, BlockPos pos) {
        if (p.getWorld().getRegistryKey() != Homes.WORLD || !inLobby(pos)) {
            Run r = runOf(p.getUuid());
            if (r != null && r.barrels.contains(pos) && p.getServerWorld().getBlockEntity(pos) instanceof BarrelBlockEntity b) {
                p.openHandledScreen(b);
                return true;
            }
            return false;
        }
        BlockPos rel = pos.subtract(origin(slotAt(pos.getX())));
        if (rel.equals(BOARD)) {
            send(p, true);
            return true;
        }
        if (rel.equals(STASH)) {
            Stash.show(p, 0);
            return true;
        }
        if (rel.equals(FUEL)) return false;
        if (p.getWorld().getBlockState(pos).getBlock() instanceof StairsBlock) {
            p.stopRiding();
            sitOn(p, pos);
        }
        return true;
    }

    // ------------------------------------------------------------------ runs

    private Run runOf(UUID id) {
        for (Run r : runs) if (r.members.contains(id)) return r;
        return null;
    }

    public static boolean inRun(UUID id) {
        return self != null && self.runOf(id) != null;
    }

    public void deploy(ServerPlayerEntity p, String islandId) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!DeathCare.EXTRACTION.equals(pr.mode) || !inLobby(p) || runOf(p.getUuid()) != null) return;
        Parties.Party party = AotRpg.PARTIES.of(p.getUuid());
        if (party != null && !p.getUuid().equals(party.leader)) {
            Notify.toast(p, Text.literal("Your squad leader deploys").formatted(Formatting.GOLD), null, 0xE0B96A);
            return;
        }
        Island island = Island.of(islandId);
        ServerWorld iw = island == null ? null : server.getWorld(island.world);
        if (iw == null) {
            Notify.toast(p, Text.literal("That island isn't loaded").formatted(Formatting.RED), null, 0xC0463A);
            return;
        }
        int slot = slotAt(p.getX());
        List<ServerPlayerEntity> squad = new ArrayList<>();
        squad.add(p);
        for (ServerPlayerEntity m : aboard(slot)) {
            if (m != p && DeathCare.EXTRACTION.equals(AotRpg.PROFILES.get(m.getUuid()).mode) && runOf(m.getUuid()) == null
                && (party == null ? false : party.members.contains(m.getUuid()))) squad.add(m);
        }
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
            AotRpg.PROFILES.save(m.getUuid());
        }
        // The jump: a sweep round the balloon with the island's name across it, then over the side.
        BlockPos o = origin(slot);
        int ticks = Cinematics.intro(squad, new Vec3d(o.getX() + 0.5, o.getY() + 1.5, o.getZ() + 0.5), squad, "DEPLOYING", island.title, island.color & 0xFFFFFF);
        BlockPos at = drop;
        AotRpg.SCHEDULER.later(ticks + 5, () -> {
            int k = 0;
            for (ServerPlayerEntity m : squad) {
                if (m.isDisconnected() || !run.members.contains(m.getUuid())) continue;
                double ox = (k % 3 - 1) * 3, oz = (k / 3) * 3;
                k++;
                m.stopRiding();
                // Sky-diving in, slowed near the canopy.
                m.teleport(iw, at.getX() + 0.5 + ox, at.getY() + 90, at.getZ() + 0.5 + oz, m.getYaw(), 40);
                m.fallDistance = 0;
                m.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 20 * 14, 0, false, false));
                Titles.show(m, Text.literal(island.title.toUpperCase()).styled(s -> s.withColor(island.color & 0xFFFFFF).withBold(true)),
                    Text.literal("Find the flares").formatted(Formatting.GOLD), 8, 60, 20);
                m.playSoundToPlayer(SoundEvents.ITEM_ELYTRA_FLYING, SoundCategory.MASTER, 0.6f, 1f);
                m.playSoundToPlayer(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.MASTER, 0.4f, 1.4f);
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
        lobbyTick(ticks);
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
        toLobby(p);
        Reveal.show(p, "EXTRACTED", r.island.title + "  ·  +" + salvage + " Salvage", "aot_rpg:gas_refueler", gear >= 6 ? 4 : gear >= 3 ? 3 : 2);
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
        p.stopRiding();
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
        for (Island i : Island.values()) {
            if (server.getWorld(i.world) != null) zs.add(new Net.ExtractionZone(i.id, i.title, i.min, i.max, i.titans));
        }
        List<String> squad = new ArrayList<>();
        Parties.Party party = AotRpg.PARTIES.of(p.getUuid());
        boolean leader = party == null || p.getUuid().equals(party.leader);
        for (ServerPlayerEntity m : aboard(slotAt(p.getX()))) squad.add(AotRpg.PROFILES.get(m.getUuid()).name);
        int[] use = Stash.usage(p);
        ServerPlayNetworking.send(p, new Net.ExtractionView(zs, squad, leader, pr.salvage, use[0], use[1],
            pr.stashRows >= Stash.MAX_ROWS ? -1 : Stash.rowCost(pr), open));
    }
}
