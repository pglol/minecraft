package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.block.LeveledCauldronBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Your estate: projects built on your property by hired builders, block by block while you
 * watch (walls against home raids in three tiers, a crop field, an animal pen), the produce they
 * bring in, and pets: they wait at home, and one can come along on your adventures.
 */
public final class Estate {
    public record Project(String id, String title, String desc, long price, String needs) { }

    public static final List<Project> PROJECTS = List.of(
        new Project("FORT1", "Palisade", "A log palisade around your land. Home raids: one titan fewer, and they're slowed at the wall.", 3000, ""),
        new Project("FORT2", "Stone Wall", "Stone walls with battlements replace the palisade. Raids: two fewer titans, slowed and weakened.", 8000, "FORT1"),
        new Project("FORT3", "Fortress", "Four corner watchtowers and a bell. Raids: three fewer, and you're warned early.", 16000, "FORT2"),
        new Project("FARM", "Crop Field", "Tilled rows of wheat, carrots and potatoes. Brings in food every half hour.", 2500, ""),
        new Project("PEN", "Animal Pen", "A fenced pen with cows, sheep and chickens. Brings in milk, wool, eggs and meat.", 3500, ""));

    public record Pet(String id, String name, String desc, long price, EntityType<?> type) { }

    public static final List<Pet> PETS = List.of(
        new Pet("rabbit", "Rabbit", "A quick little companion.", 800, EntityType.RABBIT),
        new Pet("frog", "Frog", "Croaks at titans (quietly).", 1000, EntityType.FROG),
        new Pet("cat", "Barracks Cat", "Keeps the mice out of the rations.", 1200, EntityType.CAT),
        new Pet("hound", "Scout Hound", "Loyal, fearless, good nose.", 1500, EntityType.WOLF),
        new Pet("bee", "Honey Bee", "Buzzes about your head.", 1800, EntityType.BEE),
        new Pet("parrot", "Parrot", "Rides on your shoulder.", 2000, EntityType.PARROT),
        new Pet("fox", "Fox", "Sly and handsome.", 2500, EntityType.FOX),
        new Pet("panda", "Baby Panda", "From across the sea. Very round.", 4000, EntityType.PANDA),
        new Pet("allay", "Allay", "A floating spirit that hums along.", 6000, EntityType.ALLAY));

    private static final String BUILD_TAG = "aot_builder", PEN_TAG = "aot_pen", HOME_PET = "aot_homepet:", COMPANION = "aot_companion:";

    private record Place(BlockPos pos, BlockState state) { }

    private static final class Job {
        int plot;
        String project;
        List<Place> todo;
        int total, done;
        UUID builder;
    }

    private final Map<Integer, Job> jobs = new HashMap<>();
    private MinecraftServer server;

    public void open(MinecraftServer server) {
        this.server = server;
        jobs.clear();
    }

    private static Homes.PlotDeed deed(int idx) {
        return AotRpg.HOMES.data.plots.get(idx);
    }

    /** At home: on your own property's land, or inside your own town house. */
    public static boolean atHome(ServerPlayerEntity p) {
        return AotRpg.HOMES.canBuild(p, p.getBlockPos()) || (p.getWorld() == p.getServer().getOverworld() && HomePlots.ownsAt(p, p.getBlockPos()));
    }

    static int ownPlot(ServerPlayerEntity p) {
        String stem = AotRpg.HOMES.stem(p);
        for (var e : AotRpg.HOMES.data.plots.entrySet()) if (stem.equals(e.getValue().stem) && e.getKey() < AotRpg.PLACES.plots.size()) return e.getKey();
        return -1;
    }

    private static boolean built(Homes.PlotDeed d, String id) {
        return switch (id) {
            case "FORT1" -> d.fort >= 1;
            case "FORT2" -> d.fort >= 2;
            case "FORT3" -> d.fort >= 3;
            case "FARM" -> d.farm;
            case "PEN" -> d.pen;
            default -> false;
        };
    }

    /** Fewer and weaker raid titans behind better walls. */
    public static int fortOf(int plot) {
        Homes.PlotDeed d = deed(plot);
        return d == null ? 0 : d.fort;
    }

    // ------------------------------------------------------------------ actions

    public void action(ServerPlayerEntity p, String action, String arg) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created) return;
        int idx = ownPlot(p);
        switch (action) {
            case "build" -> startProject(p, idx, arg);
            case "harvest" -> harvest(p, idx);
            case "respawn" -> {
                pr.respawn = "home".equals(pr.respawn) ? "" : "home";
                AotRpg.PROFILES.save(p.getUuid());
                toast(p, "home".equals(pr.respawn) ? "You'll wake up at home after falling" : "You'll wake at the nearest recovery post", false);
            }
            case "buypet" -> buyPet(p, pr, arg);
            case "companion" -> {
                if (!atHome(p) && !arg.isEmpty() && !arg.equals(pr.companion)) {
                    toast(p, "Change your companion at home", true);
                    break;
                }
                if (!arg.isEmpty() && !pr.pets.contains(arg)) break;
                pr.companion = arg.equals(pr.companion) ? "" : arg;
                removeCompanion(p);
                AotRpg.PROFILES.save(p.getUuid());
            }
            default -> { }
        }
        send(p, action.equals("open"));
    }

    private void toast(ServerPlayerEntity p, String s, boolean bad) {
        Notify.toast(p, Text.literal(s).formatted(bad ? Formatting.RED : Formatting.GOLD), null, bad ? 0xC0463A : 0xE0B96A, "minecraft:bricks", null);
    }

    private void startProject(ServerPlayerEntity p, int idx, String id) {
        if (idx < 0) {
            toast(p, "You need a property plot to build on", true);
            return;
        }
        Homes.PlotDeed d = deed(idx);
        Project pj = null;
        for (Project x : PROJECTS) if (x.id().equals(id)) pj = x;
        if (pj == null || built(d, id) || jobs.containsKey(idx) || !d.building.isEmpty()) return;
        if (!pj.needs().isEmpty() && !built(d, pj.needs())) return;
        Places.PlotInfo plot = AotRpg.PLACES.plots.get(idx);
        ServerWorld w = server.getOverworld();
        List<Place> plan = blueprint(w, plot, id);
        if (plan == null || plan.isEmpty()) {
            toast(p, "There's no clear space on your land for that", true);
            return;
        }
        if (!AotRpg.WALLET.spendMarks(p, pj.price())) {
            toast(p, pj.title() + " costs " + pj.price() + " Marks", true);
            return;
        }
        d.building = id;
        AotRpg.HOMES.save();
        begin(idx, id, plan);
        Notify.toast(p, Text.literal("Construction started: " + pj.title()).formatted(Formatting.GOLD),
            Text.literal("The builders are at work on your land"), 0xE0B96A, "minecraft:bricks", "build");
    }

    private void begin(int idx, String id, List<Place> plan) {
        Job j = new Job();
        j.plot = idx;
        j.project = id;
        j.todo = new ArrayList<>(plan);
        j.total = plan.size();
        jobs.put(idx, j);
    }

    // ------------------------------------------------------------------ building

    public void tick(int ticks) {
        if (server == null) return;
        ServerWorld w = server.getOverworld();
        // Resume projects after a restart.
        if (ticks % 200 == 17) {
            for (var e : AotRpg.HOMES.data.plots.entrySet()) {
                Homes.PlotDeed d = e.getValue();
                if (d.building == null || d.building.isEmpty() || jobs.containsKey(e.getKey()) || e.getKey() >= AotRpg.PLACES.plots.size()) continue;
                List<Place> plan = blueprint(w, AotRpg.PLACES.plots.get(e.getKey()), d.building);
                if (plan != null) begin(e.getKey(), d.building, plan);
            }
        }
        if (ticks % 3 == 0) {
            for (Job j : new ArrayList<>(jobs.values())) build(w, j);
        }
        // Companions keep pace four times a second (ODM and horses outrun any animal).
        if (ticks % 5 == 2) {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) follow(p);
        }
        if (ticks % 40 == 21) {
            for (ServerPlayerEntity p : w.getPlayers()) pets(p);
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) if (p.getWorld() != w) pets(p);
            for (var e : AotRpg.HOMES.data.plots.entrySet()) {
                Homes.PlotDeed d = e.getValue();
                if (e.getKey() >= AotRpg.PLACES.plots.size()) continue;
                Places.PlotInfo pl = AotRpg.PLACES.plots.get(e.getKey());
                if (d.pen) keepAnimals(w, e.getKey(), pl);
                // Estates built before fences joined up get their fences and walls connected once.
                int cx = (pl.x0() + pl.x1()) / 2, cz = (pl.z0() + pl.z1()) / 2;
                if ((d.fort > 0 || d.farm || d.pen) && !reconnected.contains(e.getKey()) && w.isChunkLoaded(cx >> 4, cz >> 4)
                    && w.getClosestPlayer(cx, pl.y(), cz, 48, false) != null) {
                    reconnected.add(e.getKey());
                    reconnect(w, pl);
                }
                produce(d);
            }
        }
    }

    /** Places a couple of blocks per step (only while the land is loaded, so you can watch it rise). */
    private void build(ServerWorld w, Job j) {
        Places.PlotInfo plot = AotRpg.PLACES.plots.get(j.plot);
        int cx = (plot.x0() + plot.x1()) / 2, cz = (plot.z0() + plot.z1()) / 2;
        if (!w.isChunkLoaded(cx >> 4, cz >> 4)) return;
        int n = 2;
        WorldCare.quiet(true);
        try {
            while (n > 0 && !j.todo.isEmpty()) {
                Place pl = j.todo.remove(0);
                j.done++;
                if (!w.isChunkLoaded(pl.pos().getX() >> 4, pl.pos().getZ() >> 4)) continue;
                BlockState cur = w.getBlockState(pl.pos());
                if (cur.equals(pl.state())) continue;
                // Fences, walls and gates join up with what's beside them (and the neighbours with them).
                w.setBlockState(pl.pos(), Block.postProcessState(pl.state(), w, pl.pos()), Block.NOTIFY_ALL);
                if (!pl.state().isAir()) {
                    w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, pl.state()), pl.pos().getX() + 0.5, pl.pos().getY() + 0.5,
                        pl.pos().getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.05);
                    if (w.getRandom().nextInt(3) == 0) {
                        w.playSound(null, pl.pos(), pl.state().getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 0.7f, 0.9f + w.getRandom().nextFloat() * 0.2f);
                    }
                }
                moveBuilder(w, j, pl.pos());
                n--;
            }
        } finally {
            WorldCare.quiet(false);
        }
        if (j.todo.isEmpty()) finish(w, j);
    }

    private void moveBuilder(ServerWorld w, Job j, BlockPos at) {
        Entity b = j.builder == null ? null : w.getEntity(j.builder);
        if (b == null) {
            VillagerEntity v = EntityType.VILLAGER.create(w);
            if (v == null) return;
            v.setAiDisabled(true);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setCustomName(Text.literal("Builder").formatted(Formatting.GOLD));
            v.setCustomNameVisible(true);
            v.addCommandTag(BUILD_TAG);
            v.refreshPositionAndAngles(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, 0, 0);
            j.builder = v.getUuid();
            w.spawnEntity(v);
            return;
        }
        if (j.done % 6 == 0) {
            BlockPos land = Safe.landing(w, at.getX(), at.getY() + 1, at.getZ());
            b.requestTeleport(land.getX() + 0.5, land.getY(), land.getZ() + 0.5);
            ((VillagerEntity) b).swingHand(net.minecraft.util.Hand.MAIN_HAND);
        }
    }

    private void finish(ServerWorld w, Job j) {
        jobs.remove(j.plot);
        if (j.builder != null) {
            Entity b = w.getEntity(j.builder);
            if (b != null) b.discard();
        }
        Homes.PlotDeed d = deed(j.plot);
        if (d == null) return;
        switch (j.project) {
            case "FORT1" -> d.fort = Math.max(d.fort, 1);
            case "FORT2" -> d.fort = Math.max(d.fort, 2);
            case "FORT3" -> d.fort = 3;
            case "FARM" -> {
                d.farm = true;
                d.harvestAt = System.currentTimeMillis();
            }
            case "PEN" -> {
                d.pen = true;
                d.harvestAt = System.currentTimeMillis();
            }
            default -> { }
        }
        d.building = "";
        AotRpg.HOMES.save();
        String title = j.project;
        for (Project p : PROJECTS) if (p.id().equals(j.project)) title = p.title();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (d.stem.equals(AotRpg.HOMES.stem(p))) {
                Notify.toast(p, Text.literal(title + " finished").formatted(Formatting.GOLD, Formatting.BOLD),
                    Text.literal("Your estate grows"), 0xF2C14E, "minecraft:bricks", "build");
                send(p, false);
            }
        }
    }

    public float progress(int idx) {
        Job j = jobs.get(idx);
        return j == null || j.total == 0 ? 0 : (float) j.done / j.total;
    }

    // ------------------------------------------------------------------ blueprints

    private static boolean free(BlockState s) {
        return s.isAir() || s.isReplaceable() || s.isIn(net.minecraft.registry.tag.BlockTags.LEAVES) || s.isIn(net.minecraft.registry.tag.BlockTags.FLOWERS)
            || s.isOf(Blocks.SPRUCE_LOG) || s.isOf(Blocks.SPRUCE_FENCE) || s.isOf(Blocks.SPRUCE_SLAB) || s.isOf(Blocks.LANTERN);
    }

    /** The blocks of a project, in building order (course by course), or null when it can't fit. */
    private List<Place> blueprint(ServerWorld w, Places.PlotInfo p, String id) {
        return switch (id) {
            case "FORT1", "FORT2" -> wall(w, p, id.equals("FORT2"));
            case "FORT3" -> towers(w, p);
            case "FARM" -> corner(w, p, 7, 6, false);
            case "PEN" -> corner(w, p, 7, 6, true);
            default -> null;
        };
    }

    private static int ringX0(Places.PlotInfo p) {
        return p.x0() - HomePlots.LAND;
    }

    private static int ringX1(Places.PlotInfo p) {
        return p.x1() + HomePlots.LAND;
    }

    private static int ringZ0(Places.PlotInfo p) {
        return p.z0() - HomePlots.LAND;
    }

    private static int ringZ1(Places.PlotInfo p) {
        return p.z1() + HomePlots.LAND;
    }

    /** Is this ring column in the gateway (3 wide, in the middle of the gate side)? */
    private static boolean gateway(Places.PlotInfo p, int x, int z) {
        int mx = (ringX0(p) + ringX1(p)) / 2, mz = (ringZ0(p) + ringZ1(p)) / 2;
        return switch (p.gate()) {
            case "north" -> z == ringZ0(p) && Math.abs(x - mx) <= 1;
            case "west" -> x == ringX0(p) && Math.abs(z - mz) <= 1;
            case "east" -> x == ringX1(p) && Math.abs(z - mz) <= 1;
            default -> z == ringZ1(p) && Math.abs(x - mx) <= 1;
        };
    }

    private static List<int[]> ring(Places.PlotInfo p) {
        List<int[]> out = new ArrayList<>();
        int x0 = ringX0(p), x1 = ringX1(p), z0 = ringZ0(p), z1 = ringZ1(p);
        for (int x = x0; x <= x1; x++) out.add(new int[] {x, z0});
        for (int z = z0 + 1; z <= z1; z++) out.add(new int[] {x1, z});
        for (int x = x1 - 1; x >= x0; x--) out.add(new int[] {x, z1});
        for (int z = z1 - 1; z > z0; z--) out.add(new int[] {x0, z});
        return out;
    }

    private List<Place> wall(ServerWorld w, Places.PlotInfo p, boolean stone) {
        List<Place> out = new ArrayList<>();
        int y = p.y();
        List<int[]> ring = ring(p);
        int height = stone ? 4 : 3;
        // Footings first, then course by course all the way round, then the top.
        for (int[] c : ring) {
            w.getChunk(c[0] >> 4, c[1] >> 4);
            for (int yy = y - 1; yy >= y - 4; yy--) {
                BlockPos pos = new BlockPos(c[0], yy, c[1]);
                if (!w.getBlockState(pos).isAir() && !w.getBlockState(pos).isReplaceable()) break;
                out.add(new Place(pos, (stone ? Blocks.COBBLESTONE : Blocks.COARSE_DIRT).getDefaultState()));
            }
        }
        var rnd = net.minecraft.util.math.random.Random.create(p.id() * 31L);
        for (int h = 0; h < height; h++) {
            for (int[] c : ring) {
                boolean gate = gateway(p, c[0], c[1]);
                if (gate && h < 3) {
                    out.add(new Place(new BlockPos(c[0], y + h, c[1]), Blocks.AIR.getDefaultState()));
                    continue;
                }
                BlockPos pos = new BlockPos(c[0], y + h, c[1]);
                if (!free(w.getBlockState(pos)) && !stone) continue;
                BlockState s = stone ? (rnd.nextInt(7) == 0 ? Blocks.MOSSY_STONE_BRICKS : rnd.nextInt(9) == 0 ? Blocks.CRACKED_STONE_BRICKS
                    : Blocks.STONE_BRICKS).getDefaultState() : Blocks.SPRUCE_LOG.getDefaultState();
                if (stone && !free(w.getBlockState(pos)) && !w.getBlockState(pos).isOf(Blocks.SPRUCE_LOG)) continue;
                out.add(new Place(pos, s));
            }
        }
        int top = y + height;
        int i = 0;
        for (int[] c : ring) {
            BlockPos pos = new BlockPos(c[0], top, c[1]);
            boolean gate = gateway(p, c[0], c[1]);
            if (stone) {
                if (!gate && i % 2 == 0) out.add(new Place(pos, Blocks.STONE_BRICK_WALL.getDefaultState()));
                else if (gate) out.add(new Place(pos, Blocks.STONE_BRICK_SLAB.getDefaultState()));
            } else {
                out.add(new Place(pos, gate ? Blocks.SPRUCE_SLAB.getDefaultState() : i % 2 == 0 ? Blocks.SPRUCE_FENCE.getDefaultState()
                    : Blocks.AIR.getDefaultState()));
            }
            i++;
        }
        // Lanterns on the gate posts.
        for (int[] c : ring) {
            if (!gateway(p, c[0], c[1])) continue;
            for (Direction d : new Direction[] {Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
                int nx = c[0] + d.getOffsetX(), nz = c[1] + d.getOffsetZ();
                boolean post = !gateway(p, nx, nz);
                for (int[] r : ring) if (r[0] == nx && r[1] == nz && post) out.add(new Place(new BlockPos(nx, top + 1, nz), Blocks.LANTERN.getDefaultState()));
            }
        }
        return out;
    }

    private List<Place> towers(ServerWorld w, Places.PlotInfo p) {
        List<Place> out = new ArrayList<>();
        int y = p.y();
        int[][] corners = {{ringX0(p), ringZ0(p)}, {ringX1(p), ringZ0(p)}, {ringX0(p), ringZ1(p)}, {ringX1(p), ringZ1(p)}};
        int height = 8;
        for (int h = -1; h <= height + 1; h++) {
            for (int[] c : corners) {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                        BlockPos pos = new BlockPos(c[0] + dx, y + h, c[1] + dz);
                        BlockState s;
                        if (h == -1) s = Blocks.COBBLESTONE.getDefaultState();
                        else if (h == height + 1) {
                            if (!edge || (dx + dz) % 2 != 0) continue;
                            s = Blocks.STONE_BRICK_WALL.getDefaultState();
                        } else if (h == 5 || h == height) s = edge ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.SPRUCE_PLANKS.getDefaultState();
                        else if (edge) s = (h == 3 && (dx == 0 || dz == 0)) ? Blocks.AIR.getDefaultState() : Blocks.STONE_BRICKS.getDefaultState();
                        else s = Blocks.AIR.getDefaultState();
                        if (h >= 0 && !free(w.getBlockState(pos)) && !w.getBlockState(pos).isIn(net.minecraft.registry.tag.BlockTags.STONE_BRICKS)
                            && !w.getBlockState(pos).isOf(Blocks.STONE_BRICK_WALL)) continue;
                        out.add(new Place(pos, s));
                    }
                }
                // A ladder up the inside, a hatch through each floor, and a lantern at the top.
                if (h >= 0 && h <= height) {
                    BlockPos lad = new BlockPos(c[0], y + h, c[1] + 1);
                    out.add(new Place(lad, Blocks.LADDER.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.NORTH)));
                }
            }
        }
        for (int[] c : corners) out.add(new Place(new BlockPos(c[0] - 1, y + height + 1, c[1] - 1), Blocks.LANTERN.getDefaultState()));
        int[] bell = corners[0];
        out.add(new Place(new BlockPos(bell[0], y + height + 1, bell[1]), Blocks.BELL.getDefaultState()));
        return out;
    }

    /** A field or pen in a clear corner of the plot. */
    private List<Place> corner(ServerWorld w, Places.PlotInfo p, int wd, int dp, boolean pen) {
        int y = p.y();
        int[][] corners = {{p.x0(), p.z0()}, {p.x1() - wd + 1, p.z0()}, {p.x0(), p.z1() - dp + 1}, {p.x1() - wd + 1, p.z1() - dp + 1}};
        for (int[] c : corners) {
            boolean clear = true;
            for (int x = c[0]; x < c[0] + wd && clear; x++) {
                for (int z = c[1]; z < c[1] + dp && clear; z++) {
                    w.getChunk(x >> 4, z >> 4);
                    for (int yy = y; yy <= y + 2; yy++) {
                        BlockState s = w.getBlockState(new BlockPos(x, yy, z));
                        if (!s.isAir() && !s.isReplaceable() && !s.isIn(net.minecraft.registry.tag.BlockTags.FLOWERS)) {
                            clear = false;
                            break;
                        }
                    }
                }
            }
            if (!clear) continue;
            List<Place> out = new ArrayList<>();
            for (int x = c[0]; x < c[0] + wd; x++) {
                for (int z = c[1]; z < c[1] + dp; z++) {
                    boolean edge = x == c[0] || z == c[1] || x == c[0] + wd - 1 || z == c[1] + dp - 1;
                    BlockPos g = new BlockPos(x, y - 1, z), a = new BlockPos(x, y, z);
                    if (pen) {
                        out.add(new Place(g, Blocks.GRASS_BLOCK.getDefaultState()));
                        if (edge) {
                            boolean gate = z == c[1] + dp - 1 && x == c[0] + wd / 2;
                            out.add(new Place(a, gate ? Blocks.OAK_FENCE_GATE.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.SOUTH)
                                : Blocks.OAK_FENCE.getDefaultState()));
                        }
                    } else if (edge) {
                        out.add(new Place(g, Blocks.OAK_LOG.getDefaultState().with(Properties.AXIS, x == c[0] || x == c[0] + wd - 1 ? Direction.Axis.Z : Direction.Axis.X)));
                    } else if (z == c[1] + dp / 2) {
                        out.add(new Place(g, Blocks.WATER.getDefaultState()));
                    } else {
                        out.add(new Place(g, Blocks.FARMLAND.getDefaultState().with(FarmlandBlock.MOISTURE, 7)));
                    }
                }
            }
            if (pen) {
                out.add(new Place(new BlockPos(c[0] + 1, y, c[1] + 1), Blocks.HAY_BLOCK.getDefaultState()));
                out.add(new Place(new BlockPos(c[0] + 2, y, c[1] + 1), Blocks.WATER_CAULDRON.getDefaultState().with(LeveledCauldronBlock.LEVEL, 3)));
            } else {
                // Crops, grown, on the tilled rows.
                int row = 0;
                for (int z = c[1] + 1; z < c[1] + dp - 1; z++) {
                    if (z == c[1] + dp / 2) continue;
                    Block crop = row % 3 == 0 ? Blocks.WHEAT : row % 3 == 1 ? Blocks.CARROTS : Blocks.POTATOES;
                    for (int x = c[0] + 1; x < c[0] + wd - 1; x++) {
                        out.add(new Place(new BlockPos(x, y, z), crop.getDefaultState().with(CropBlock.AGE, 7)));
                    }
                    row++;
                }
                out.add(new Place(new BlockPos(c[0], y, c[1]), Blocks.COMPOSTER.getDefaultState()));
            }
            return out;
        }
        return null;
    }

    // ------------------------------------------------------------------ produce

    private static final long BATCH_MS = 30 * 60_000L;
    private static final int MAX_STOCK = 4;

    /** A batch of produce every half hour, up to four waiting. */
    private static void produce(Homes.PlotDeed d) {
        if (!d.farm && !d.pen) return;
        long now = System.currentTimeMillis();
        if (d.harvestAt <= 0) d.harvestAt = now;
        while (d.harvestStock < MAX_STOCK && now - d.harvestAt >= BATCH_MS) {
            d.harvestStock++;
            d.harvestAt += BATCH_MS;
        }
        if (d.harvestStock >= MAX_STOCK) d.harvestAt = now;
    }

    private void harvest(ServerPlayerEntity p, int idx) {
        if (idx < 0) return;
        Homes.PlotDeed d = deed(idx);
        if (d.harvestStock <= 0) {
            toast(p, "Nothing to collect yet", true);
            return;
        }
        if (!HomePlots.ownsAt(p, p.getBlockPos())) {
            toast(p, "Collect your harvest at your property", true);
            return;
        }
        int n = d.harvestStock;
        d.harvestStock = 0;
        if (d.farm) {
            AotRpg.SATCHEL.add(p, Provisions.of(Items.BREAD, 4 * n));
            AotRpg.SATCHEL.add(p, new ItemStack(Items.CARROT, 6 * n));
            AotRpg.SATCHEL.add(p, new ItemStack(Items.POTATO, 6 * n));
            AotRpg.SATCHEL.add(p, new ItemStack(Items.WHEAT, 4 * n));
        }
        if (d.pen) {
            AotRpg.SATCHEL.add(p, new ItemStack(Items.BEEF, 3 * n));
            AotRpg.SATCHEL.add(p, new ItemStack(Items.EGG, Math.min(16, 4 * n)));
            AotRpg.SATCHEL.add(p, new ItemStack(Items.WHITE_WOOL, 2 * n));
            AotRpg.SATCHEL.add(p, new ItemStack(Items.LEATHER, 2 * n));
            AotRpg.SATCHEL.add(p, new ItemStack(Items.MILK_BUCKET));
        }
        AotRpg.HOMES.save();
        Notify.toast(p, Text.literal("Harvest collected").formatted(Formatting.GREEN),
            Text.literal(n + (n == 1 ? " batch" : " batches") + " of produce in your satchel"), 0x5BD35B, "minecraft:wheat", null);
    }

    private void keepAnimals(ServerWorld w, int idx, Places.PlotInfo p) {
        int cx = (p.x0() + p.x1()) / 2, cz = (p.z0() + p.z1()) / 2;
        if (!w.isChunkLoaded(cx >> 4, cz >> 4) || w.getClosestPlayer(cx, p.y(), cz, 64, false) == null) return;
        Box box = new Box(p.x0() - 4, p.y() - 4, p.z0() - 4, p.x1() + 4, p.y() + 8, p.z1() + 4);
        String tag = PEN_TAG + ":" + idx;
        // Any that wandered off the land are brought back.
        for (MobEntity m : w.getEntitiesByClass(MobEntity.class, box.expand(48), e -> e.getCommandTags().contains(tag))) {
            if (!box.contains(m.getPos())) m.requestTeleport((p.x0() + p.x1()) / 2.0, p.y(), (p.z0() + p.z1()) / 2.0);
        }
        int have = w.getEntitiesByClass(MobEntity.class, box, e -> e.getCommandTags().contains(tag)).size();
        if (have >= 7) return;
        // Find the pen's hay bale and fill the pen around it.
        BlockPos hay = null;
        for (BlockPos pos : BlockPos.iterate(p.x0(), p.y(), p.z0(), p.x1(), p.y(), p.z1())) {
            if (w.getBlockState(pos).isOf(Blocks.HAY_BLOCK) && w.getBlockState(pos.east()).isOf(Blocks.WATER_CAULDRON)) {
                hay = pos.toImmutable();
                break;
            }
        }
        if (hay == null) return;
        EntityType<?>[] kinds = {EntityType.COW, EntityType.COW, EntityType.SHEEP, EntityType.SHEEP, EntityType.CHICKEN, EntityType.CHICKEN, EntityType.CHICKEN};
        for (int i = have; i < kinds.length; i++) {
            Entity e = kinds[i].create(w);
            if (!(e instanceof MobEntity m)) continue;
            m.refreshPositionAndAngles(hay.getX() + 1.5 + (i % 3), p.y(), hay.getZ() + 2.5, w.getRandom().nextFloat() * 360, 0);
            m.setPersistent();
            m.addCommandTag(tag);
            w.spawnEntity(m);
        }
    }

    private final java.util.Set<Integer> reconnected = new java.util.HashSet<>();

    /** Joins up the fences, walls and gates of an estate built before they connected properly. */
    private static void reconnect(ServerWorld w, Places.PlotInfo p) {
        for (BlockPos pos : BlockPos.iterate(p.x0() - HomePlots.LAND - 3, p.y() - 1, p.z0() - HomePlots.LAND - 3,
            p.x1() + HomePlots.LAND + 3, p.y() + 10, p.z1() + HomePlots.LAND + 3)) {
            BlockState s = w.getBlockState(pos);
            Block b = s.getBlock();
            if (b instanceof net.minecraft.block.FenceBlock || b instanceof net.minecraft.block.WallBlock || b instanceof net.minecraft.block.PaneBlock) {
                BlockState fixed = Block.postProcessState(s, w, pos);
                if (!fixed.equals(s)) w.setBlockState(pos, fixed, Block.NOTIFY_LISTENERS);
            }
        }
    }

    // ------------------------------------------------------------------ pets

    private void buyPet(ServerPlayerEntity p, Profile pr, String id) {
        Pet pet = pet(id);
        if (pet == null || pr.pets.contains(id)) return;
        if (!AotRpg.WALLET.spendMarks(p, pet.price())) {
            toast(p, pet.name() + " costs " + pet.price() + " Marks", true);
            return;
        }
        pr.pets.add(id);
        AotRpg.PROFILES.save(p.getUuid());
        Notify.toast(p, Text.literal(pet.name() + " adopted!").formatted(Formatting.GOLD),
            Text.literal("It waits at your home · bring one along from the Pets tab"), 0xE0B96A, "minecraft:lead", null);
    }

    public static Pet pet(String id) {
        for (Pet x : PETS) if (x.id().equals(id)) return x;
        return null;
    }

    private static String owner(Entity e, String prefix) {
        for (String t : e.getCommandTags()) if (t.startsWith(prefix)) return t.substring(prefix.length());
        return null;
    }

    public static boolean managedPet(Entity e) {
        return owner(e, HOME_PET) != null || owner(e, COMPANION) != null;
    }

    private Entity makePet(ServerWorld w, ServerPlayerEntity owner, Pet pet, double x, double y, double z, String tag) {
        Entity e = pet.type().create(w);
        if (!(e instanceof MobEntity m)) return null;
        m.refreshPositionAndAngles(x, y, z, w.getRandom().nextFloat() * 360, 0);
        m.setInvulnerable(true);
        m.addCommandTag(tag);
        if (m instanceof TameableEntity t) {
            t.setTamed(true, true);
            t.setOwnerUuid(owner.getUuid());
        }
        if (m instanceof net.minecraft.entity.passive.PandaEntity panda) panda.setBaby(true);
        m.setCustomName(Text.literal(pet.name()).formatted(Formatting.GRAY));
        w.spawnEntity(m);
        return m;
    }

    /** Pets at home while you are there; your companion at your side everywhere else. */
    private void pets(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created || p.isSpectator()) return;
        ServerWorld w = p.getServerWorld();
        String me = p.getUuidAsString();
        boolean home = atHome(p);
        Box near = p.getBoundingBox().expand(48);
        // Home pets: wander your land while you're home, gone when you leave.
        List<Entity> homePets = w.getEntitiesByClass(MobEntity.class, near, e -> me.equals(owner(e, HOME_PET))).stream().map(e -> (Entity) e).toList();
        if (home) {
            for (String id : pr.pets) {
                if (id.equals(pr.companion)) continue;
                boolean here = false;
                for (Entity e : homePets) if (e.getCommandTags().contains("aot_pet:" + id)) here = true;
                if (here) continue;
                Pet pet = pet(id);
                if (pet == null) continue;
                Entity e = makePet(w, p, pet, p.getX() + w.getRandom().nextInt(7) - 3, p.getY(), p.getZ() + w.getRandom().nextInt(7) - 3, HOME_PET + me);
                if (e != null) {
                    e.addCommandTag("aot_pet:" + id);
                    if (e instanceof TameableEntity t) t.setSitting(true);
                }
            }
        } else {
            for (Entity e : homePets) if (e.squaredDistanceTo(p) > 24 * 24) e.discard();
        }
        // Stray home pets far from their owner go.
        for (Entity e : w.getEntitiesByClass(MobEntity.class, near.expand(32), e -> me.equals(owner(e, HOME_PET)))) {
            if (!home && e.squaredDistanceTo(p) > 40 * 40) e.discard();
        }
        // The companion.
        Pet c = pet(pr.companion);
        List<MobEntity> comps = w.getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(64), e -> me.equals(owner(e, COMPANION)));
        if (c == null || !pr.pets.contains(pr.companion)) {
            comps.forEach(Entity::discard);
            return;
        }
        MobEntity comp = comps.isEmpty() ? null : comps.get(0);
        for (int i = 1; i < comps.size(); i++) comps.get(i).discard();
        if (comp == null) {
            Entity e = makePet(w, p, c, p.getX() + 1, p.getY(), p.getZ() + 1, COMPANION + me);
            comp = e instanceof MobEntity m ? m : null;
            if (comp == null) return;
        }
        if (comp instanceof TameableEntity t) t.setSitting(false);
    }

    /**
     * Your companion keeps up: quick on its feet, running when you get ahead, and with you in a
     * blink when you outpace it (flying on ODM gear, galloping, falling from a height).
     */
    private void follow(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (pr.companion == null || pr.companion.isEmpty() || p.isSpectator()) return;
        String me = p.getUuidAsString();
        List<MobEntity> comps = p.getServerWorld().getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(64), e -> me.equals(owner(e, COMPANION)));
        if (comps.isEmpty()) return;
        MobEntity comp = comps.get(0);
        var speed = comp.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (speed != null && speed.getBaseValue() < 0.4) speed.setBaseValue(0.4);
        var fly = comp.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_FLYING_SPEED);
        if (fly != null && fly.getBaseValue() < 0.8) fly.setBaseValue(0.8);
        double d = comp.squaredDistanceTo(p);
        boolean fast = p.getVelocity().horizontalLengthSquared() > 0.09 || !p.isOnGround() || p.hasVehicle();
        if (d > (fast ? 7 * 7 : 12 * 12)) {
            // Just behind you, on your level.
            var back = p.getRotationVector().multiply(1, 0, 1).normalize().multiply(-1.5);
            comp.requestTeleport(p.getX() + back.x, p.getY(), p.getZ() + back.z);
            comp.getNavigation().stop();
        } else if (d > 3 * 3) {
            comp.getNavigation().startMovingTo(p, d > 6 * 6 ? 1.8 : 1.3);
        }
    }

    public void removeCompanion(ServerPlayerEntity p) {
        String me = p.getUuidAsString();
        for (MobEntity e : p.getServerWorld().getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(96), e -> me.equals(owner(e, COMPANION)))) {
            e.discard();
        }
    }

    /** On logout: your pets go home with you. */
    public void forget(ServerPlayerEntity p) {
        String me = p.getUuidAsString();
        for (MobEntity e : p.getServerWorld().getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(96),
            e -> me.equals(owner(e, COMPANION)) || me.equals(owner(e, HOME_PET)))) {
            e.discard();
        }
    }

    /** Stray estate helpers left in the world (builders after a restart, pets without owners) are cleared. */
    public boolean stray(Entity e) {
        if (e.getCommandTags().contains(BUILD_TAG)) {
            // Builders belong to a job running now; one left over from before a restart goes.
            for (Job j : jobs.values()) if (e.getUuid().equals(j.builder)) return false;
            return j0(e);
        }
        String o = owner(e, COMPANION);
        if (o == null) o = owner(e, HOME_PET);
        if (o == null || server == null) return false;
        try {
            return server.getPlayerManager().getPlayer(UUID.fromString(o)) == null;
        } catch (IllegalArgumentException ex) {
            return true;
        }
    }

    /** A builder being spawned right now has no job id recorded yet: keep it for its first tick. */
    private boolean j0(Entity e) {
        return e.age > 5;
    }

    // ------------------------------------------------------------------ view

    public void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.EstateView.ID)) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        int idx = ownPlot(p);
        Homes.PlotDeed d = idx < 0 ? null : deed(idx);
        List<Net.EstateProject> projects = new ArrayList<>();
        for (Project pj : PROJECTS) {
            int state;
            if (d == null) state = 0;
            else if (built(d, pj.id())) state = 3;
            else if (pj.id().equals(d.building)) state = 2;
            else if (!pj.needs().isEmpty() && !built(d, pj.needs())) state = 0;
            else state = d.building.isEmpty() ? 1 : 0;
            projects.add(new Net.EstateProject(pj.id(), pj.title(), pj.desc(), pj.price(), state, state == 2 ? progress(idx) : state == 3 ? 1 : 0));
        }
        List<Net.EstatePet> pets = new ArrayList<>();
        for (Pet pet : PETS) pets.add(new Net.EstatePet(pet.id(), pet.name(), pet.desc(), pet.price(), pr.pets.contains(pet.id()), pet.id().equals(pr.companion)));
        long next = d == null || (!d.farm && !d.pen) ? -1 : Math.max(0, (BATCH_MS - (System.currentTimeMillis() - d.harvestAt)) / 1000);
        ServerPlayNetworking.send(p, new Net.EstateView(idx >= 0, idx < 0 ? "" : HomePlots.label(AotRpg.PLACES.plots.get(idx)), d == null ? 0 : d.fort,
            projects, d == null ? 0 : d.harvestStock, next, pets, atHome(p), "home".equals(pr.respawn), open));
    }
}
