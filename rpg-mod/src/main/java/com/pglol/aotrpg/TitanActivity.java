package com.pglol.aotrpg;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Titans on the move, beyond the regular spawns:
 * <ul>
 *   <li><b>Hordes</b>: every so often a pack of titans gathers near someone out in titan land and
 *   is marked on the map. Clear it and everyone who fought is paid.</li>
 *   <li><b>Abnormal sightings</b>: a lone abnormal titan, tougher (several nape strikes), roams
 *   somewhere beyond the walls. Everyone hears of it; the hunters who fell it earn fine loot.</li>
 *   <li><b>Titan caves</b>: caves fill with titans again whenever someone comes near.</li>
 * </ul>
 */
public final class TitanActivity {
    public static final String TAG = "aot_activity", HORDE = "aot_horde", ABNORMAL = "aot_abnormal", CAVE = "aot_cave";

    private static final class Horde {
        final List<UUID> titans = new ArrayList<>();
        final Set<UUID> fighters = new HashSet<>();
        int x, z;
        long until;
        String where = "";
    }

    private Horde horde;
    private UUID abnormal;
    private int abX, abZ;
    private long abUntil;
    private final Set<UUID> abHunters = new HashSet<>();
    private long nextHorde = System.currentTimeMillis() + 6 * 60_000L, nextAbnormal = System.currentTimeMillis() + 15 * 60_000L;
    private final Map<String, Long> caveReady = new HashMap<>();
    private MinecraftServer server;

    public void open(MinecraftServer server) {
        this.server = server;
        horde = null;
        abnormal = null;
    }

    public static boolean managed(Entity e) {
        return e.getCommandTags().contains(TAG);
    }

    private boolean beyondWalls(double x, double z) {
        int[] w = AotRpg.PLACES.walls;
        return w == null || Math.hypot(x, z) > w[2] + 60;
    }

    // ------------------------------------------------------------------ ticking

    public void tick(int ticks) {
        if (server == null || ticks % 20 != 9) return;
        ServerWorld w = server.getOverworld();
        long now = System.currentTimeMillis();
        tickHorde(w, now);
        tickAbnormal(w, now);
        if (ticks % 200 == 9) tickCaves(w, now);
    }

    private List<ServerPlayerEntity> outside(ServerWorld w) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (!p.isSpectator() && AotRpg.PROFILES.get(p.getUuid()).created && beyondWalls(p.getX(), p.getZ()) && !AotRpg.CROWD.afk(p)) out.add(p);
        }
        return out;
    }

    private static String direction(double dx, double dz) {
        String[] names = {"east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east"};
        double a = Math.toDegrees(Math.atan2(dz, dx));
        return names[(int) Math.floorMod(Math.round(a / 45.0), 8)];
    }

    // ------------------------------------------------------------------ hordes

    private void tickHorde(ServerWorld w, long now) {
        if (horde == null) {
            if (now < nextHorde) return;
            nextHorde = now + (8 + w.getRandom().nextInt(6)) * 60_000L;
            List<ServerPlayerEntity> out = outside(w);
            if (out.isEmpty()) return;
            ServerPlayerEntity p = out.get(w.getRandom().nextInt(out.size()));
            double a = w.getRandom().nextDouble() * Math.PI * 2, d = 140 + w.getRandom().nextInt(80);
            int x = (int) (p.getX() + Math.cos(a) * d), z = (int) (p.getZ() + Math.sin(a) * d);
            if (!beyondWalls(x, z)) return;
            Horde h = new Horde();
            h.x = x;
            h.z = z;
            h.until = now + 10 * 60_000L;
            Net.Area area = AotRpg.PLACES.areaAt(x, z);
            h.where = area == null ? "the wilds" : area.name();
            int n = 6 + w.getRandom().nextInt(5);
            List<EntityType<?>> kinds = TitanTypes.ordinary();
            if (kinds.isEmpty()) return;
            for (int i = 0; i < n; i++) {
                Entity t = spawn(w, kinds.get(w.getRandom().nextInt(kinds.size())), x, z, 18, HORDE);
                if (t != null) h.titans.add(t.getUuid());
            }
            if (h.titans.isEmpty()) return;
            horde = h;
            for (ServerPlayerEntity o : w.getPlayers()) {
                if (o.squaredDistanceTo(x, o.getY(), z) > 600 * 600) continue;
                Notify.toast(o, Text.literal("Titan horde sighted").formatted(Formatting.RED, Formatting.BOLD),
                    Text.literal(n + " titans gather to the " + direction(x - o.getX(), z - o.getZ()) + " (" + h.where + ") · marked on your map"),
                    0xE04A3A, "minecraft:bell", "horde");
                AotRpg.QUESTS.markers(o, true);
            }
            return;
        }
        Horde h = horde;
        h.titans.removeIf(id -> {
            Entity e = w.getEntity(id);
            return e == null || !e.isAlive();
        });
        if (h.titans.isEmpty()) {
            // Cleared: everyone who felled one is paid.
            for (UUID id : h.fighters) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                if (p == null) continue;
                Profile pr = AotRpg.PROFILES.get(p.getUuid());
                long marks = 150 + pr.level * 6L;
                AotRpg.WALLET.earn(p, marks, "Horde cleared");
                AotRpg.SEASON.xp(p, 300);
                AotRpg.REGIMENTS.gain(p, 60);
                Notify.toast(p, Text.literal("Horde cleared!").formatted(Formatting.GOLD, Formatting.BOLD),
                    Text.literal("+" + marks + " Marks · pass XP"), 0xE0B96A, "minecraft:gold_ingot", "horde");
            }
            end(w);
        } else if (System.currentTimeMillis() > h.until) {
            // Nobody came: the horde moves on.
            for (UUID id : h.titans) {
                Entity e = w.getEntity(id);
                if (e != null) e.discard();
            }
            end(w);
        }
    }

    private void end(ServerWorld w) {
        horde = null;
        for (ServerPlayerEntity o : w.getPlayers()) AotRpg.QUESTS.markers(o, true);
    }

    // ------------------------------------------------------------------ abnormals

    private void tickAbnormal(ServerWorld w, long now) {
        if (abnormal == null) {
            if (now < nextAbnormal) return;
            nextAbnormal = now + (18 + w.getRandom().nextInt(10)) * 60_000L;
            List<ServerPlayerEntity> out = outside(w);
            if (out.isEmpty()) return;
            ServerPlayerEntity p = out.get(w.getRandom().nextInt(out.size()));
            double a = w.getRandom().nextDouble() * Math.PI * 2, d = 250 + w.getRandom().nextInt(250);
            int x = (int) (p.getX() + Math.cos(a) * d), z = (int) (p.getZ() + Math.sin(a) * d);
            if (!beyondWalls(x, z)) return;
            List<EntityType<?>> kinds = TitanTypes.ordinary();
            if (kinds.isEmpty()) return;
            Entity t = spawn(w, kinds.get(w.getRandom().nextInt(kinds.size())), x, z, 0, ABNORMAL);
            if (t == null) return;
            TitanLevels.fix(t, AotRpg.PLACES.levelAt(x, z) + 8);
            t.addCommandTag(Raids.STRIKES + 5);
            t.setCustomName(Text.literal("Abnormal Titan").formatted(Formatting.DARK_PURPLE, Formatting.BOLD));
            t.setGlowing(true);
            if (t instanceof LivingEntity le) le.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 20 * 60 * 20, 1, false, false));
            abnormal = t.getUuid();
            abX = x;
            abZ = z;
            abUntil = now + 15 * 60_000L;
            abHunters.clear();
            Net.Area area = AotRpg.PLACES.areaAt(x, z);
            for (ServerPlayerEntity o : w.getPlayers()) {
                Notify.toast(o, Text.literal("Abnormal titan sighted").formatted(Formatting.DARK_PURPLE, Formatting.BOLD),
                    Text.literal((area == null ? "Beyond the walls" : area.name()) + " · 5 nape strikes · fine loot · on your map"),
                    0x9A5CC8, "minecraft:wither_skeleton_skull", "abnormal");
                AotRpg.QUESTS.markers(o, true);
            }
            return;
        }
        Entity e = w.getEntity(abnormal);
        if (e != null && e.isAlive()) {
            abX = (int) e.getX();
            abZ = (int) e.getZ();
        }
        if ((e == null || !e.isAlive()) && w.isChunkLoaded(abX >> 4, abZ >> 4) || now > abUntil) {
            if (e != null && e.isAlive()) e.discard();
            abnormal = null;
            for (ServerPlayerEntity o : w.getPlayers()) AotRpg.QUESTS.markers(o, true);
        }
    }

    // ------------------------------------------------------------------ caves

    /** Titans come back to a cave when someone is near and it has been quiet for a few minutes. */
    private void tickCaves(ServerWorld w, long now) {
        List<EntityType<?>> kinds = TitanTypes.ordinary();
        if (kinds.isEmpty()) return;
        for (Net.Area a : AotRpg.PLACES.areas()) {
            if (!a.look().equals("cave") || now < caveReady.getOrDefault(a.id(), 0L)) continue;
            boolean near = false;
            for (ServerPlayerEntity p : w.getPlayers()) {
                if (!p.isSpectator() && p.squaredDistanceTo(a.x(), a.y(), a.z()) < 80 * 80) near = true;
            }
            if (!near || !w.isChunkLoaded(a.x() >> 4, a.z() >> 4)) continue;
            Box box = new Box(a.x() - 48, a.y() - 30, a.z() - 48, a.x() + 48, a.y() + 40, a.z() + 48);
            int have = w.getEntitiesByClass(LivingEntity.class, box, e -> e.isAlive() && AotRpg.isTitan(e)).size();
            int want = 3 + w.getRandom().nextInt(2);
            for (int i = have; i < want; i++) {
                Entity t = spawnAt(w, kinds.get(w.getRandom().nextInt(kinds.size())),
                    a.x() + w.getRandom().nextInt(13) - 6, a.y(), a.z() + w.getRandom().nextInt(13) - 6, CAVE);
                if (t != null) TitanLevels.fix(t, (a.min() + a.max()) / 2 + w.getRandom().nextInt(3));
            }
            caveReady.put(a.id(), now + 3 * 60_000L);
        }
    }

    // ------------------------------------------------------------------ shared

    private Entity spawn(ServerWorld w, EntityType<?> type, int cx, int cz, int spread, String tag) {
        int x = cx + (spread > 0 ? w.getRandom().nextInt(spread * 2 + 1) - spread : 0);
        int z = cz + (spread > 0 ? w.getRandom().nextInt(spread * 2 + 1) - spread : 0);
        w.getChunk(x >> 4, z >> 4);
        int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        return spawnAt(w, type, x, y, z, tag);
    }

    private Entity spawnAt(ServerWorld w, EntityType<?> type, int x, int y, int z, String tag) {
        Entity t = type.create(w);
        if (t == null) return null;
        t.refreshPositionAndAngles(x + 0.5, y, z + 0.5, w.getRandom().nextFloat() * 360, 0);
        if (t instanceof MobEntity mob) {
            mob.initialize(w, w.getLocalDifficulty(new BlockPos(x, y, z)), SpawnReason.EVENT, null);
            mob.setPersistent();
        }
        t.addCommandTag(TAG);
        t.addCommandTag(tag);
        t.addCommandTag("aot_titan");
        return w.spawnEntity(t) ? t : null;
    }

    /** A titan died: horde fighters are noted, and an abnormal's hunters are paid. */
    public void onKill(ServerPlayerEntity killer, LivingEntity dead) {
        if (killer == null) return;
        if (horde != null && horde.titans.contains(dead.getUuid())) {
            horde.fighters.add(killer.getUuid());
            Parties.Party party = AotRpg.PARTIES.of(killer.getUuid());
            if (party != null) {
                for (UUID m : party.members) {
                    ServerPlayerEntity o = server.getPlayerManager().getPlayer(m);
                    if (o != null && o.squaredDistanceTo(dead) < 64 * 64) horde.fighters.add(m);
                }
            }
        }
        if (dead.getUuid().equals(abnormal)) {
            ServerWorld w = (ServerWorld) dead.getWorld();
            for (ServerPlayerEntity p : w.getPlayers()) {
                if (p != killer && p.squaredDistanceTo(dead) > 40 * 40) continue;
                Profile pr = AotRpg.PROFILES.get(p.getUuid());
                long marks = 300 + pr.level * 10L;
                AotRpg.WALLET.earn(p, marks, "Abnormal slain");
                AotRpg.SEASON.xp(p, 500);
                AotRpg.REGIMENTS.gain(p, 100);
                Gear.Rarity rar = p.getRandom().nextFloat() < 0.3f ? Gear.Rarity.EPIC : Gear.Rarity.RARE;
                AotRpg.SATCHEL.add(p, Gear.roll(p.getRandom(), rar, Gear.dropLevel(p, TitanLevels.level(dead), 1)));
                Notify.toast(p, Text.literal("Abnormal slain!").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD),
                    Text.literal("+" + marks + " Marks · " + rar.title + " gear in your satchel"), 0x9A5CC8, "minecraft:nether_star", "abnormal");
                p.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.9f, 1.1f);
            }
            abnormal = null;
        }
    }

    /** Hordes and abnormals on the map. */
    public void markers(ServerPlayerEntity p, List<Net.Marker> list) {
        if (horde != null) list.add(new Net.Marker("event", "Titan horde (" + horde.titans.size() + ")", horde.x, 70, horde.z, 0xE04A3A));
        if (abnormal != null) list.add(new Net.Marker("event", "Abnormal titan", abX, 70, abZ, 0x9A5CC8));
    }
}
