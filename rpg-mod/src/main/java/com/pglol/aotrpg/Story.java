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
        public boolean freeStart = true;
    }

    // ------------------------------------------------------------------ content

    static final class ActorDef { String name = "?"; String skin = "civilian_m"; boolean watch; String item; }
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
        UUID host = phased.get(e.getId());
        if (host == null) return true;
        if (host.equals(p.getUuid())) return true;
        return host.equals(AotRpg.STORY.guestOf.get(p.getUuid()));
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
        int x = (int) Math.round(px + dx * f + rx * r), z = (int) Math.round(pz + dz * f + rz * r);
        BlockPos g = ground(w, x, p[1], z);
        return new Vec3d(g.getX() + 0.5, g.getY() + dy, g.getZ() + 0.5);
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
                    if (se != null) w.playSound(null, BlockPos.ofFloored(at), se, SoundCategory.AMBIENT, o.has("volume") ? o.get("volume").getAsFloat() : 1f,
                        o.has("pitch") ? o.get("pitch").getAsFloat() : 1f);
                }
                case "fx" -> {
                    JsonObject o = v.getAsJsonObject();
                    Vec3d at = resolve(w, m, o.get("at"));
                    String type = o.get("type").getAsString();
                    switch (type) {
                        case "explosion" -> w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 2, at.z, 2, 2, 2, 2, 0);
                        case "smoke" -> w.spawnParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, at.x, at.y + 1, at.z, 40, 3, 2, 3, 0.02);
                        case "steam" -> w.spawnParticles(ParticleTypes.CLOUD, at.x, at.y + 20, at.z, 200, 8, 20, 8, 0.1);
                        case "fire" -> w.spawnParticles(ParticleTypes.FLAME, at.x, at.y + 1, at.z, 60, 2, 1, 2, 0.02);
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
                        sc.followers.remove(o.get("actor").getAsString());
                        sc.walks.put(o.get("actor").getAsString(), resolve(w, m, o.get("to")));
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
                    Vec3d at = resolve(w, m, o.get("at"));
                    old.requestTeleport(at.x, at.y, at.z);
                }
                return;
            }
        }
        ActorDef def = actors.getOrDefault(id, new ActorDef());
        Vec3d at = resolve(w, m, o.get("at"));
        if (def.item != null && def.item.equals("boat")) {
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
        v.refreshPositionAndAngles(at.x, at.y, at.z, o.has("yaw") ? o.get("yaw").getAsFloat() : 0, 0);
        v.setAiDisabled(true);
        v.setInvulnerable(true);
        v.setSilent(true);
        v.setCustomName(Text.literal(def.name));
        v.setCustomNameVisible(true);
        v.addCommandTag("aot_aid:" + id);
        if (def.watch || (o.has("watch") && o.get("watch").getAsBoolean())) v.addCommandTag(WATCH);
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
        List<Net.ActorInfo> list = new ArrayList<>();
        for (var a : sc.actors.entrySet()) {
            Entity e = actorEntity(sc, a.getKey());
            ActorDef d = actors.get(a.getKey());
            if (e == null || !(e instanceof VillagerEntity)) continue;
            list.add(new Net.ActorInfo(e.getId(), d == null ? "civilian_m" : d.skin));
        }
        for (ServerPlayerEntity x : members(sc)) {
            if (ServerPlayNetworking.canSend(x, Net.Actors.ID)) ServerPlayNetworking.send(x, new Net.Actors(list));
        }
    }

    // ------------------------------------------------------------------ the flow

    /** Starts a character's story at their origin's opening. */
    private void begin(ServerPlayerEntity p, Profile pr) {
        State s = pr.story;
        s.begun = true;
        String first = pr.origin == null ? null : starts.get(pr.origin.name());
        if (first == null) first = starts.get("default");
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
        AotRpg.PROFILES.save(p.getUuid());
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
        if (s.mission.startsWith("@")) {
            startMission(p, pr, s.mission.substring(1));
            return;
        }
        if (!s.begun && s.mission.isEmpty()) {
            if (ticks % 20 == 0) {
                begin(p, pr);
                if (s.freeStart && current(pr) != null) offerStart(p, current(pr));
            }
            return;
        }
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
        for (String id : sc.followers) {
            Entity e = actorEntity(sc, id);
            if (e == null) continue;
            double d = e.squaredDistanceTo(host);
            if (d > 40 * 40 || e.getWorld() != host.getWorld()) e.requestTeleport(host.getX() + 1.5, host.getY(), host.getZ() + 1.5);
            else if (d > 3.5 * 3.5) step(e, host.getPos(), 0.28);
        }
        for (var it = sc.walks.entrySet().iterator(); it.hasNext(); ) {
            var wk = it.next();
            Entity e = actorEntity(sc, wk.getKey());
            if (e == null || e.getPos().squaredDistanceTo(wk.getValue()) < 1) {
                it.remove();
                continue;
            }
            step(e, wk.getValue(), 0.22);
        }
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
     * A story scene keeps its own titans: wandering ones (not event or raid titans) that come
     * within 96 blocks of the scene's players are sent off, so a scene isn't gatecrashed.
     */
    private void calm(Scene sc, ServerPlayerEntity host) {
        ServerWorld w = host.getServerWorld();
        List<Entity> gone = new ArrayList<>();
        for (ServerPlayerEntity x : members(sc)) {
            if (x.getWorld() != w) continue;
            for (Entity e : w.getOtherEntities(x, x.getBoundingBox().expand(96, 64, 96), TitanGuard::wanderingTitan)) {
                if (!phased.containsKey(e.getId())) gone.add(e);
            }
        }
        for (Entity e : gone) {
            w.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, e.getX(), e.getY() + e.getHeight() / 2, e.getZ(), 12, e.getWidth() / 2, e.getHeight() / 3, e.getWidth() / 2, 0.02);
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
        String next = s.mission.startsWith("@") ? s.mission.substring(1) : null;
        if (next == null) {
            for (JsonObject o : m.nextIf) {
                if (test(p, o.has("if") ? o.getAsJsonObject("if") : null)) {
                    next = o.get("then").getAsString();
                    break;
                }
            }
        }
        if (next == null) next = m.next;
        clearScene(sc);
        for (UUID g : sc.guests) guestOf.remove(g);
        sc.guests.clear();
        scenes.remove(p.getUuid());
        if (next != null && missions.containsKey(next)) {
            startMission(p, pr, next);
        } else {
            s.mission = "";
            s.step = 0;
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
        Scene sc = scenes.get(host);
        ServerPlayerEntity hp = server.getPlayerManager().getPlayer(host);
        if (sc == null || hp == null) return true;
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
        Mission m = current(pr(hp));
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
        ServerPlayNetworking.send(p, new Net.StoryJournal(m == null ? "" : m.chapter, m == null ? "" : m.title, m == null ? "" : threadName(m.thread),
            st == null ? "" : st.objective, people, deeds, ideology(s), done));
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
