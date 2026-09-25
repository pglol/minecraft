package com.pglol.aotrpg;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Call to Arms: every hour or so titans march on a town, and every faction soldier online is
 * called to defend it. Kills count for your faction's hold on that sector. When it is over the
 * defender with the most kills (the MVP) wins special spoils, everyone who fought is paid in Marks
 * and reputation (more for factions holding more sectors), and the faction with the most kills
 * gains influence there.
 */
public final class FactionWar {
    public static final String TAG = "aot_event";
    private static final long DURATION = 10 * 60_000L, WAVE_EVERY = 75_000L;

    public static final class Event {
        public String town = "";
        public Sector sector = Sector.ROSE;
        public int x, z;
        public long until, nextWave;
        public int wavesLeft = 4;
        public final List<UUID> titans = new ArrayList<>();
        public final Map<UUID, Integer> kills = new HashMap<>();
        public final Map<UUID, String> names = new HashMap<>();
    }

    private Event active;
    private long nextAt = System.currentTimeMillis() + 20 * 60_000L;
    private MinecraftServer server;

    public void open(MinecraftServer server) {
        this.server = server;
        active = null;
        nextAt = System.currentTimeMillis() + 20 * 60_000L;
    }

    public Event active() {
        return active;
    }

    public long nextAt() {
        return nextAt;
    }

    private List<ServerPlayerEntity> soldiers() {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) if (Factions.of(AotRpg.PROFILES.get(p.getUuid())) != null) out.add(p);
        return out;
    }

    public void tick(int ticks) {
        if (ticks % 20 != 17) return;
        long now = System.currentTimeMillis();
        if (active == null) {
            if (now >= nextAt && !soldiers().isEmpty()) start(null);
            return;
        }
        ServerWorld w = server.getOverworld();
        if (active.wavesLeft > 0 && now >= active.nextWave) {
            wave(w);
            active.wavesLeft--;
            active.nextWave = now + WAVE_EVERY;
        }
        boolean alive = false;
        for (UUID id : active.titans) {
            Entity e = w.getEntity(id);
            if (e != null && e.isAlive()) alive = true;
        }
        if ((!alive && active.wavesLeft == 0) || now > active.until) end(!alive && active.wavesLeft == 0);
    }

    /** Starts an event (at a named town, or a random one). */
    public boolean start(String townId) {
        List<Net.Area> towns = new ArrayList<>();
        for (Net.Area a : AotRpg.PLACES.areas()) {
            if (townId != null ? a.id().equals(townId) : (a.look().equals("town") || a.look().equals("village"))) towns.add(a);
        }
        if (towns.isEmpty()) for (Net.Area a : AotRpg.PLACES.areas()) if (!a.look().equals("marley")) towns.add(a);
        if (towns.isEmpty()) return false;
        Net.Area a = towns.get(server.getOverworld().getRandom().nextInt(towns.size()));
        Event ev = new Event();
        ev.town = a.name();
        ev.x = a.x();
        ev.z = a.z();
        ev.sector = Sector.at(a.x(), a.z());
        long now = System.currentTimeMillis();
        ev.until = now + DURATION;
        ev.nextWave = now + 20_000;
        active = ev;
        for (ServerPlayerEntity p : soldiers()) {
            Notify.toast(p, Text.literal("CALL TO ARMS").formatted(Formatting.RED, Formatting.BOLD),
                Text.literal("Titans march on " + ev.town + " (" + ev.sector.title + "). Defend it!"), 0xE04A3A, "minecraft:bell", "war");
            p.playSoundToPlayer(SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.MASTER, 0.9f, 1f);
            AotRpg.QUESTS.markers(p, true);
        }
        return true;
    }

    private void wave(ServerWorld w) {
        List<EntityType<?>> kinds = new ArrayList<>();
        for (Identifier id : Registries.ENTITY_TYPE.getIds()) {
            String path = id.getPath();
            if (!id.getNamespace().equals(AotItems.namespace) || !path.contains("titan")) continue;
            if (path.contains("nape") || path.contains("eye") || path.contains("grab") || path.contains("hand") || path.contains("leg")
                || path.contains("dummy") || path.contains("shell") || path.contains("shifter")) continue;
            boolean shifter = false;
            for (String s : new String[] {"attack", "armored", "colossal", "female", "beast", "cart", "jaw", "warhammer", "founding"}) {
                if (path.contains(s)) shifter = true;
            }
            if (!shifter) kinds.add(Registries.ENTITY_TYPE.get(id));
        }
        if (kinds.isEmpty()) return;
        int near = 0;
        for (ServerPlayerEntity p : soldiers()) if (p.squaredDistanceTo(active.x, p.getY(), active.z) < 200 * 200) near++;
        int n = 3 + Math.min(6, near * 2);
        var r = w.getRandom();
        for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = 55 + r.nextDouble() * 30;
            int x = (int) (active.x + Math.cos(a) * d), z = (int) (active.z + Math.sin(a) * d);
            w.getChunk(x >> 4, z >> 4);
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            Entity t = kinds.get(r.nextInt(kinds.size())).create(w);
            if (t == null) continue;
            t.refreshPositionAndAngles(x + 0.5, y, z + 0.5, r.nextFloat() * 360, 0);
            if (t instanceof MobEntity mob) {
                mob.initialize(w, w.getLocalDifficulty(new BlockPos(x, y, z)), SpawnReason.EVENT, null);
                mob.setPersistent();
                mob.getNavigation().startMovingTo(active.x, y, active.z, 1.0);
            }
            t.addCommandTag(TAG);
            t.addCommandTag("aot_titan");
            if (w.spawnEntity(t)) active.titans.add(t.getUuid());
        }
    }

    public static boolean eventTitan(Entity e) {
        return e.getCommandTags().contains(TAG);
    }

    /** A titan died: credit the killer if it was one of the event's. */
    public void onKill(ServerPlayerEntity killer, Entity dead) {
        if (active == null || !eventTitan(dead)) return;
        active.kills.merge(killer.getUuid(), 1, Integer::sum);
        active.names.put(killer.getUuid(), AotRpg.PROFILES.get(killer.getUuid()).name);
        Factions.Faction f = Factions.of(AotRpg.PROFILES.get(killer.getUuid()));
        if (f != null) AotRpg.FACTIONS.influence(active.sector, f, 0.6);
    }

    private void end(boolean held) {
        Event ev = active;
        active = null;
        nextAt = System.currentTimeMillis() + (40 + server.getOverworld().getRandom().nextInt(31)) * 60_000L;
        ServerWorld w = server.getOverworld();
        for (UUID id : ev.titans) {
            Entity e = w.getEntity(id);
            if (e != null && e.isAlive()) e.discard();
        }
        UUID mvp = null;
        int best = 0;
        Map<Factions.Faction, Integer> byFaction = new HashMap<>();
        for (var k : ev.kills.entrySet()) {
            if (k.getValue() > best) {
                best = k.getValue();
                mvp = k.getKey();
            }
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(k.getKey());
            Factions.Faction f = p == null ? null : Factions.of(AotRpg.PROFILES.get(p.getUuid()));
            if (f != null) byFaction.merge(f, k.getValue(), Integer::sum);
        }
        Factions.Faction top = null;
        int topKills = 0;
        for (var e : byFaction.entrySet()) {
            if (e.getValue() > topKills) {
                topKills = e.getValue();
                top = e.getKey();
            }
        }
        if (top != null) AotRpg.FACTIONS.influence(ev.sector, top, held ? 5 : 2);
        String mvpName = mvp == null ? "" : ev.names.getOrDefault(mvp, "?");
        for (var k : ev.kills.entrySet()) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(k.getKey());
            if (p == null) continue;
            Profile pr = AotRpg.PROFILES.get(p.getUuid());
            double mult = AotRpg.FACTIONS.multiplier(Factions.of(pr));
            int kills = k.getValue();
            pr.factionRep += (int) Math.round(2 * kills * mult);
            AotRpg.WALLET.earn(p, Math.round(30 * kills * mult), "Call to Arms: " + ev.town);
            AotRpg.SEASON.xp(p, 40L * kills);
            if (k.getKey().equals(mvp)) spoils(p, kills);
            AotRpg.PROFILES.save(p.getUuid());
        }
        for (ServerPlayerEntity p : soldiers()) {
            Notify.toast(p, Text.literal(held ? ev.town + " held!" : "The titans overran " + ev.town).formatted(held ? Formatting.GOLD : Formatting.RED),
                Text.literal(mvp == null ? "Nobody answered the call" : "MVP: " + mvpName + " (" + best + " kills)"
                    + (top == null ? "" : " · " + top.title + " gains ground")), held ? 0xE0B96A : 0xC0463A, "minecraft:bell", "war");
            AotRpg.QUESTS.markers(p, true);
        }
    }

    /** The MVP's spoils: fine gear, and sometimes a rare cosmetic. */
    private void spoils(ServerPlayerEntity p, int kills) {
        var r = p.getRandom();
        Gear.Rarity rar = kills >= 12 ? Gear.Rarity.LEGENDARY : kills >= 6 ? Gear.Rarity.EPIC : Gear.Rarity.RARE;
        p.getInventory().offerOrDrop(Gear.roll(r, rar, Gear.dropLevel(p, AotRpg.PROFILES.get(p.getUuid()).level + 2, 1)));
        String[] rare = {"clash_thunder", "head_crown", "odm_lightning", "slash_holy", "body_soul", "block_holy"};
        String extra = "";
        if (r.nextFloat() < 0.35f) {
            String c = rare[r.nextInt(rare.length)];
            AotRpg.COSMETICS.grant(p, c, true);
            Cosmetics.Def d = Cosmetics.def(c);
            extra = " and the " + (d == null ? c : d.title()) + " cosmetic";
        }
        Notify.toast(p, Text.literal("MVP!").formatted(Formatting.GOLD, Formatting.BOLD),
            Text.literal("You won " + rar.title + " gear" + extra), 0xF2C14E, "minecraft:nether_star", null);
        p.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1f, 1f);
    }

    /** The event on the map, for faction soldiers. */
    public void markers(ServerPlayerEntity p, List<Net.Marker> list) {
        if (active == null || Factions.of(AotRpg.PROFILES.get(p.getUuid())) == null) return;
        list.add(new Net.Marker("event", "Call to Arms: defend " + active.town, active.x, 70, active.z, 0xE04A3A));
    }
}
