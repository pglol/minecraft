package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The story engine. Each character lives its own story (missions, choices, relationships,
 * ideology, deeds, fates), written as data in resources/story/*.json. The player's party can join
 * the scene they're in: story actors and scene titans are phased so only the scene's players see
 * them, the host decides every choice, and guests may suggest. Canon events are stepping stones
 * everyone reaches; how they arrive, and who they become, is theirs.
 *
 * No filler: a mission that doesn't declare what it does (reveals character, advances the war,
 * exposes lore, changes a relationship, alters a faction, affects the future) is refused at load.
 */
public final class Story {
    // ------------------------------------------------------------------ saved per character

    public static final class State {
        public String mission = "";
        public int step;
        public Set<String> done = new HashSet<>();
        public Set<String> flags = new HashSet<>();
        public Map<String, Integer> affinity = new HashMap<>();
        public Map<String, Integer> standing = new HashMap<>();
        /** Ideology: mercy (+) vs ruthlessness (-), Paradis first (+) vs humanity first (-), independence (+) vs obedience (-). */
        public double mercy, paradis, independence;
        public List<String> deeds = new ArrayList<>();
        public boolean begun;
        /** Set aside by the player (legacy; quests are abandoned instead now). */
        public boolean paused;
        /** Quests offered and waiting to be picked up from their givers. */
        public Set<String> available = new HashSet<>();
        /** Where a memory quest started, to come back to when it ends. */
        public double[] back;
        public boolean freeStart = true;
    }

    // ------------------------------------------------------------------ content

    static final class ActorDef { String name = "?"; String skin = "civilian_m"; boolean watch; String item; String kit; }
    static final class Step {
        String objective = "";
        List<JsonObject> spawn = new ArrayList<>();
        List<JsonObject> start = new ArrayList<>();
        List<JsonObject> done = new ArrayList<>();
        JsonObject goal = new JsonObject();
        /** Only played when this holds (else skipped). */
        JsonObject when;
    }
    static final class Mission {
        String id, title = "", chapter = "", thread = "people", place;
        List<String> purpose = new ArrayList<>();
        int[] level = {1, 10};
        List<Step> steps = new ArrayList<>();
        List<JsonObject> complete = new ArrayList<>();
        String next;
        List<JsonObject> nextIf = new ArrayList<>();
        long xp;
        /** Who offers it: {"actor": id, "at": pos, "place"?: id, "dialogue"?: id}. No giver: it starts on its own. */
        JsonObject giver;
        /** A stage of a bigger quest: starts the moment the one before it ends. */
        boolean chain;
        /** A flashback: takes you there, and back to where you were when the chain ends (returnHome). */
        boolean memory, returnHome;
        /** Where the quest takes you when it starts. */
        JsonElement startAt;
        /** What the giver says about it. */
        String pitch = "";
        /** Quests it makes available: [{"id": .., "if"?: cond}]. */
        List<JsonObject> unlock = new ArrayList<>();
    }
    static final class Choice { String text = ""; String tag; JsonObject requires; List<JsonObject> effects = new ArrayList<>(); String next; }
    static final class Node { String speaker; String text = ""; String next; boolean end; List<Choice> choices = new ArrayList<>(); List<JsonObject> effects = new ArrayList<>(); }
    static final class Dialogue { String start = "a"; Map<String, Node> nodes = new LinkedHashMap<>(); }
    static final class Content {
        Map<String, ActorDef> actors = new HashMap<>();
        List<Mission> missions = new ArrayList<>();
        Map<String, Dialogue> dialogues = new HashMap<>();
        Map<String, String> starts = new HashMap<>();
        List<String> files = new ArrayList<>();
    }

    private static final Set<String> PURPOSES = Set.of("character", "war", "lore", "relationship", "faction", "future");
    private final Map<String, ActorDef> actors = new HashMap<>();
    private final Map<String, Mission> missions = new LinkedHashMap<>();
    private final Map<String, Dialogue> dialogues = new HashMap<>();
    private final Map<String, String> starts = new HashMap<>();
    /** A chained stage -> the quest it belongs to. */
    private final Map<String, String> headOf = new HashMap<>();

    public void load() {
        actors.clear();
        missions.clear();
        dialogues.clear();
        starts.clear();
        Gson gson = new Gson();
        Content index = read(gson, "index.json");
        if (index == null) return;
        starts.putAll(index.starts);
        for (String f : index.files) {
            Content c = read(gson, f);
            if (c == null) continue;
            actors.putAll(c.actors);
            dialogues.putAll(c.dialogues);
            for (Mission m : c.missions) {
                if (m.id == null || m.steps.isEmpty()) {
                    AotRpg.LOG.warn("[story] {}: a mission without id or steps was skipped", f);
                    continue;
                }
                boolean purposeful = false;
                for (String p : m.purpose) if (PURPOSES.contains(p)) purposeful = true;
                if (!purposeful) {
                    AotRpg.LOG.warn("[story] {}: mission {} has no purpose (no filler) and was skipped", f, m.id);
                    continue;
                }
                missions.put(m.id, m);
            }
        }
        // Each giver gets a short offer: the pitch, then take it or leave it.
        headOf.clear();
        for (Mission m : missions.values()) {
            if (m.giver == null) continue;
            String who = m.giver.get("actor").getAsString();
            if (!m.giver.has("dialogue")) {
                Dialogue d = new Dialogue();
                Node n = new Node();
                n.speaker = who;
                n.text = m.pitch.isEmpty() ? "I've got something for you, if you've got time." : m.pitch;
                Choice yes = new Choice();
                yes.text = "I'm in.";
                JsonObject acc = new JsonObject();
                acc.addProperty("accept", m.id);
                yes.effects.add(acc);
                Choice no = new Choice();
                no.text = "Not right now.";
                n.choices.add(yes);
                n.choices.add(no);
                d.nodes.put("a", n);
                dialogues.put("giver:" + m.id, d);
            }
            // The stages that follow a giver's quest belong to it (for abandoning).
            Mission cur = m;
            while (cur.next != null && missions.containsKey(cur.next) && missions.get(cur.next).chain && !headOf.containsKey(cur.next)) {
                headOf.put(cur.next, m.id);
                cur = missions.get(cur.next);
            }
        }
        // Check references so broken content is reported, not discovered in play.
        for (Mission m : missions.values()) {
            if (m.next != null && !missions.containsKey(m.next)) AotRpg.LOG.warn("[story] {} -> unknown next {}", m.id, m.next);
            for (Step s : m.steps) {
                if (s.goal.has("dialogue") && !dialogues.containsKey(s.goal.get("dialogue").getAsString())) AotRpg.LOG.warn("[story] {} -> unknown dialogue {}", m.id, s.goal.get("dialogue").getAsString());
            }
        }
        AotRpg.LOG.info("[story] {} missions, {} dialogues, {} actors", missions.size(), dialogues.size(), actors.size());
    }

    private static Content read(Gson gson, String name) {
        try (InputStream in = Story.class.getResourceAsStream("/story/" + name)) {
            if (in == null) {
                AotRpg.LOG.warn("[story] missing /story/{}", name);
                return null;
            }
            return gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Content.class);
        } catch (Exception e) {
            AotRpg.LOG.error("[story] could not read {}: {}", name, e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ scenes (runtime)

    private record PathPt(Vec3d at, Vec3d face, JsonObject say, boolean talk, long pauseMs) { }

    private static final class Scene {
        final UUID host;
        String mission;
        int step = -1;
        final Set<UUID> guests = new HashSet<>();
        final Map<String, UUID> actors = new HashMap<>();
        final List<UUID> titans = new ArrayList<>();
        final Set<String> followers = new HashSet<>();
        final Set<String> patrols = new HashSet<>();
        final Map<String, Vec3d> walks = new HashMap<>();
        /** Where an actor turns to look once their walk ends. */
        final Map<String, Vec3d> faces = new HashMap<>();
        /** Routes: points walked one after another, pausing (and maybe speaking) at each. */
        final Map<String, java.util.ArrayDeque<PathPt>> paths = new HashMap<>();
        final Map<String, PathPt> arrive = new HashMap<>();
        final Map<String, Long> pauses = new HashMap<>();
        /** Cadets and soldiers in the scene who are up in the air fighting its titans. */
        final Set<String> fighting = new HashSet<>();
        final Map<String, Long> expire = new HashMap<>();
        boolean spawned, titansSpawned;
        long stepAt;
        Vec3d checkpoint;
        String dialogue, node;
        String talkedTo;
        final Map<UUID, Integer> suggestions = new HashMap<>();
        boolean goalMet;

        Scene(UUID host) {
            this.host = host;
        }
    }

    private final Map<UUID, Scene> scenes = new HashMap<>();
    private final Map<UUID, UUID> guestOf = new HashMap<>();
    /** Phased entities: entity id -> scene host. */
    private static final Map<Integer, UUID> phased = new java.util.concurrent.ConcurrentHashMap<>();
    private MinecraftServer server;

    public void open(MinecraftServer server) {
        this.server = server;
        scenes.clear();
        guestOf.clear();
        phased.clear();
        load();
    }

    /** Can this player see this entity? Story actors and scene titans only show to their scene. */
    public static boolean visibleTo(Entity e, ServerPlayerEntity p) {
        // In a story moment you only see the people in it with you, and nobody outside sees you.
        if (e instanceof ServerPlayerEntity other && other != p && !java.util.Objects.equals(moment(other), moment(p))) return false;
        UUID host = phased.get(e.getId());
        if (host == null) return true;
        if (host.equals(p.getUuid())) return true;
        return host.equals(AotRpg.STORY.guestOf.get(p.getUuid()));
    }

    /** The story moment a player is in (their scene's host), or null when they're out in the shared world. */
    static UUID moment(ServerPlayerEntity p) {
        Story st = AotRpg.STORY;
        if (st == null) return null;
        UUID h = st.guestOf.getOrDefault(p.getUuid(), p.getUuid());
        Scene sc = st.scenes.get(h);
        return sc != null && sc.spawned && sc.mission != null && !sc.mission.isEmpty() ? h : null;
    }

    private final Map<UUID, UUID> lastMoment = new HashMap<>();

    /**
     * Someone stepped into or out of a story moment: reconsider, for each other player in the
     * world, whether the two of them can see each other (only those two trackers, nothing else).
     */
    private static void refreshPlayers(ServerPlayerEntity p) {
        try {
            Object mgr = p.getServerWorld().getChunkManager().chunkLoadingManager;
            if (!(mgr instanceof com.pglol.aotrpg.mixin.phase.ChunkLoadingManagerAccessor acc)) return;
            var trackers = acc.aotrpg$trackers();
            Object mine = trackers.get(p.getId());
            for (ServerPlayerEntity o : p.getServerWorld().getPlayers()) {
                if (o == p) continue;
                if (mine instanceof com.pglol.aotrpg.PhaseTracker t) t.aotrpg$refresh(o);
                if (trackers.get(o.getId()) instanceof com.pglol.aotrpg.PhaseTracker t) t.aotrpg$refresh(p);
            }
        } catch (RuntimeException ignored) {
            // Without the phasing hooks, players simply show up again when they next move.
        }
    }

    /** True once a character has lived their first memory and come back: the rest of the world opens up. */
    public static boolean free(State s) {
        if (s.flags.contains("prologue_done")) return true;
        for (String id : s.done) {
            Mission m = AotRpg.STORY.missions.get(id);
            if (m != null && m.returnHome) return true;
        }
        return false;
    }

    public static boolean phased(Entity e) {
        return phased.containsKey(e.getId()) || e.getCommandTags().contains(ACTOR);
    }

    /** A story actor that keeps watch (counts as a witness for stealth). */
    public static boolean watcherActor(Entity e) {
        return e.getCommandTags().contains(WATCH);
    }

    public static final String ACTOR = "aot_actor", WATCH = "aot_watch", GENTLE = "aot_gentle";

    /** A story titan from an early mission: it can't grab you and hits softly. */
    public static boolean gentle(Entity e) {
        return e != null && e.getCommandTags().contains(GENTLE);
    }

    private static final String[] ABNORMAL = {"abnormal", "crawl", "jump", "runner", "sprint", "deviant", "beast", "cart", "jaw", "female",
        "armored", "armoured", "colossal", "attack", "warhammer", "founding", "shifter", "boss"};

    /** Ordinary titans for a story fight: never abnormals; small ones for early missions. */
    private static List<EntityType<?>> storyTitans(int maxLevel) {
        List<EntityType<?>> ok = new ArrayList<>();
        for (EntityType<?> t : TitanTypes.ordinary()) {
            String path = Registries.ENTITY_TYPE.getId(t).getPath();
            boolean bad = false;
            for (String a : ABNORMAL) if (path.contains(a)) bad = true;
            if (!bad) ok.add(t);
        }
        if (ok.isEmpty()) ok.addAll(TitanTypes.ordinary());
        ok.sort((a, b) -> Float.compare(a.getDimensions().height(), b.getDimensions().height()));
        if (maxLevel <= 8 && ok.size() > 2) return new ArrayList<>(ok.subList(0, Math.max(1, ok.size() / 3)));
        if (maxLevel <= 14 && ok.size() > 2) return new ArrayList<>(ok.subList(0, Math.max(1, ok.size() * 2 / 3)));
        return ok;
    }

    // ------------------------------------------------------------------ helpers

    private static Profile pr(ServerPlayerEntity p) {
        return AotRpg.PROFILES.get(p.getUuid());
    }

    private Mission current(Profile pr) {
        return missions.get(pr.story.mission);
    }

    private Step step(Profile pr) {
        Mission m = current(pr);
        if (m == null || pr.story.step < 0 || pr.story.step >= m.steps.size()) return null;
        return m.steps.get(pr.story.step);
    }

    private List<ServerPlayerEntity> members(Scene s) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        ServerPlayerEntity h = server.getPlayerManager().getPlayer(s.host);
        if (h != null) out.add(h);
        for (UUID g : s.guests) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(g);
            if (p != null) out.add(p);
        }
        return out;
    }

    // ---- positions: [forward, right] from the mission's place, forward pointing to the heart of the walls

    private int[] placeOf(String id) {
        return id == null ? null : AotRpg.PLACES.get(id);
    }

    private Vec3d resolve(ServerWorld w, Mission m, JsonElement spec) {
        String placeId = m.place;
        double f = 0, r = 0, dy = 0, frac = -1;
        String gate = null;
        JsonObject rowSpec = null;
        if (spec != null && spec.isJsonArray() && spec.getAsJsonArray().size() > 0) {
            JsonArray a = spec.getAsJsonArray();
            f = a.get(0).getAsDouble();
            r = a.size() > 1 ? a.get(1).getAsDouble() : 0;
        } else if (spec != null && spec.isJsonObject()) {
            JsonObject o = spec.getAsJsonObject();
            if (o.has("place")) placeId = o.get("place").getAsString();
            if (o.has("rel")) {
                f = o.getAsJsonArray("rel").get(0).getAsDouble();
                r = o.getAsJsonArray("rel").get(1).getAsDouble();
            }
            if (o.has("gate")) gate = o.get("gate").getAsString();
            if (o.has("edge")) {
                gate = o.get("edge").getAsString();
                frac = o.has("frac") ? o.get("frac").getAsDouble() : 0.7;
            }
            if (o.has("y")) dy = o.get("y").getAsDouble();
            if (o.has("row")) rowSpec = o;
        }
        int[] p = placeOf(placeId);
        if (p == null) p = new int[] {0, 70, 0};
        double px = p[0], pz = p[2];
        double len = Math.hypot(px, pz);
        double dx = len < 1 ? 0 : -px / len, dz = len < 1 ? 1 : -pz / len;
        if (gate != null && AotRpg.PLACES.walls != null && len > 1) {
            double wall = 0;
            for (int wr : AotRpg.PLACES.walls) if (wr < len && wr > wall) wall = wr;
            if (wall > 0) {
                double gx = px / len * wall, gz = pz / len * wall;
                double depth = Math.max(40, 2 * (len - wall - 8));
                double ex, ez;
                if (gate.equals("outer")) {
                    ex = gx - dx * depth;
                    ez = gz - dz * depth;
                } else {
                    ex = gx + dx * 10;
                    ez = gz + dz * 10;
                }
                if (frac >= 0) {
                    // Part of the way from the district's centre toward that gate: always in its streets.
                    double wx = p[0], wz = p[2];
                    ex = wx + (gate.equals("outer") ? gx - dx * depth - wx : gx - wx) * frac;
                    ez = wz + (gate.equals("outer") ? gz - dz * depth - wz : gz - wz) * frac;
                }
                px = ex;
                pz = ez;
            }
        }
        double rx = -dz, rz = dx;
        if (rowSpec != null) {
            // A place in a formation: one straight line on open, level ground near where it's asked for.
            int size = rowSpec.has("size") ? rowSpec.get("size").getAsInt() : 9;
            double spacing = rowSpec.has("spacing") ? rowSpec.get("spacing").getAsDouble() : 1.6;
            double front = rowSpec.has("front") ? rowSpec.get("front").getAsDouble() : 0;
            double slot = rowSpec.has("slot") ? rowSpec.get("slot").getAsDouble() : 0;
            double[] c = row(w, m.id + ":" + rowSpec.get("row").getAsString(), px + dx * f + rx * r, pz + dz * f + rz * r, dx, dz, size, spacing);
            if (c != null) {
                double xd = c[0] + rx * slot * spacing + dx * front, zd = c[1] + rz * slot * spacing + dz * front;
                int top = w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(xd), (int) Math.floor(zd));
                return new Vec3d(xd, top + dy, zd);
            }
            f += front;
            r += slot * spacing;
        }
        int x = (int) Math.round(px + dx * f + rx * r), z = (int) Math.round(pz + dz * f + rz * r);
        BlockPos g = ground(w, x, p[1], z);
        return new Vec3d(g.getX() + 0.5, g.getY() + dy, g.getZ() + 0.5);
    }

    private final Map<String, double[]> rows = new HashMap<>();

    /**
     * Finds (once) a straight row for a formation near (bx, bz), running across the forward
     * direction: every place in it, and the strip in front where an instructor walks, on level solid
     * ground with nothing standing next to it (no posts, trees or walls). Null until it's loaded.
     */
    private double[] row(ServerWorld w, String key, double bx, double bz, double dx, double dz, int size, double spacing) {
        double[] have = rows.get(key);
        if (have != null) return have;
        double rx = -dz, rz = dx;
        int n = size / 2;
        List<int[]> cand = new ArrayList<>();
        for (int a = -14; a <= 14; a++) for (int b = -14; b <= 14; b++) cand.add(new int[] {a, b});
        cand.sort(java.util.Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        for (int[] o : cand) {
            double cx = bx + dx * o[0] + rx * o[1], cz = bz + dz * o[0] + rz * o[1];
            if (!w.isChunkLoaded((int) Math.floor(cx) >> 4, (int) Math.floor(cz) >> 4)) return null;
            int y0 = w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(cx), (int) Math.floor(cz));
            boolean ok = true;
            for (int k = -n - 1; k <= n + 1 && ok; k++) {
                for (double fo : new double[] {-1.5, 0, 1.5, 3}) {
                    int x = (int) Math.floor(cx + rx * k * spacing + dx * fo), z = (int) Math.floor(cz + rz * k * spacing + dz * fo);
                    if (!openGround(w, x, z, y0)) {
                        ok = false;
                        break;
                    }
                }
            }
            if (ok) {
                double[] c = {cx, cz};
                rows.put(key, c);
                return c;
            }
        }
        return null;
    }

    private static boolean openGround(ServerWorld w, int x, int z, int y0) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;
        int top = w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (Math.abs(top - y0) > 1) return false;
        BlockPos g = new BlockPos(x, top - 1, z);
        var gs = w.getBlockState(g);
        if (!gs.getFluidState().isEmpty() || !gs.isSolidBlock(w, g)) return false;
        if (w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z) > top + 3) return false;
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] a : around) if (w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x + a[0], z + a[1]) > top + 1) return false;
        return true;
    }

    /** Particles only the scene's players see (the story is theirs; everyone else's world stays quiet). */
    private <T extends net.minecraft.particle.ParticleEffect> void particles(Scene sc, ServerPlayerEntity p, ServerWorld w, T type,
                                                                          double x, double y, double z, int n, double dx, double dy, double dz, double speed) {
        for (ServerPlayerEntity o : sc == null ? List.of(p) : members(sc)) {
            if (o.getServerWorld() == w) w.spawnParticles(o, type, true, x, y, z, n, dx, dy, dz, speed);
        }
    }

    /** A sound only the scene's players hear. */
    private void sound(Scene sc, ServerPlayerEntity p, SoundEvent se, SoundCategory cat, Vec3d at, float vol, float pitch) {
        var entry = Registries.SOUND_EVENT.getEntry(se);
        for (ServerPlayerEntity o : sc == null ? List.of(p) : members(sc)) {
            o.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket(entry, cat, at.x, at.y, at.z, vol, pitch, o.getRandom().nextLong()));
        }
    }

    /**
     * The nearest spot to `at` where a person fits: open at the feet and head, solid underfoot, no
     * water, and not on top of another of the scene's actors. Keeps `at` when it's already fine;
     * prefers staying on the same side of a roof (indoors stays indoors, outdoors outdoors).
     */
    private Vec3d settle(ServerWorld w, Vec3d at, Scene sc, Entity self) {
        BlockPos base = BlockPos.ofFloored(at.x, at.y + 0.01, at.z);
        if (!w.isChunkLoaded(base)) return at;
        if (standable(w, base) && !crowded(w, sc, at, self)) return at;
        boolean sky = w.isSkyVisible(base.up());
        Vec3d best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos c = base.add(dx, dy, dz);
                    double d = dx * dx + dz * dz + dy * dy * 2;
                    if (d >= bd || !standable(w, c)) continue;
                    if (w.isSkyVisible(c.up()) != sky) d += 12;
                    Vec3d v = new Vec3d(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
                    if (d < bd && !crowded(w, sc, v, self)) {
                        bd = d;
                        best = v;
                    }
                }
            }
        }
        return best != null ? best : at;
    }

    private static boolean standable(ServerWorld w, BlockPos p) {
        return w.getBlockState(p).getCollisionShape(w, p).isEmpty() && w.getBlockState(p.up()).getCollisionShape(w, p.up()).isEmpty()
            && w.getFluidState(p).isEmpty() && !w.getBlockState(p.down()).getCollisionShape(w, p.down()).isEmpty();
    }

    private boolean crowded(ServerWorld w, Scene sc, Vec3d v, Entity self) {
        if (sc == null) return false;
        for (UUID u : sc.actors.values()) {
            Entity e = w.getEntity(u);
            if (e == null || e == self || !(e instanceof VillagerEntity)) continue;
            double dx = e.getX() - v.x, dz = e.getZ() - v.z;
            if (dx * dx + dz * dz < 1.0 && Math.abs(e.getY() - v.y) < 2) return true;
        }
        return false;
    }

    private static void faceTo(Entity e, Vec3d pt) {
        float yaw = (float) (MathHelper.atan2(pt.z - e.getZ(), pt.x - e.getX()) * MathHelper.DEGREES_PER_RADIAN) - 90;
        e.setYaw(yaw);
        e.setHeadYaw(yaw);
        if (e instanceof LivingEntity le) le.setBodyYaw(yaw);
    }

    /** Street level near the place's height (not rooftops); underground stays underground. */
    private static BlockPos ground(ServerWorld w, int x, int hintY, int z) {
        BlockPos near = Safe.near(w, x, hintY, z);
        if (near != null && Math.abs(near.getY() - hintY) <= 8) return near;
        return Safe.landing(w, x, hintY, z);
    }

    // ------------------------------------------------------------------ conditions and effects

    private boolean test(ServerPlayerEntity p, JsonObject c) {
        if (c == null) return true;
        Profile pr = pr(p);
        State s = pr.story;
        for (var e : c.entrySet()) {
            String k = e.getKey();
            JsonElement v = e.getValue();
            boolean ok = switch (k) {
                case "origin" -> pr.origin != null && matchesAny(v, pr.origin.name());
                case "flag" -> allFlags(s, v, true);
                case "not" -> allFlags(s, v, false);
                case "level" -> pr.level >= v.getAsInt();
                case "role" -> matchesAny(v, pr.cls().name());
                case "charisma" -> pr.total(Stat.CHARISMA) >= v.getAsInt();
                case "marks" -> pr.marks >= v.getAsLong();
                case "affinity" -> {
                    boolean all = true;
                    for (var a : v.getAsJsonObject().entrySet()) if (s.affinity.getOrDefault(a.getKey(), 0) < a.getValue().getAsInt()) all = false;
                    yield all;
                }
                case "ideology" -> {
                    boolean all = true;
                    for (var a : v.getAsJsonObject().entrySet()) {
                        double have = axis(s, a.getKey()), need = a.getValue().getAsDouble();
                        if (need >= 0 ? have < need : have > need) all = false;
                    }
                    yield all;
                }
                case "party" -> {
                    Scene sc = scenes.get(p.getUuid());
                    yield sc != null && sc.guests.size() + 1 >= v.getAsInt();
                }
                default -> true;
            };
            if (!ok) return false;
        }
        return true;
    }

    private static boolean matchesAny(JsonElement v, String value) {
        if (v.isJsonArray()) {
            for (JsonElement x : v.getAsJsonArray()) if (x.getAsString().equalsIgnoreCase(value)) return true;
            return false;
        }
        return v.getAsString().equalsIgnoreCase(value);
    }

    private static boolean allFlags(State s, JsonElement v, boolean want) {
        if (v.isJsonArray()) {
            for (JsonElement x : v.getAsJsonArray()) if (s.flags.contains(x.getAsString()) != want) return false;
            return true;
        }
        return s.flags.contains(v.getAsString()) == want;
    }

    private static double axis(State s, String k) {
        return switch (k) {
            case "mercy" -> s.mercy;
            case "paradis" -> s.paradis;
            case "independence" -> s.independence;
            default -> 0;
        };
    }

    private void apply(ServerPlayerEntity p, Scene sc, Mission m, List<JsonObject> effects) {
        if (effects == null) return;
        for (JsonObject e : effects) apply(p, sc, m, e);
    }

    private void apply(ServerPlayerEntity p, Scene sc, Mission m, JsonObject e) {
        Profile pr = pr(p);
        State s = pr.story;
        ServerWorld w = p.getServerWorld();
        if (e.has("if")) {
            JsonObject cond = e.getAsJsonObject("if");
            List<JsonObject> branch = new ArrayList<>();
            JsonArray arr = test(p, cond) ? (e.has("then") ? e.getAsJsonArray("then") : null) : (e.has("else") ? e.getAsJsonArray("else") : null);
            if (arr != null) for (JsonElement x : arr) branch.add(x.getAsJsonObject());
            apply(p, sc, m, branch);
            return;
        }
        for (var kv : e.entrySet()) {
            String k = kv.getKey();
            JsonElement v = kv.getValue();
            switch (k) {
                case "xp" -> AotRpg.PROGRESSION.addXp(p, pr, v.getAsLong());
                case "marks" -> AotRpg.WALLET.earn(p, v.getAsLong(), "the story");
                case "flag" -> {
                    if (v.isJsonArray()) for (JsonElement x : v.getAsJsonArray()) s.flags.add(x.getAsString());
                    else s.flags.add(v.getAsString());
                }
                case "unflag" -> s.flags.remove(v.getAsString());
                case "fate" -> {
                    for (var a : v.getAsJsonObject().entrySet()) {
                        s.flags.removeIf(f -> f.startsWith("fate:" + a.getKey() + ":"));
                        s.flags.add("fate:" + a.getKey() + ":" + a.getValue().getAsString());
                    }
                }
                case "affinity" -> {
                    for (var a : v.getAsJsonObject().entrySet()) {
                        int now = Math.max(-100, Math.min(100, s.affinity.getOrDefault(a.getKey(), 0) + a.getValue().getAsInt()));
                        s.affinity.put(a.getKey(), now);
                        int d = a.getValue().getAsInt();
                        ActorDef ad = actors.get(a.getKey());
                        if (ad != null && Math.abs(d) >= 5) {
                            p.sendMessage(Text.literal(ad.name + (d > 0 ? " will remember that." : " won't forget that."))
                                .formatted(d > 0 ? Formatting.DARK_AQUA : Formatting.DARK_RED, Formatting.ITALIC), true);
                        }
                    }
                }
                case "standing" -> {
                    for (var a : v.getAsJsonObject().entrySet()) s.standing.merge(a.getKey(), a.getValue().getAsInt(), Integer::sum);
                }
                case "ideology" -> {
                    for (var a : v.getAsJsonObject().entrySet()) {
                        double d = a.getValue().getAsDouble();
                        switch (a.getKey()) {
                            case "mercy" -> s.mercy = clamp(s.mercy + d);
                            case "paradis" -> s.paradis = clamp(s.paradis + d);
                            case "independence" -> s.independence = clamp(s.independence + d);
                            default -> { }
                        }
                    }
                }
                case "deed" -> {
                    s.deeds.add(v.getAsString());
                    while (s.deeds.size() > 200) s.deeds.remove(0);
                }
                case "say" -> {
                    JsonObject o = v.getAsJsonObject();
                    String who = o.has("who") ? o.get("who").getAsString() : null;
                    line(sc, p, who, o.get("text").getAsString());
                }
                case "card" -> {
                    JsonObject o = v.getAsJsonObject();
                    for (ServerPlayerEntity x : sc == null ? List.of(p) : members(sc)) {
                        if (ServerPlayNetworking.canSend(x, Net.StoryCard.ID)) {
                            ServerPlayNetworking.send(x, new Net.StoryCard(o.get("title").getAsString(), o.has("sub") ? o.get("sub").getAsString() : ""));
                        }
                    }
                }
                case "toast" -> Notify.toast(p, Text.literal(v.getAsString()).formatted(Formatting.GOLD), null, 0xE0B96A, null, "story");
                case "shake", "flash" -> {
                    for (ServerPlayerEntity x : sc == null ? List.of(p) : members(sc)) {
                        if (ServerPlayNetworking.canSend(x, Net.StoryFx.ID)) ServerPlayNetworking.send(x, new Net.StoryFx(k, v.getAsFloat()));
                    }
                }
                case "sound" -> {
                    JsonObject o = v.getAsJsonObject();
                    SoundEvent se = Registries.SOUND_EVENT.get(Identifier.of(o.get("id").getAsString()));
                    Vec3d at = o.has("at") ? resolve(w, m, o.get("at")) : p.getPos();
                    if (se != null) sound(sc, p, se, SoundCategory.AMBIENT, at, o.has("volume") ? o.get("volume").getAsFloat() : 1f,
                        o.has("pitch") ? o.get("pitch").getAsFloat() : 1f);
                }
                case "fx" -> {
                    JsonObject o = v.getAsJsonObject();
                    Vec3d at = resolve(w, m, o.get("at"));
                    String type = o.get("type").getAsString();
                    switch (type) {
                        case "explosion" -> particles(sc, p, w, ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 2, at.z, 2, 2, 2, 2, 0);
                        case "smoke" -> particles(sc, p, w, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, at.x, at.y + 1, at.z, 40, 3, 2, 3, 0.02);
                        case "steam" -> particles(sc, p, w, ParticleTypes.CLOUD, at.x, at.y + 20, at.z, 200, 8, 20, 8, 0.1);
                        case "fire" -> particles(sc, p, w, ParticleTypes.FLAME, at.x, at.y + 1, at.z, 60, 2, 1, 2, 0.02);
                        default -> { }
                    }
                }
                case "remove" -> {
                    if (sc != null) despawnActor(sc, v.getAsString());
                }
                case "follow" -> {
                    if (sc != null) {
                        sc.followers.add(v.getAsString());
                        sc.walks.remove(v.getAsString());
                    }
                }
                case "unfollow" -> {
                    if (sc != null) sc.followers.remove(v.getAsString());
                }
                case "walk" -> {
                    JsonObject o = v.getAsJsonObject();
                    if (sc != null) {
                        String a = o.get("actor").getAsString();
                        sc.followers.remove(a);
                        sc.walks.put(a, settle(w, resolve(w, m, o.get("to")), sc, actorEntity(sc, a)));
                        if (o.has("face")) sc.faces.put(a, resolve(w, m, o.get("face")));
                        else sc.faces.remove(a);
                    }
                }
                case "path" -> {
                    JsonObject o = v.getAsJsonObject();
                    if (sc != null) {
                        String a = o.get("actor").getAsString();
                        sc.followers.remove(a);
                        sc.walks.remove(a);
                        java.util.ArrayDeque<PathPt> q = new java.util.ArrayDeque<>();
                        for (JsonElement pe : o.getAsJsonArray("points")) {
                            JsonObject po = pe.getAsJsonObject();
                            q.add(new PathPt(resolve(w, m, po.get("at")), po.has("face") ? resolve(w, m, po.get("face")) : null,
                                po.has("say") ? po.getAsJsonObject("say") : null, po.has("talk") && po.get("talk").getAsBoolean(),
                                (long) ((po.has("pause") ? po.get("pause").getAsDouble() : 0.6) * 1000)));
                        }
                        sc.paths.put(a, q);
                    }
                }
                case "titan_actor" -> {
                    if (sc != null) spawnTitanActor(sc, m, w, v.getAsJsonObject());
                }
                case "teleport" -> {
                    Vec3d to = resolve(w, m, v);
                    for (ServerPlayerEntity x : sc == null ? List.of(p) : members(sc)) {
                        x.stopRiding();
                        x.teleport(w, to.x + x.getRandom().nextDouble() - 0.5, to.y, to.z + x.getRandom().nextDouble() - 0.5, x.getYaw(), x.getPitch());
                    }
                    if (sc != null) {
                        sc.checkpoint = to;
                        for (String a : sc.followers) moveActor(sc, a, to.add(1.5, 0, 1.5));
                    }
                }
                case "faction" -> AotRpg.FACTIONS.action(p, "join", v.getAsString());
                case "give" -> {
                    JsonObject o = v.getAsJsonObject();
                    var item = Registries.ITEM.get(Identifier.of(o.get("item").getAsString()));
                    ItemStack st = new ItemStack(item, o.has("count") ? o.get("count").getAsInt() : 1);
                    if (!p.giveItemStack(st)) p.dropItem(st, false);
                }
                case "start" -> s.mission = "@" + v.getAsString();
                case "pay" -> AotRpg.WALLET.spendMarks(p, v.getAsLong());
                case "accept" -> accept(p, v.getAsString());
                case "cutscene" -> cutscene(p, sc, m, v.getAsJsonObject());
                case "spawn" -> {
                    if (sc != null) {
                        JsonObject o = v.getAsJsonObject();
                        if (o.has("titans")) spawnTitans(sc, m, w, o, p);
                        else spawnActor(sc, m, w, o);
                    }
                }
                case "chapter" -> pr.chapter = Math.max(pr.chapter, v.getAsInt());
                default -> { }
            }
        }
    }

    private static double clamp(double v) {
        return Math.max(-100, Math.min(100, v));
    }

    /** A line of speech or narration: film subtitles for the whole scene, and the chat log. */
    private void line(Scene sc, ServerPlayerEntity p, String who, String text) {
        ActorDef a = who == null ? null : actors.get(who);
        ServerPlayerEntity hp = sc == null ? p : server.getPlayerManager().getPlayer(sc.host);
        text = fill(text, hp == null ? p : hp);
        String name = a == null ? "" : a.name;
        String skin = a == null ? "" : a.skin;
        for (ServerPlayerEntity x : sc == null ? List.of(p) : members(sc)) {
            if (ServerPlayNetworking.canSend(x, Net.StoryLine.ID)) ServerPlayNetworking.send(x, new Net.StoryLine(name, skin, text));
            x.sendMessage(name.isEmpty() ? Text.literal(text).formatted(Formatting.GRAY, Formatting.ITALIC)
                : Text.literal(name + ": ").formatted(Formatting.GOLD).append(Text.literal(text).formatted(Formatting.WHITE)), false);
        }
    }

    // ------------------------------------------------------------------ actors and titans

    private void tagPhased(Entity e, Scene sc) {
        e.addCommandTag(ACTOR);
        e.addCommandTag("aot_scene:" + sc.host);
        phased.put(e.getId(), sc.host);
    }

    private void spawnActor(Scene sc, Mission m, ServerWorld w, JsonObject o) {
        String id = o.get("actor").getAsString();
        if (sc.actors.containsKey(id)) {
            Entity old = w.getEntity(sc.actors.get(id));
            if (old != null && old.isAlive()) {
                if (o.has("at")) {
                    Vec3d at = settle(w, resolve(w, m, o.get("at")), sc, old);
                    old.requestTeleport(at.x, at.y, at.z);
                }
                return;
            }
        }
        ActorDef def = actors.getOrDefault(id, new ActorDef());
        Vec3d at = resolve(w, m, o.get("at"));
        if (def.item != null && def.item.equals("boat")) {
            // Boats only on water: the nearest canal or river nearby, or no boat at all.
            Vec3d water = water(w, BlockPos.ofFloored(at), 24);
            if (water == null) return;
            at = water;
            var boat = EntityType.BOAT.create(w);
            if (boat == null) return;
            boat.refreshPositionAndAngles(at.x, at.y, at.z, o.has("yaw") ? o.get("yaw").getAsFloat() : 0, 0);
            boat.setInvulnerable(true);
            boat.addCommandTag("aot_aid:" + id);
            tagPhased(boat, sc);
            w.spawnEntity(boat);
            phased.put(boat.getId(), sc.host);
            sc.actors.put(id, boat.getUuid());
            return;
        }
        if (def.item != null) {
            // A prop: something to take (a gear pack, a crate), held by an invisible stand.
            var stand = EntityType.ARMOR_STAND.create(w);
            if (stand == null) return;
            stand.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0);
            stand.setInvisible(true);
            stand.setInvulnerable(true);
            stand.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, new ItemStack(Registries.ITEM.get(Identifier.of(def.item))));
            stand.setCustomName(Text.literal(def.name).formatted(Formatting.YELLOW));
            stand.setCustomNameVisible(true);
            stand.addCommandTag("aot_aid:" + id);
            tagPhased(stand, sc);
            w.spawnEntity(stand);
            phased.put(stand.getId(), sc.host);
            sc.actors.put(id, stand.getUuid());
            return;
        }
        VillagerEntity v = EntityType.VILLAGER.create(w);
        if (v == null) return;
        // Somewhere a person can actually stand: not inside a wall, not on top of someone else.
        at = settle(w, at, sc, null);
        v.refreshPositionAndAngles(at.x, at.y, at.z, o.has("yaw") ? o.get("yaw").getAsFloat() : 0, 0);
        v.setAiDisabled(true);
        v.setInvulnerable(true);
        v.setSilent(true);
        v.setCustomName(Text.literal(def.name));
        v.setCustomNameVisible(true);
        v.addCommandTag("aot_aid:" + id);
        if (def.watch || (o.has("watch") && o.get("watch").getAsBoolean())) v.addCommandTag(WATCH);
        // Cadets and soldiers wear what you wear: uniform, harness and boots.
        if (def.kit != null) Kit.dress(v, def.kit);
        tagPhased(v, sc);
        w.spawnEntity(v);
        phased.put(v.getId(), sc.host);
        sc.actors.put(id, v.getUuid());
        if (o.has("patrol") && o.get("patrol").getAsBoolean()) sc.patrols.add(id);
        if (o.has("follow") && o.get("follow").getAsBoolean()) sc.followers.add(id);
        if (o.has("face")) facePlayer(v, sc);
        if (o.has("pose") && o.get("pose").getAsString().equals("crouch")) v.setPose(net.minecraft.entity.EntityPose.CROUCHING);
        sendActors(sc);
    }

    /** The nearest open water surface within r blocks (water with air above), or null. */
    private static Vec3d water(ServerWorld w, BlockPos c, int r) {
        Vec3d best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx += 2) {
            for (int dz = -r; dz <= r; dz += 2) {
                for (int dy = -6; dy <= 4; dy++) {
                    BlockPos p = c.add(dx, dy, dz);
                    if (!w.getFluidState(p).isIn(net.minecraft.registry.tag.FluidTags.WATER) || !w.getBlockState(p.up()).isAir()) continue;
                    double d = dx * dx + dz * dz + dy * dy;
                    if (d < bd) {
                        bd = d;
                        best = new Vec3d(p.getX() + 0.5, p.getY() + 1, p.getZ() + 0.5);
                    }
                }
            }
        }
        return best;
    }

    private void facePlayer(Entity v, Scene sc) {
        ServerPlayerEntity h = server.getPlayerManager().getPlayer(sc.host);
        if (h == null) return;
        float yaw = (float) (MathHelper.atan2(h.getZ() - v.getZ(), h.getX() - v.getX()) * MathHelper.DEGREES_PER_RADIAN) - 90;
        v.setYaw(yaw);
        v.setHeadYaw(yaw);
        v.setBodyYaw(yaw);
    }

    private void spawnTitanActor(Scene sc, Mission m, ServerWorld w, JsonObject o) {
        String which = o.get("shifter").getAsString();
        EntityType<?> type = which.equals("ordinary") ? null : TitanTypes.shifter(which);
        if (type == null) {
            List<EntityType<?>> all = storyTitans(m.level[1]);
            if (all.isEmpty()) return;
            type = all.get(w.getRandom().nextInt(all.size()));
        }
        Entity t = type.create(w);
        if (t == null) return;
        Vec3d at = resolve(w, m, o.get("at"));
        float yaw = (float) (MathHelper.atan2(-at.z, -at.x) * MathHelper.DEGREES_PER_RADIAN) - 90;
        if (o.has("overWall") && t instanceof LivingEntity le) {
            // Tall enough that its head and shoulders show above the Wall it stands behind: grown if
            // the model allows, and raised the rest of the way (its feet are hidden by the Wall).
            double extra = o.get("overWall").getAsDouble();
            double len = Math.hypot(at.x, at.z);
            double ix = len < 1 ? 0 : -at.x / len, iz = len < 1 ? 0 : -at.z / len;
            int wallTop = (int) at.y;
            for (int k = 0; k <= 40; k++) {
                int x = (int) Math.floor(at.x + ix * k), z = (int) Math.floor(at.z + iz * k);
                if (w.isChunkLoaded(x >> 4, z >> 4)) wallTop = Math.max(wallTop, w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z));
            }
            double baseH = Math.max(1, t.getHeight());
            double want = wallTop + extra - at.y;
            double scale = Math.max(1, Math.min(4, want / baseH));
            var attr = le.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_SCALE);
            if (attr != null) attr.setBaseValue(scale);
            else scale = 1;
            double top = at.y + baseH * scale;
            if (top < wallTop + extra) at = new Vec3d(at.x, at.y + (wallTop + extra - top), at.z);
            t.setNoGravity(true);
        }
        t.refreshPositionAndAngles(at.x, at.y, at.z, yaw, 0);
        if (t instanceof MobEntity mob) {
            mob.setAiDisabled(true);
            mob.setPersistent();
        }
        t.setInvulnerable(true);
        t.addCommandTag("aot_aid:" + o.get("id").getAsString());
        tagPhased(t, sc);
        w.spawnEntity(t);
        phased.put(t.getId(), sc.host);
        sc.actors.put(o.get("id").getAsString(), t.getUuid());
        if (o.has("seconds")) sc.expire.put(o.get("id").getAsString(), System.currentTimeMillis() + o.get("seconds").getAsLong() * 1000);
    }

    private void spawnTitans(Scene sc, Mission m, ServerWorld w, JsonObject o, ServerPlayerEntity host) {
        int n = o.has("titans") ? o.get("titans").getAsInt() : 1;
        n += sc.guests.size() / 2;
        int lv = Math.max(m.level[0], Math.min(m.level[1], pr(host).level));
        if (o.has("level")) lv = o.get("level").getAsInt();
        double spread = o.has("spread") ? o.get("spread").getAsDouble() : 8;
        List<EntityType<?>> kinds = storyTitans(m.level[1]);
        boolean gentle = o.has("gentle") ? o.get("gentle").getAsBoolean() : m.level[1] <= 8;
        if (kinds.isEmpty()) {
            // No titan mod in this world: the fight can't happen, so it counts as won.
            sc.titansSpawned = true;
            return;
        }
        Vec3d c = resolve(w, m, o.get("at"));
        for (int i = 0; i < n; i++) {
            Entity t = kinds.get(w.getRandom().nextInt(kinds.size())).create(w);
            if (t == null) continue;
            double a = w.getRandom().nextDouble() * Math.PI * 2;
            BlockPos g = ground(w, (int) (c.x + Math.cos(a) * spread), (int) c.y, (int) (c.z + Math.sin(a) * spread));
            t.refreshPositionAndAngles(g.getX() + 0.5, g.getY(), g.getZ() + 0.5, w.getRandom().nextFloat() * 360, 0);
            if (t instanceof MobEntity mob) {
                mob.initialize(w, w.getLocalDifficulty(g), SpawnReason.EVENT, null);
                mob.setPersistent();
                mob.setTarget(host);
            }
            TitanLevels.fix(t, lv);
            if (gentle) t.addCommandTag(GENTLE);
            if (o.has("strikes")) t.addCommandTag("aot_raidstrikes:" + o.get("strikes").getAsInt());
            tagPhased(t, sc);
            w.spawnEntity(t);
            phased.put(t.getId(), sc.host);
            sc.titans.add(t.getUuid());
        }
        sc.titansSpawned = true;
    }

    private void despawnActor(Scene sc, String id) {
        UUID u = sc.actors.remove(id);
        sc.followers.remove(id);
        sc.patrols.remove(id);
        sc.walks.remove(id);
        if (u == null) return;
        for (ServerWorld w : server.getWorlds()) {
            Entity e = w.getEntity(u);
            if (e != null) {
                phased.remove(e.getId());
                e.discard();
            }
        }
    }

    private void clearTitans(Scene sc) {
        for (UUID u : sc.titans) {
            for (ServerWorld w : server.getWorlds()) {
                Entity e = w.getEntity(u);
                if (e != null) {
                    phased.remove(e.getId());
                    e.discard();
                }
            }
        }
        sc.titans.clear();
        sc.titansSpawned = false;
    }

    private void clearScene(Scene sc) {
        for (String id : new ArrayList<>(sc.actors.keySet())) despawnActor(sc, id);
        clearTitans(sc);
        sc.spawned = false;
        sc.dialogue = null;
        sc.node = null;
        closeDialogue(sc);
    }

    private void moveActor(Scene sc, String id, Vec3d to) {
        UUID u = sc.actors.get(id);
        if (u == null) return;
        for (ServerWorld w : server.getWorlds()) {
            Entity e = w.getEntity(u);
            if (e != null) e.requestTeleport(to.x, to.y, to.z);
        }
    }

    private Entity actorEntity(Scene sc, String id) {
        UUID u = sc.actors.get(id);
        if (u == null) return null;
        for (ServerWorld w : server.getWorlds()) {
            Entity e = w.getEntity(u);
            if (e != null) return e;
        }
        return null;
    }

    private void sendActors(Scene sc) {
        for (ServerPlayerEntity x : members(sc)) sendActorsTo(x);
    }

    /** Skins of everything story-shaped this player can see: their scene's cast and their quest givers. */
    private void sendActorsTo(ServerPlayerEntity x) {
        if (!ServerPlayNetworking.canSend(x, Net.Actors.ID)) return;
        List<Net.ActorInfo> list = new ArrayList<>();
        UUID host = guestOf.getOrDefault(x.getUuid(), x.getUuid());
        Scene sc = scenes.get(host);
        if (sc != null) {
            for (var a : sc.actors.entrySet()) {
                Entity e = actorEntity(sc, a.getKey());
                ActorDef d = actors.get(a.getKey());
                if (e instanceof VillagerEntity) list.add(new Net.ActorInfo(e.getId(), d == null ? "civilian_m" : d.skin));
            }
        }
        Map<String, UUID> mine = givers.get(host);
        if (mine != null) {
            for (var g : mine.entrySet()) {
                Entity e = server.getOverworld().getEntity(g.getValue());
                Mission m = missions.get(g.getKey());
                if (e == null || m == null) continue;
                ActorDef d = actors.get(m.giver.get("actor").getAsString());
                list.add(new Net.ActorInfo(e.getId(), d == null ? "civilian_m" : d.skin));
            }
        }
        ServerPlayNetworking.send(x, new Net.Actors(list));
    }

    // ------------------------------------------------------------------ cutscenes

    /**
     * A camera sequence for the scene's players: shots that glide from one spot to another while
     * looking at a point, with lines and sounds timed to them. Controls and the HUD pause meanwhile.
     */
    private void cutscene(ServerPlayerEntity p, Scene sc, Mission m, JsonObject o) {
        ServerWorld w = p.getServerWorld();
        List<Net.Shot> shots = new ArrayList<>();
        List<ServerPlayerEntity> mem = sc == null ? List.of(p) : members(sc);
        long at = 0;
        for (JsonElement el : o.getAsJsonArray("shots")) {
            JsonObject s = el.getAsJsonObject();
            double h = s.has("h") ? s.get("h").getAsDouble() : 1.6, h2 = s.has("h2") ? s.get("h2").getAsDouble() : h;
            double lh = s.has("lh") ? s.get("lh").getAsDouble() : 1.5, lh2 = s.has("lh2") ? s.get("lh2").getAsDouble() : lh;
            Vec3d from = resolve(w, m, s.get("from")).add(0, h, 0);
            Vec3d to = s.has("to") ? resolve(w, m, s.get("to")).add(0, h2, 0) : from;
            Vec3d look = s.has("look") ? resolve(w, m, s.get("look")).add(0, lh, 0) : from.add(0, 0, 1);
            Vec3d look2 = s.has("lookTo") ? resolve(w, m, s.get("lookTo")).add(0, lh2, 0) : look;
            float secs = s.has("seconds") ? s.get("seconds").getAsFloat() : 3;
            shots.add(new Net.Shot(from.x, from.y, from.z, to.x, to.y, to.z, look.x, look.y, look.z, look2.x, look2.y, look2.z, secs));
            int ticksAt = (int) (at / 50);
            if (s.has("say")) {
                JsonObject say = s.getAsJsonObject("say");
                String who = say.has("who") ? say.get("who").getAsString() : null;
                String text = say.get("text").getAsString();
                int later = ticksAt + (say.has("after") ? (int) (say.get("after").getAsFloat() * 20) : 5);
                AotRpg.SCHEDULER.later(later, () -> line(sc, p, who, text));
            }
            if (s.has("sound")) {
                String sid = s.get("sound").getAsString();
                SoundEvent se = Registries.SOUND_EVENT.get(Identifier.of(sid));
                if (se != null) AotRpg.SCHEDULER.later(ticksAt + 1, () -> {
                    for (ServerPlayerEntity x : mem) x.playSoundToPlayer(se, SoundCategory.AMBIENT, 1f, 1f);
                });
            }
            at += (long) (secs * 1000);
        }
        boolean fade = !o.has("fade") || o.get("fade").getAsBoolean();
        for (ServerPlayerEntity x : mem) {
            if (ServerPlayNetworking.canSend(x, Net.Cutscene.ID)) ServerPlayNetworking.send(x, new Net.Cutscene(shots, fade));
        }
    }

    // ------------------------------------------------------------------ quest givers

    /** Each player's quest givers standing in the world: mission id -> entity. */
    private final Map<UUID, Map<String, UUID>> givers = new HashMap<>();

    private Vec3d giverAt(ServerWorld w, Mission m) {
        JsonObject spec = new JsonObject();
        JsonElement at = m.giver.has("at") ? m.giver.get("at") : new JsonArray();
        if (at.isJsonObject()) spec = at.getAsJsonObject().deepCopy();
        else spec.add("rel", at);
        if (m.giver.has("place") && !spec.has("place")) spec.addProperty("place", m.giver.get("place").getAsString());
        return resolve(w, m, spec);
    }

    /** Givers of the quests on offer stand at their spots, marked with a gold "!", while you're nearby. */
    private void givers(ServerPlayerEntity p, Profile pr) {
        ServerWorld w = server.getOverworld();
        Map<String, UUID> mine = givers.computeIfAbsent(p.getUuid(), k -> new HashMap<>());
        for (var it = mine.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Mission m = missions.get(e.getKey());
            Entity en = w.getEntity(e.getValue());
            boolean keep = m != null && pr.story.available.contains(m.id) && en != null && en.isAlive()
                && p.getWorld() == w && p.getPos().squaredDistanceTo(en.getPos()) < 110 * 110;
            if (!keep) {
                if (en != null) {
                    phased.remove(en.getId());
                    en.discard();
                }
                it.remove();
            }
        }
        if (p.getWorld() != w) return;
        boolean changed = false;
        for (String id : pr.story.available) {
            Mission m = missions.get(id);
            if (m == null || m.giver == null || mine.containsKey(id)) continue;
            Vec3d at = giverAt(w, m);
            if (p.getPos().squaredDistanceTo(at) > 72 * 72) continue;
            String who = m.giver.get("actor").getAsString();
            ActorDef def = actors.getOrDefault(who, new ActorDef());
            VillagerEntity v = EntityType.VILLAGER.create(w);
            if (v == null) continue;
            at = settle(w, at, null, null);
            v.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0);
            v.setAiDisabled(true);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setCustomName(Text.literal("! ").formatted(Formatting.GOLD, Formatting.BOLD).append(Text.literal(def.name).formatted(Formatting.WHITE)));
            v.setCustomNameVisible(true);
            v.addCommandTag(ACTOR);
            v.addCommandTag("aot_giver:" + id);
            phased.put(v.getId(), p.getUuid());
            w.spawnEntity(v);
            phased.put(v.getId(), p.getUuid());
            float yaw = (float) (MathHelper.atan2(p.getZ() - v.getZ(), p.getX() - v.getX()) * MathHelper.DEGREES_PER_RADIAN) - 90;
            v.setYaw(yaw);
            v.setHeadYaw(yaw);
            v.setBodyYaw(yaw);
            mine.put(id, v.getUuid());
            changed = true;
        }
        sendActorsTo(p);
        if (changed) AotRpg.QUESTS.markers(p, true);
    }

    /** For /story actors: every story NPC this player has, where it is, and whether it's really there. */
    public List<String> actorReport(ServerPlayerEntity p) {
        List<String> out = new ArrayList<>();
        UUID host = guestOf.getOrDefault(p.getUuid(), p.getUuid());
        Scene sc = scenes.get(host);
        if (sc != null) {
            out.add("Scene " + sc.mission + " step " + sc.step + " spawned=" + sc.spawned);
            for (var a : sc.actors.entrySet()) out.add("  " + a.getKey() + ": " + where(p, a.getValue()));
        }
        Map<String, UUID> mine = givers.get(host);
        if (mine != null) for (var g : mine.entrySet()) out.add("  giver " + g.getKey() + ": " + where(p, g.getValue()));
        if (out.isEmpty()) out.add("No story NPCs.");
        return out;
    }

    private String where(ServerPlayerEntity p, UUID u) {
        Entity e = null;
        for (ServerWorld w : server.getWorlds()) if (e == null) e = w.getEntity(u);
        if (e == null) return "MISSING";
        return String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f (%.0fm, %s, visible=%s)", e.getX(), e.getY(), e.getZ(),
            Math.sqrt(e.squaredDistanceTo(p)), e.isAlive() ? "alive" : "dead", visibleTo(e, p));
    }

    private String giverOf(UUID host, Entity e) {
        Map<String, UUID> mine = givers.get(host);
        if (mine == null) return null;
        for (var g : mine.entrySet()) if (g.getValue().equals(e.getUuid())) return g.getKey();
        return null;
    }

    /** Pick up an offered quest (one at a time). */
    private void accept(ServerPlayerEntity p, String id) {
        Profile pr = pr(p);
        Mission cur = current(pr);
        if (cur != null && !cur.id.equals(id)) {
            Notify.toast(p, Text.literal("You're already on a quest").formatted(Formatting.RED),
                Text.literal("Finish or abandon \"" + cur.title + "\" first (journal)"), 0xC0463A, null, "story");
            return;
        }
        if (!pr.story.available.contains(id)) return;
        startMission(p, pr, id);
    }

    /** Drop the current quest: it goes back to its giver, to pick up again later. */
    public void abandon(ServerPlayerEntity p) {
        Profile pr = pr(p);
        Mission m = current(pr);
        if (m == null) return;
        String head = m.giver != null ? m.id : headOf.get(m.id);
        if (head == null) {
            Notify.toast(p, Text.literal("This one can't be abandoned").formatted(Formatting.GRAY), Text.literal(m.title), 0x8F8A7A, null, "story");
            return;
        }
        forget(p);
        pr.story.mission = "";
        pr.story.step = 0;
        pr.story.available.add(head);
        if (pr.story.back != null) {
            p.teleport(server.getOverworld(), pr.story.back[0], pr.story.back[1], pr.story.back[2], p.getYaw(), p.getPitch());
            pr.story.back = null;
        }
        AotRpg.PROFILES.save(p.getUuid());
        Notify.toast(p, Text.literal("Quest abandoned").formatted(Formatting.GOLD), Text.literal(missions.get(head).title + " · pick it up again any time"), 0xE0B96A, "minecraft:writable_book", "story");
        send(p, pr);
    }

    /** Map marks for the quests on offer. */
    public void markers(ServerPlayerEntity p, List<Net.Marker> list) {
        Profile pr = pr(p);
        if (!pr.created || server == null) return;
        ServerWorld w = server.getOverworld();
        for (String id : pr.story.available) {
            Mission m = missions.get(id);
            if (m == null || m.giver == null) continue;
            Vec3d at = giverAt(w, m);
            list.add(new Net.Marker("giver", "! " + m.title, (int) at.x, (int) at.y, (int) at.z, 0xF2C14E));
        }
    }

    // ------------------------------------------------------------------ the flow

    /** Starts a character's story at their origin's opening. */
    private void begin(ServerPlayerEntity p, Profile pr) {
        State s = pr.story;
        s.begun = true;
        // Everyone wakes up in the training corps; your origin comes back later, as a memory.
        String first = starts.get("default");
        if (first == null || !missions.containsKey(first)) return;
        startMission(p, pr, first);
    }

    private void startMission(ServerPlayerEntity p, Profile pr, String id) {
        Mission m = missions.get(id);
        if (m == null) return;
        Scene old = scenes.remove(p.getUuid());
        if (old != null) {
            clearScene(old);
            for (UUID g : old.guests) guestOf.remove(g);
        }
        pr.story.mission = id;
        pr.story.step = 0;
        pr.story.available.remove(id);
        ServerWorld w = server.getOverworld();
        if (m.memory && p.getWorld() == w) pr.story.back = new double[] {p.getX(), p.getY(), p.getZ()};
        if (m.startAt != null) {
            Vec3d to = resolve(w, m, m.startAt);
            p.stopRiding();
            p.teleport(w, to.x, to.y, to.z, p.getYaw(), p.getPitch());
        }
        AotRpg.PROFILES.save(p.getUuid());
        if (m.memory && ServerPlayNetworking.canSend(p, Net.StoryCard.ID)) ServerPlayNetworking.send(p, new Net.StoryCard("A memory", m.chapter));
        Notify.toast(p, Text.literal(m.title).formatted(Formatting.GOLD), Text.literal(m.chapter + " · " + threadName(m.thread)), 0xE0B96A, "minecraft:writable_book", "story");
        send(p, pr);
    }

    private static String threadName(String t) {
        return switch (t) {
            case "survival" -> "Survival";
            case "truth" -> "Truth";
            default -> "People";
        };
    }

    public void tick(ServerPlayerEntity p, Profile pr, int ticks) {
        if (!pr.created || server == null) return;
        State s = pr.story;
        if (ticks % 10 == 0) {
            // Stepping into or out of a story moment: who can see whom changes, so look again now.
            UUID now = moment(p), was = lastMoment.get(p.getUuid());
            if (!java.util.Objects.equals(now, was)) {
                if (now == null) lastMoment.remove(p.getUuid());
                else lastMoment.put(p.getUuid(), now);
                refreshPlayers(p);
            }
        }
        if (s.mission.startsWith("@")) {
            startMission(p, pr, s.mission.substring(1));
            return;
        }
        if (!s.begun && s.mission.isEmpty()) {
            if (ticks % 20 == 0) begin(p, pr);
            return;
        }
        if (ticks % 20 == 3) givers(p, pr);
        Mission m = current(pr);
        if (m == null) return;
        Scene sc = scenes.computeIfAbsent(p.getUuid(), Scene::new);
        if (sc.mission == null || !sc.mission.equals(m.id) || sc.step != s.step) enterStep(p, pr, sc, m);
        Step st = step(pr);
        if (st == null) return;
        if (st.when != null && !test(p, st.when)) {
            pr.story.step++;
            if (pr.story.step >= m.steps.size()) completeMission(p, pr, sc, m);
            return;
        }
        ServerWorld w = p.getServerWorld();
        // Spawn the step's cast once the host is near where it happens.
        if (!sc.spawned) {
            Vec3d anchor = anchor(w, m, st);
            if (anchor == null || p.getPos().squaredDistanceTo(anchor) < 72 * 72) {
                sc.spawned = true;
                for (JsonObject o : st.spawn) {
                    if (o.has("actor")) spawnActor(sc, m, w, o);
                    else if (o.has("titans")) spawnTitans(sc, m, w, o, p);
                }
                if (sc.checkpoint == null) sc.checkpoint = p.getPos();
                apply(p, sc, m, st.start);
                if (s.mission.startsWith("@")) return;
            }
            return;
        }
        animate(sc, p, ticks);
        if (ticks % 20 == 0) sendActors(sc);
        if (ticks % 40 == 0) inviteParty(p, sc, m);
        checkGoal(p, pr, sc, m, st);
    }

    private Vec3d anchor(ServerWorld w, Mission m, Step st) {
        if (st.goal.has("goto")) return resolve(w, m, st.goal.get("goto"));
        for (JsonObject o : st.spawn) if (o.has("at")) return resolve(w, m, o.get("at"));
        return m.place == null ? null : resolve(w, m, new JsonArray());
    }

    private void enterStep(ServerPlayerEntity p, Profile pr, Scene sc, Mission m) {
        boolean newMission = sc.mission == null || !sc.mission.equals(m.id);
        if (newMission) clearScene(sc);
        else clearTitans(sc);
        sc.mission = m.id;
        sc.step = pr.story.step;
        sc.spawned = false;
        sc.stepAt = System.currentTimeMillis();
        sc.goalMet = false;
        sc.talkedTo = null;
        sc.checkpoint = p.getPos();
        closeDialogue(sc);
        AotRpg.QUESTS.sendObjective(p);
        AotRpg.QUESTS.markers(p, true);
        AotRpg.QUESTS.send(p);
        journal(p);
    }

    /** Actors walking, following, keeping watch; scene titans hunting the scene. */
    private void animate(Scene sc, ServerPlayerEntity host, int ticks) {
        long now = System.currentTimeMillis();
        for (var it = sc.expire.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (now > e.getValue()) {
                it.remove();
                despawnActor(sc, e.getKey());
            }
        }
        fight(sc, host, ticks);
        // Followers keep their own place behind you, in pairs, so they never walk inside each other.
        List<String> fol = new ArrayList<>(sc.followers);
        java.util.Collections.sort(fol);
        double hy = Math.toRadians(host.getYaw());
        double bx = Math.sin(hy), bz = -Math.cos(hy), sx = -bz, sz = bx;
        for (int i = 0; i < fol.size(); i++) {
            String id = fol.get(i);
            if (sc.fighting.contains(id)) continue;
            Entity e = actorEntity(sc, id);
            if (e == null) continue;
            double d = e.squaredDistanceTo(host);
            double back = 2.4 + (i / 2) * 1.7, side = (i % 2 == 0 ? -1 : 1) * (fol.size() == 1 ? 0 : 0.9);
            Vec3d spot = host.getPos().add(bx * back + sx * side, 0, bz * back + sz * side);
            if (d > 40 * 40 || e.getWorld() != host.getWorld()) e.requestTeleport(spot.x, host.getY(), spot.z);
            else {
                double hx = spot.x - e.getX(), hz = spot.z - e.getZ();
                if (hx * hx + hz * hz > 0.6 * 0.6) step(e, spot, d > 8 * 8 ? 0.4 : 0.28);
                else if (ticks % 10 == 0) faceTo(e, host.getEyePos());
            }
        }
        List<String> arrived = new ArrayList<>();
        for (var it = sc.walks.entrySet().iterator(); it.hasNext(); ) {
            var wk = it.next();
            String id = wk.getKey();
            if (sc.fighting.contains(id)) continue;
            Entity e = actorEntity(sc, id);
            if (e == null) {
                it.remove();
                continue;
            }
            Vec3d to = wk.getValue();
            double hx = to.x - e.getX(), hz = to.z - e.getZ();
            if (hx * hx + hz * hz < 0.0025) {
                // There: stand exactly on the mark and turn the way the scene wants.
                e.refreshPositionAndAngles(to.x, e.getY(), to.z, e.getYaw(), 0);
                Vec3d face = sc.faces.remove(id);
                if (face != null) faceTo(e, face);
                it.remove();
                arrived.add(id);
                continue;
            }
            step(e, to, 0.22);
        }
        long nowMs = System.currentTimeMillis();
        for (String id : arrived) {
            PathPt pt = sc.arrive.remove(id);
            if (pt == null) continue;
            sc.pauses.put(id, nowMs + pt.pauseMs());
            if (pt.say() != null) line(sc, host, pt.say().has("who") ? pt.say().get("who").getAsString() : id, pt.say().get("text").getAsString());
            if (pt.talk()) {
                Entity e = actorEntity(sc, id);
                if (e != null) interact(host, e);
            }
        }
        for (var pe : sc.paths.entrySet()) {
            String id = pe.getKey();
            if (pe.getValue().isEmpty() || sc.walks.containsKey(id) || sc.arrive.containsKey(id)) continue;
            if (nowMs < sc.pauses.getOrDefault(id, 0L)) continue;
            PathPt pt = pe.getValue().poll();
            sc.walks.put(id, pt.at());
            if (pt.face() != null) sc.faces.put(id, pt.face());
            sc.arrive.put(id, pt);
        }
        sc.paths.values().removeIf(java.util.ArrayDeque::isEmpty);
        for (String id : sc.patrols) {
            Entity e = actorEntity(sc, id);
            if (e == null) continue;
            float yaw = e.getBodyYaw() + (float) Math.sin(ticks / 40.0) * 70;
            e.setHeadYaw(yaw);
            e.setYaw(e.getBodyYaw());
        }
        if (ticks % 5 == 0) {
            // Nobody carries a story character off, and an early mission's titans can't hold you.
            for (String id : sc.actors.keySet()) {
                Entity e = actorEntity(sc, id);
                if (e != null && e.hasVehicle()) e.stopRiding();
            }
            for (UUID u : sc.titans) {
                Entity t = host.getServerWorld().getEntity(u);
                if (t != null && gentle(t) && t.hasPassengers()) {
                    for (Entity pass : new ArrayList<>(t.getPassengerList())) {
                        pass.stopRiding();
                        if (pass instanceof ServerPlayerEntity sp) sp.sendMessage(Text.literal("You twist free of its grip!").formatted(Formatting.GOLD), true);
                    }
                }
            }
        }
        if (ticks % 20 == 0) calm(sc, host);
        if (ticks % 10 == 0) {
            List<ServerPlayerEntity> mem = members(sc);
            for (UUID u : sc.titans) {
                Entity t = host.getServerWorld().getEntity(u);
                if (!(t instanceof MobEntity mob) || !t.isAlive()) continue;
                ServerPlayerEntity best = null;
                double bd = Double.MAX_VALUE;
                for (ServerPlayerEntity x : mem) {
                    double d = x.squaredDistanceTo(t);
                    if (d < bd && !x.isSpectator() && !AotRpg.DOWNED.isDowned(x)) {
                        bd = d;
                        best = x;
                    }
                }
                if (best != null && (mob.getTarget() != best || mob.getTarget() == null)) mob.setTarget(best);
                else if (best == null && mob.getTarget() != null) mob.setTarget(null);
            }
        }
    }

    /**
     * A story scene keeps its own titans: while you're at the scene, wandering titans (not event,
     * raid or hunt titans) within 64 blocks of it are sent off, so it isn't gatecrashed. Anywhere
     * else the world's titans are left alone.
     */
    private void calm(Scene sc, ServerPlayerEntity host) {
        // Only where the scene is actually playing out: around the step's spot, while you're there.
        if (!sc.spawned || sc.mission == null) return;
        Mission m = missions.get(sc.mission);
        Step st = m == null ? null : step(AotRpg.PROFILES.get(host.getUuid()));
        if (st == null) return;
        ServerWorld w = host.getServerWorld();
        Vec3d anchor = anchor(w, m, st);
        if (anchor == null || host.getPos().squaredDistanceTo(anchor) > 80 * 80) return;
        List<Entity> gone = new ArrayList<>();
        net.minecraft.util.math.Box area = new net.minecraft.util.math.Box(anchor.x - 64, anchor.y - 48, anchor.z - 64, anchor.x + 64, anchor.y + 64, anchor.z + 64);
        List<ServerPlayerEntity> mem = members(sc);
        for (Entity e : w.getEntitiesByClass(Entity.class, area, TitanGuard::wanderingTitan)) {
            if (phased.containsKey(e.getId())) continue;
            // Someone outside the scene can see it or is fighting it: it stays in their world.
            boolean watched = false;
            for (ServerPlayerEntity o : w.getPlayers()) {
                if (!mem.contains(o) && o.squaredDistanceTo(e) < 128 * 128) {
                    watched = true;
                    break;
                }
            }
            if (!watched) gone.add(e);
        }
        for (Entity e : gone) {
            particles(sc, host, w, ParticleTypes.CAMPFIRE_COSY_SMOKE, e.getX(), e.getY() + e.getHeight() / 2, e.getZ(), 12, e.getWidth() / 2, e.getHeight() / 3, e.getWidth() / 2, 0.02);
            e.discard();
        }
    }

    /**
     * Fell during a scene: wake where the step began and try it again (fresh titans, same scene).
     * Returns true if the player was placed.
     */
    public boolean respawnInScene(ServerPlayerEntity p) {
        UUID host = guestOf.getOrDefault(p.getUuid(), p.getUuid());
        Scene sc = scenes.get(host);
        if (sc == null || sc.checkpoint == null || sc.mission == null) return false;
        ServerWorld w = server.getOverworld();
        BlockPos at = Safe.landing(w, (int) Math.floor(sc.checkpoint.x), (int) Math.floor(sc.checkpoint.y), (int) Math.floor(sc.checkpoint.z));
        p.teleport(w, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, p.getYaw(), 0);
        if (host.equals(p.getUuid())) {
            clearTitans(sc);
            sc.spawned = false;
            sc.stepAt = System.currentTimeMillis();
            sc.dialogue = null;
            closeDialogue(sc);
        }
        Notify.toast(p, Text.literal("You come to where it began").formatted(Formatting.GOLD), Text.literal("Try again"), 0xE0B96A, "minecraft:red_bed", "story");
        return true;
    }

    /** Moves an AI-less actor a step toward a point, facing where it walks. */
    /**
     * Cadets and soldiers in a scene fight its titans: blades out, up at the nape on their gear,
     * circling behind and cutting. They wear a titan down but leave the last one for you to finish.
     */
    private void fight(Scene sc, ServerPlayerEntity host, int ticks) {
        ServerWorld w = host.getServerWorld();
        List<LivingEntity> live = new ArrayList<>();
        for (UUID u : sc.titans) {
            if (w.getEntity(u) instanceof LivingEntity t && t.isAlive()) live.add(t);
        }
        for (var a : sc.actors.entrySet()) {
            String id = a.getKey();
            ActorDef def = actors.get(id);
            if (def == null || def.kit == null || !(def.kit.startsWith("cadet") || def.kit.startsWith("soldier"))) continue;
            if (!(w.getEntity(a.getValue()) instanceof MobEntity v)) continue;
            LivingEntity t = null;
            double bd = 48 * 48;
            for (LivingEntity x : live) {
                double d = x.squaredDistanceTo(v);
                if (d < bd) {
                    bd = d;
                    t = x;
                }
            }
            if (t == null) {
                if (sc.fighting.remove(id)) {
                    v.setNoGravity(false);
                    Kit.arm(v, false);
                }
                continue;
            }
            if (sc.fighting.add(id)) {
                Kit.arm(v, true);
                v.setNoGravity(true);
                sc.walks.remove(id);
            }
            int seed = id.hashCode() & 1023;
            double ang = Math.toRadians(t.getBodyYaw() + 180 + Math.sin(ticks / 25.0 + seed) * 55);
            double r = t.getWidth() / 2 + 1.4;
            Vec3d spot = new Vec3d(t.getX() - Math.sin(ang) * r, t.getY() + t.getHeight() * 0.82, t.getZ() + Math.cos(ang) * r);
            Vec3d d = spot.subtract(v.getPos());
            double len = d.length();
            Vec3d next = len < 0.6 ? spot : v.getPos().add(d.multiply(0.6 / len));
            v.refreshPositionAndAngles(next.x, next.y, next.z, v.getYaw(), 0);
            faceTo(v, new Vec3d(t.getX(), v.getY(), t.getZ()));
            if (ticks % 4 == seed % 4) particles(sc, host, w, ParticleTypes.CLOUD, v.getX(), v.getY() + 0.9, v.getZ(), 1, 0.1, 0.1, 0.1, 0.01);
            if (len < 2.5 && ticks % 32 == seed % 32) {
                v.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                particles(sc, host, w, ParticleTypes.SWEEP_ATTACK, t.getX() - Math.sin(ang) * (r - 1), spot.y, t.getZ() + Math.cos(ang) * (r - 1), 1, 0, 0, 0, 0);
                sound(sc, host, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, v.getPos(), 0.8f, 1.1f);
                float max = t.getMaxHealth();
                // The last titan standing is yours: they only wear it down.
                float floor = live.size() > 1 ? 0 : max * 0.25f;
                float left = Math.max(floor, t.getHealth() - max * 0.07f);
                if (left < t.getHealth()) {
                    if (left <= 0) {
                        var src = v.getDamageSources().mobAttack(v);
                        t.setHealth(0);
                        t.onDeath(src);
                        particles(sc, host, w, ParticleTypes.CLOUD, t.getX(), t.getY() + t.getHeight() * 0.8, t.getZ(), 30, 0.6, 0.6, 0.6, 0.05);
                    } else {
                        t.setHealth(left);
                        particles(sc, host, w, ParticleTypes.CLOUD, spot.x, spot.y, spot.z, 6, 0.3, 0.3, 0.3, 0.02);
                    }
                }
            }
        }
    }

    private static void step(Entity e, Vec3d to, double speed) {
        Vec3d d = to.subtract(e.getPos());
        double len = Math.hypot(d.x, d.z);
        if (len < 0.1) return;
        double k = Math.min(speed, len) / len;
        double nx = e.getX() + d.x * k, nz = e.getZ() + d.z * k;
        double ny = e.getY();
        if (e.getWorld() instanceof ServerWorld w) {
            BlockPos g = Safe.near(w, (int) Math.floor(nx), (int) Math.floor(ny), (int) Math.floor(nz));
            if (g != null && Math.abs(g.getY() - ny) <= 1.2) ny = g.getY();
        }
        float yaw = (float) (MathHelper.atan2(d.z, d.x) * MathHelper.DEGREES_PER_RADIAN) - 90;
        e.refreshPositionAndAngles(nx, ny, nz, yaw, 0);
        e.setHeadYaw(yaw);
        if (e instanceof LivingEntity le) le.setBodyYaw(yaw);
    }

    private void checkGoal(ServerPlayerEntity p, Profile pr, Scene sc, Mission m, Step st) {
        JsonObject g = st.goal;
        long now = System.currentTimeMillis();
        ServerWorld w = p.getServerWorld();
        boolean met = sc.goalMet;
        // Stealth: being spotted sends the scene back to where the step began.
        if (g.has("unseen") && g.get("unseen").getAsBoolean() && AotRpg.WITNESS.alerted(p)) {
            spotted(p, sc);
            return;
        }
        if (g.has("goto")) {
            Vec3d at = resolve(w, m, g.get("goto"));
            double r = g.has("radius") ? g.get("radius").getAsDouble() : 5;
            for (ServerPlayerEntity x : members(sc)) if (x.getPos().squaredDistanceTo(at) < r * r) met = true;
            if (g.has("timeout")) {
                if (met && g.has("success")) apply(p, sc, m, list(g.getAsJsonArray("success")));
                else if (!met && now - sc.stepAt > g.get("timeout").getAsLong() * 1000) {
                    met = true;
                    if (g.has("fail")) apply(p, sc, m, list(g.getAsJsonArray("fail")));
                }
            }
        } else if (g.has("kill")) {
            sc.titans.removeIf(u -> {
                Entity t = w.getEntity(u);
                return t == null || !t.isAlive();
            });
            boolean timeout = g.has("timeout") && now - sc.stepAt > g.get("timeout").getAsLong() * 1000;
            if (sc.titansSpawned && sc.titans.isEmpty()) {
                met = true;
                if (g.has("success")) apply(p, sc, m, list(g.getAsJsonArray("success")));
            } else if (timeout) {
                met = true;
                if (g.has("fail")) apply(p, sc, m, list(g.getAsJsonArray("fail")));
                clearTitans(sc);
            }
        } else if (g.has("wait")) {
            met = now - sc.stepAt > g.get("wait").getAsDouble() * 1000;
        } else if (g.has("wear")) {
            String want = g.get("wear").getAsString();
            for (var slot : new net.minecraft.entity.EquipmentSlot[] {net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.CHEST,
                net.minecraft.entity.EquipmentSlot.FEET, net.minecraft.entity.EquipmentSlot.MAINHAND}) {
                if (Registries.ITEM.getId(p.getEquippedStack(slot).getItem()).getPath().contains(want)) met = true;
            }
            if (g.has("timeout") && now - sc.stepAt > g.get("timeout").getAsLong() * 1000) met = true;
        }
        // talk/take goals are met by interacting (sc.goalMet)
        if (met) completeStep(p, pr, sc, m, st);
    }

    private static List<JsonObject> list(JsonArray a) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : a) out.add(e.getAsJsonObject());
        return out;
    }

    private void spotted(ServerPlayerEntity p, Scene sc) {
        for (ServerPlayerEntity x : members(sc)) {
            x.playSoundToPlayer(SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 1f, 0.8f);
            Notify.toast(x, Text.literal("You've been spotted!").formatted(Formatting.RED), Text.literal("Back to the shadows · try again"), 0xC0463A, null, "story");
            if (sc.checkpoint != null) x.teleport(x.getServerWorld(), sc.checkpoint.x, sc.checkpoint.y, sc.checkpoint.z, x.getYaw(), x.getPitch());
        }
        AotRpg.WITNESS.calm(p);
        sc.stepAt = System.currentTimeMillis();
    }

    private void completeStep(ServerPlayerEntity p, Profile pr, Scene sc, Mission m, Step st) {
        apply(p, sc, m, st.done);
        for (UUID g : sc.guests) {
            ServerPlayerEntity gp = server.getPlayerManager().getPlayer(g);
            if (gp != null) pr(gp).story.flags.add("witnessed:" + m.id);
        }
        if (pr.story.mission.startsWith("@")) return;
        pr.story.step++;
        if (pr.story.step >= m.steps.size()) {
            completeMission(p, pr, sc, m);
            return;
        }
        AotRpg.PROFILES.save(p.getUuid());
        p.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.MASTER, 0.5f, 1.4f);
    }

    private void completeMission(ServerPlayerEntity p, Profile pr, Scene sc, Mission m) {
        State s = pr.story;
        s.done.add(m.id);
        pr.chapter = Math.max(pr.chapter, 1 + s.done.size());
        apply(p, sc, m, m.complete);
        if (m.xp > 0) AotRpg.PROGRESSION.addXp(p, pr, m.xp);
        // Guests share in the reward.
        for (UUID g : sc.guests) {
            ServerPlayerEntity gp = server.getPlayerManager().getPlayer(g);
            if (gp != null && m.xp > 0) AotRpg.PROGRESSION.addXp(gp, pr(gp), m.xp / 2);
        }
        Notify.toast(p, Text.literal("Mission complete").formatted(Formatting.GOLD, Formatting.BOLD), Text.literal(m.title), 0xE0B96A, "minecraft:writable_book", "story");
        p.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.7f, 1f);
        String forced = s.mission.startsWith("@") ? s.mission.substring(1) : null;
        List<String> next = new ArrayList<>();
        if (forced != null) next.add(forced);
        for (JsonObject o : m.nextIf) {
            if (test(p, o.has("if") ? o.getAsJsonObject("if") : null)) {
                next.add(o.get("then").getAsString());
                break;
            }
        }
        if (m.next != null) next.add(m.next);
        for (JsonObject o : m.unlock) {
            if (test(p, o.has("if") ? o.getAsJsonObject("if") : null)) next.add(o.get("id").getAsString());
        }
        clearScene(sc);
        for (UUID g : sc.guests) guestOf.remove(g);
        sc.guests.clear();
        scenes.remove(p.getUuid());
        s.mission = "";
        s.step = 0;
        // A memory ends where it began: back to the present.
        if (m.returnHome) s.flags.add("prologue_done");
        if (m.returnHome && s.back != null) {
            p.teleport(server.getOverworld(), s.back[0], s.back[1], s.back[2], p.getYaw(), p.getPitch());
            s.back = null;
            if (ServerPlayNetworking.canSend(p, Net.StoryCard.ID)) ServerPlayNetworking.send(p, new Net.StoryCard("The present", "The 104th Training Corps"));
        }
        String chained = null;
        for (String id : next) {
            Mission nm = missions.get(id);
            if (nm == null || s.done.contains(id)) continue;
            if (nm.chain || nm.giver == null) {
                if (chained == null) chained = id;
                continue;
            }
            if (s.available.add(id)) {
                ActorDef giver = actors.get(nm.giver.get("actor").getAsString());
                Notify.toast(p, Text.literal("New quest: " + nm.title).formatted(Formatting.GOLD),
                    Text.literal(giver == null ? "Check your journal" : "Talk to " + giver.name + " (! on your map)"), 0xE0B96A, "minecraft:writable_book", "quest:" + id);
            }
        }
        if (chained != null) startMission(p, pr, chained);
        else {
            AotRpg.PROFILES.save(p.getUuid());
            send(p, pr);
        }
    }

    // ------------------------------------------------------------------ talking

    /** A player used an entity: a story actor starts its talk (or take) goal. Returns true if handled. */
    public boolean interact(ServerPlayerEntity p, Entity e) {
        UUID host = phased.get(e.getId());
        if (host == null) return false;
        if (!visibleTo(e, p)) return true;
        ServerPlayerEntity hp = server.getPlayerManager().getPlayer(host);
        if (hp == null) return true;
        String offer = giverOf(host, e);
        if (offer != null) {
            Mission om = missions.get(offer);
            Scene gs = scenes.computeIfAbsent(host, Scene::new);
            if (gs.dialogue != null) return true;
            gs.dialogue = om.giver.has("dialogue") ? om.giver.get("dialogue").getAsString() : "giver:" + offer;
            Dialogue d = dialogues.get(gs.dialogue);
            if (d == null) {
                gs.dialogue = null;
                return true;
            }
            gs.node = d.start;
            gs.talkedTo = null;
            gs.suggestions.clear();
            enterNode(hp, gs, om);
            return true;
        }
        Scene sc = scenes.get(host);
        if (sc == null) return true;
        Profile pr = pr(hp);
        Mission m = current(pr);
        Step st = step(pr);
        if (m == null || st == null) return true;
        String id = null;
        for (var a : sc.actors.entrySet()) if (a.getValue().equals(e.getUuid())) id = a.getKey();
        if (id == null) return true;
        JsonObject g = st.goal;
        if (g.has("take") && g.get("take").getAsString().equals(id)) {
            if (g.has("unseen") && g.get("unseen").getAsBoolean() && AotRpg.WITNESS.seenByAny(p)) {
                AotRpg.WITNESS.alert(p);
                spotted(hp, sc);
                return true;
            }
            despawnActor(sc, id);
            sc.goalMet = true;
            completeStep(hp, pr, sc, m, st);
            return true;
        }
        if (g.has("talk") && g.get("talk").getAsString().equals(id) && g.has("dialogue")) {
            if (sc.dialogue == null) {
                Dialogue d = dialogues.get(g.get("dialogue").getAsString());
                if (d == null) return true;
                sc.dialogue = g.get("dialogue").getAsString();
                sc.node = d.start;
                sc.talkedTo = id;
                sc.suggestions.clear();
                facePlayer(e, sc);
                enterNode(hp, sc, m);
            } else {
                sendDialogue(sc);
            }
            return true;
        }
        // Just a word in passing.
        ActorDef def = actors.get(id);
        if (def != null) p.sendMessage(Text.literal(def.name).formatted(Formatting.GOLD).append(Text.literal(" is busy.").formatted(Formatting.GRAY)), true);
        return true;
    }

    private void enterNode(ServerPlayerEntity host, Scene sc, Mission m) {
        Dialogue d = dialogues.get(sc.dialogue);
        Node n = d == null ? null : d.nodes.get(sc.node);
        if (n == null) {
            endDialogue(host, sc, m);
            return;
        }
        apply(host, sc, m, n.effects);
        sc.suggestions.clear();
        sendDialogue(sc);
    }

    private void sendDialogue(Scene sc) {
        ServerPlayerEntity host = server.getPlayerManager().getPlayer(sc.host);
        if (host == null || sc.dialogue == null) return;
        Dialogue d = dialogues.get(sc.dialogue);
        Node n = d.nodes.get(sc.node);
        if (n == null) return;
        ActorDef sp = n.speaker == null ? null : actors.get(n.speaker);
        String speaker = n.speaker == null ? "" : n.speaker.equals("you") ? pr(host).name : sp == null ? n.speaker : sp.name;
        String skin = sp == null ? "" : sp.skin;
        List<Net.ChoiceView> choices = new ArrayList<>();
        for (int i = 0; i < n.choices.size(); i++) {
            Choice c = n.choices.get(i);
            boolean ok = test(host, c.requires);
            if (!ok && c.requires != null && c.requires.has("hidden")) continue;
            StringBuilder by = new StringBuilder();
            for (var s : sc.suggestions.entrySet()) {
                if (s.getValue() != i) continue;
                Profile gp = AotRpg.PROFILES.get(s.getKey());
                if (by.length() > 0) by.append(", ");
                by.append(gp.firstName.isEmpty() ? gp.name : gp.firstName);
            }
            choices.add(new Net.ChoiceView(i, fill(c.text, host), c.tag == null ? "" : c.tag, ok, by.toString()));
        }
        String text = fill(n.text, host);
        for (ServerPlayerEntity x : members(sc)) {
            if (!ServerPlayNetworking.canSend(x, Net.DialogueView.ID)) {
                x.sendMessage(Text.literal(speaker + ": ").formatted(Formatting.GOLD).append(Text.literal(text)), false);
                continue;
            }
            ServerPlayNetworking.send(x, new Net.DialogueView(true, speaker, skin, text, choices, x == host, pr(host).name));
        }
    }

    /** {name}, {first}, {origin} in lines. */
    private static String fill(String t, ServerPlayerEntity p) {
        Profile pr = pr(p);
        return t.replace("{name}", pr.name).replace("{first}", pr.firstName).replace("{family}", pr.familyName)
            .replace("{origin}", pr.origin == null ? "" : pr.origin.title);
    }

    /** The host picks a choice (or continues); a guest's pick is a suggestion. */
    public void pick(ServerPlayerEntity p, int index) {
        UUID host = guestOf.getOrDefault(p.getUuid(), p.getUuid());
        Scene sc = scenes.get(host);
        if (sc == null || sc.dialogue == null) return;
        ServerPlayerEntity hp = server.getPlayerManager().getPlayer(host);
        if (hp == null) return;
        Dialogue d = dialogues.get(sc.dialogue);
        Node n = d.nodes.get(sc.node);
        Mission m = sc.dialogue.startsWith("giver:") ? missions.get(sc.dialogue.substring(6)) : current(pr(hp));
        if (m == null) m = offered(pr(hp));
        if (n == null || m == null) return;
        if (!p.getUuid().equals(host)) {
            if (index >= 0 && index < n.choices.size()) {
                sc.suggestions.put(p.getUuid(), index);
                sendDialogue(sc);
            }
            return;
        }
        if (n.choices.isEmpty()) {
            if (n.end || n.next == null) endDialogue(hp, sc, m);
            else {
                sc.node = n.next;
                enterNode(hp, sc, m);
            }
            return;
        }
        if (index < 0 || index >= n.choices.size()) return;
        Choice c = n.choices.get(index);
        if (!test(hp, c.requires)) return;
        apply(hp, sc, m, c.effects);
        line(sc, hp, null, "▸ " + fill(c.text, hp));
        if (c.next == null) endDialogue(hp, sc, m);
        else {
            sc.node = c.next;
            enterNode(hp, sc, m);
        }
    }

    /** Any quest on offer (for a giver's custom dialogue). */
    private Mission offered(Profile pr) {
        for (String id : pr.story.available) if (missions.containsKey(id)) return missions.get(id);
        return null;
    }

    private void endDialogue(ServerPlayerEntity host, Scene sc, Mission m) {
        sc.dialogue = null;
        sc.node = null;
        closeDialogue(sc);
        Profile pr = pr(host);
        Step st = step(pr);
        if (st != null && st.goal.has("talk") && sc.talkedTo != null && st.goal.get("talk").getAsString().equals(sc.talkedTo)) {
            sc.goalMet = true;
            completeStep(host, pr, sc, m, st);
        }
    }

    private void closeDialogue(Scene sc) {
        if (server == null) return;
        for (ServerPlayerEntity x : members(sc)) {
            if (ServerPlayNetworking.canSend(x, Net.DialogueView.ID)) {
                ServerPlayNetworking.send(x, new Net.DialogueView(false, "", "", "", List.of(), false, ""));
            }
        }
    }

    // ------------------------------------------------------------------ party

    private final Map<UUID, Long> invited = new HashMap<>();

    private void inviteParty(ServerPlayerEntity host, Scene sc, Mission m) {
        Parties.Party party = AotRpg.PARTIES.of(host.getUuid());
        if (party == null) return;
        long now = System.currentTimeMillis();
        for (UUID u : party.members) {
            if (u.equals(host.getUuid()) || sc.guests.contains(u)) continue;
            ServerPlayerEntity g = server.getPlayerManager().getPlayer(u);
            if (g == null || g.getWorld() != host.getWorld() || g.squaredDistanceTo(host) > 64 * 64) continue;
            if (now - invited.getOrDefault(u, 0L) < 120_000) continue;
            invited.put(u, now);
            g.sendMessage(Text.literal("⚑ " + pr(host).name + " is playing \"" + m.title + "\". ").formatted(Formatting.GOLD)
                .append(Text.literal("[Join scene]").formatted(Formatting.GREEN, Formatting.UNDERLINE)
                    .styled(st -> st.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/story join " + host.getName().getString())))), false);
        }
    }

    public void join(ServerPlayerEntity g, ServerPlayerEntity host) {
        if (g == host) return;
        Scene sc = scenes.get(host.getUuid());
        if (sc == null || !AotRpg.PARTIES.same(g.getUuid(), host.getUuid())) {
            g.sendMessage(Text.literal("Nothing to join.").formatted(Formatting.GRAY), true);
            return;
        }
        leave(g);
        sc.guests.add(g.getUuid());
        guestOf.put(g.getUuid(), host.getUuid());
        Notify.toast(g, Text.literal("Joined " + pr(host).name + "'s scene").formatted(Formatting.GREEN),
            Text.literal("Their choices are theirs; you can suggest"), 0x5BD35B, "minecraft:writable_book", "story");
        Notify.toast(host, Text.literal(pr(g).name + " joined your scene").formatted(Formatting.GREEN), null, 0x5BD35B, null, "story");
        // Recast the actors so the tracker sends them to the new guest.
        for (String id : new ArrayList<>(sc.actors.keySet())) despawnActor(sc, id);
        sc.spawned = false;
        if (sc.dialogue != null) sendDialogue(sc);
    }

    public void leave(ServerPlayerEntity g) {
        UUID h = guestOf.remove(g.getUuid());
        if (h == null) return;
        Scene sc = scenes.get(h);
        if (sc != null) sc.guests.remove(g.getUuid());
        if (ServerPlayNetworking.canSend(g, Net.DialogueView.ID)) ServerPlayNetworking.send(g, new Net.DialogueView(false, "", "", "", List.of(), false, ""));
    }

    public void forget(ServerPlayerEntity p) {
        leave(p);
        Map<String, UUID> mine = givers.remove(p.getUuid());
        if (mine != null && server != null) {
            for (UUID u : mine.values()) {
                Entity e = server.getOverworld().getEntity(u);
                if (e != null) {
                    phased.remove(e.getId());
                    e.discard();
                }
            }
        }
        Scene sc = scenes.remove(p.getUuid());
        if (sc != null) {
            clearScene(sc);
            for (UUID g : sc.guests) {
                guestOf.remove(g);
                ServerPlayerEntity gp = server.getPlayerManager().getPlayer(g);
                if (gp != null) Notify.toast(gp, Text.literal("The scene ended").formatted(Formatting.GRAY), null, 0x8F8A7A, null, "story");
            }
        }
    }

    /** Old story entities left in the world (a crash, a restart) are removed as they load. */
    public boolean stray(Entity e) {
        return e.getCommandTags().contains(ACTOR) && !phased.containsKey(e.getId());
    }

    // ------------------------------------------------------------------ first steps, commands

    private void offerStart(ServerPlayerEntity p, Mission m) {
        int[] at = placeOf(m.place);
        if (at == null) return;
        double d = Math.hypot(at[0] - p.getX(), at[2] - p.getZ());
        if (d < 200) return;
        p.sendMessage(Text.literal("Your story begins at " + placeName(m.place) + ". ").formatted(Formatting.GOLD)
            .append(Text.literal("[Travel there now]").formatted(Formatting.AQUA, Formatting.UNDERLINE)
                .styled(st -> st.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/story begin")))), false);
    }

    private static String placeName(String id) {
        for (Net.Area a : AotRpg.PLACES.areas()) if (a.id().equals(id)) return a.name();
        return id;
    }

    /** One free trip to where your story begins (for characters whose story restarted far away). */
    public void freeTravel(ServerPlayerEntity p) {
        Profile pr = pr(p);
        Mission m = current(pr);
        if (m == null || !pr.story.freeStart) {
            p.sendMessage(Text.literal("That trip has already been taken.").formatted(Formatting.GRAY), true);
            return;
        }
        pr.story.freeStart = false;
        Vec3d at = resolve(p.getServerWorld(), m, new JsonArray());
        p.teleport(p.getServerWorld(), at.x, at.y, at.z, p.getYaw(), 0);
        AotRpg.PROFILES.save(p.getUuid());
    }

    /** Set the story aside (or pick it back up). The step is kept; the scene is cleared until you resume. */
    public void pause(ServerPlayerEntity p, boolean pause) {
        if (pause) {
            abandon(p);
            return;
        }
        Profile pr = pr(p);
        if (current(pr) == null || pr.story.paused == pause) return;
        pr.story.paused = pause;
        if (pause) forget(p);
        AotRpg.PROFILES.save(p.getUuid());
        Notify.toast(p, Text.literal(pause ? "Story paused" : "Story resumed").formatted(Formatting.GOLD),
            Text.literal(pause ? "Your progress is kept · resume from the journal or /story resume" : current(pr).title), 0xE0B96A, "minecraft:writable_book", "story");
        send(p, pr);
    }

    /** Operators: skip the current step. */
    public void skip(ServerPlayerEntity p) {
        Profile pr = pr(p);
        Mission m = current(pr);
        Step st = step(pr);
        Scene sc = scenes.computeIfAbsent(p.getUuid(), Scene::new);
        if (m != null && st != null) {
            if (sc.dialogue != null) {
                sc.dialogue = null;
                closeDialogue(sc);
            }
            completeStep(p, pr, sc, m, st);
        }
    }

    /** Operators: restart a character's story (or jump to a mission). */
    public void reset(ServerPlayerEntity p, String mission) {
        Profile pr = pr(p);
        forget(p);
        if (mission == null) {
            pr.story = new State();
        } else if (missions.containsKey(mission)) {
            pr.story.mission = "@" + mission;
            pr.story.begun = true;
        }
        AotRpg.PROFILES.save(p.getUuid());
    }

    /** Is this player in a story step that must be done unseen? */
    public boolean stealth(ServerPlayerEntity p) {
        Step st = step(pr(p));
        return st != null && st.goal.has("unseen") && st.goal.get("unseen").getAsBoolean();
    }

    public List<String> missionIds() {
        return new ArrayList<>(missions.keySet());
    }

    // ------------------------------------------------------------------ views

    public record View(String chapter, String text, String progress, boolean hasTarget, int x, int y, int z, long xp) { }

    public View view(Profile pr) {
        Mission m = current(pr);
        Step st = step(pr);
        if (m == null && server != null) {
            // Between quests: point to the nearest one on offer.
            ServerPlayerEntity pl = null;
            for (ServerPlayerEntity x : server.getPlayerManager().getPlayerList()) if (AotRpg.PROFILES.get(x.getUuid()) == pr) pl = x;
            Mission best = null;
            Vec3d bestAt = null;
            double bd = Double.MAX_VALUE;
            for (String id : pr.story.available) {
                Mission om = missions.get(id);
                if (om == null || om.giver == null) continue;
                Vec3d at = giverAt(server.getOverworld(), om);
                double d = pl == null ? 0 : pl.getPos().squaredDistanceTo(at);
                if (d < bd) {
                    bd = d;
                    best = om;
                    bestAt = at;
                }
            }
            if (best != null) {
                ActorDef g = actors.get(best.giver.get("actor").getAsString());
                return new View("New quest", "Talk to " + (g == null ? "the quest giver" : g.name) + ": " + best.title, "", true,
                    (int) bestAt.x, (int) bestAt.y, (int) bestAt.z, best.xp);
            }
            return new View("No active quest", pr.story.done.isEmpty() ? "Your story is about to begin" : "Explore. New quests will find you", "", false, 0, 0, 0, 0);
        }
        if (m == null || st == null) {
            return new View("The Story", pr.story.done.isEmpty() ? "Your story is about to begin" : "More of your story is coming soon", "", false, 0, 0, 0, 0);
        }
        String progress = "";
        Scene sc = null;
        for (Scene s : scenes.values()) if (s.mission != null && s.mission.equals(m.id) && AotRpg.PROFILES.get(s.host) == pr) sc = s;
        if (st.goal.has("kill") && sc != null && sc.titansSpawned) progress = sc.titans.size() + " left";
        Vec3d target = null;
        ServerWorld w = server == null ? null : server.getOverworld();
        if (w != null) {
            if (st.goal.has("goto")) target = resolve(w, m, st.goal.get("goto"));
            else if ((st.goal.has("talk") || st.goal.has("take")) && sc != null) {
                Entity e = actorEntity(sc, st.goal.has("talk") ? st.goal.get("talk").getAsString() : st.goal.get("take").getAsString());
                if (e != null) target = e.getPos();
            }
            if (target == null && !sc_spawned(sc)) target = anchor(w, m, st);
        }
        return new View(m.chapter, st.objective, progress, target != null,
            target == null ? 0 : (int) target.x, target == null ? 0 : (int) target.y, target == null ? 0 : (int) target.z, m.xp);
    }

    private static boolean sc_spawned(Scene sc) {
        return sc != null && sc.spawned;
    }

    public void send(ServerPlayerEntity p, Profile pr) {
        if (!pr.created) return;
        AotRpg.QUESTS.sendObjective(p);
        AotRpg.QUESTS.send(p);
        AotRpg.QUESTS.markers(p, true);
        journal(p);
    }

    /** The journal's story pages: what you're doing, who you know, who you've become. */
    public void journal(ServerPlayerEntity p) {
        if (!ServerPlayNetworking.canSend(p, Net.StoryJournal.ID)) return;
        Profile pr = pr(p);
        State s = pr.story;
        Mission m = current(pr);
        Step st = step(pr);
        List<Net.Person> people = new ArrayList<>();
        for (var a : s.affinity.entrySet()) {
            ActorDef d = actors.get(a.getKey());
            if (d == null) continue;
            String fate = "";
            for (String f : s.flags) if (f.startsWith("fate:" + a.getKey() + ":")) fate = f.substring(("fate:" + a.getKey() + ":").length());
            people.add(new Net.Person(d.name, d.skin, a.getValue(), fate));
        }
        people.sort((x, y) -> Integer.compare(Math.abs(y.affinity()), Math.abs(x.affinity())));
        List<String> deeds = new ArrayList<>(s.deeds);
        java.util.Collections.reverse(deeds);
        List<String> done = new ArrayList<>();
        for (String id : s.done) {
            Mission dm = missions.get(id);
            if (dm != null) done.add(dm.chapter + " · " + dm.title);
        }
        List<String> offers = new ArrayList<>();
        for (String id : s.available) {
            Mission om = missions.get(id);
            if (om == null) continue;
            ActorDef g = om.giver == null ? null : actors.get(om.giver.get("actor").getAsString());
            offers.add(om.title + "|" + (g == null ? "" : g.name) + "|" + om.chapter);
        }
        ServerPlayNetworking.send(p, new Net.StoryJournal(m == null ? "" : m.chapter, m == null ? "" : m.title,
            m == null ? "" : threadName(m.thread),
            st == null ? "" : st.objective, people, deeds, ideology(s), done, offers, free(s)));
    }

    /** Words for who you've become (never numbers). */
    private static List<String> ideology(State s) {
        List<String> out = new ArrayList<>();
        out.add(s.mercy > 25 ? "Merciful" : s.mercy < -25 ? "Ruthless" : s.mercy > 8 ? "Kind" : s.mercy < -8 ? "Hard" : "Undecided on mercy");
        out.add(s.paradis > 25 ? "Paradis above all" : s.paradis < -25 ? "Humanity above borders" : s.paradis > 8 ? "Loyal to the Walls" : s.paradis < -8 ? "Curious about the world" : "Undecided on the Walls");
        out.add(s.independence > 25 ? "Your own person" : s.independence < -25 ? "A loyal soldier" : s.independence > 8 ? "Questions orders" : s.independence < -8 ? "Follows orders" : "Undecided on duty");
        return out;
    }
}
