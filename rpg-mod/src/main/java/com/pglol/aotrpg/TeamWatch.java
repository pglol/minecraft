package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Falling in a raid or an event: no death, no respawn. You watch the ones still fighting (click to
 * switch, hold Space to leave early), and when it's over you're back on your feet where you fell
 * (or where the raid took you from).
 */
public final class TeamWatch {
    private TeamWatch() {}

    private static final class W {
        Supplier<List<ServerPlayerEntity>> team;
        UUID watching;
        GameMode before;
        RegistryKey<World> world;
        Vec3d back;
        String whose;
    }

    private static final Map<UUID, W> watchers = new HashMap<>();
    private static MinecraftServer server;

    public static boolean watching(UUID id) {
        return watchers.containsKey(id);
    }

    /**
     * A killing blow: in a raid or an event, they go down to watch instead. False when handled here
     * (the death doesn't happen).
     */
    public static boolean allowDeath(ServerPlayerEntity p) {
        if (p.isSpectator() || watchers.containsKey(p.getUuid())) return true;
        server = p.getServer();
        Supplier<List<ServerPlayerEntity>> team = null;
        Vec3d back = p.getPos();
        String whose = "";
        Raids.Fall raid = AotRpg.RAID_BOSSES.fall(p);
        if (raid != null) {
            team = raid.team();
            back = raid.back();
            whose = "Still fighting";
        } else {
            FactionWar.Event ev = AotRpg.WAR.active();
            if (ev != null && Math.hypot(p.getX() - ev.x, p.getZ() - ev.z) < 200) {
                final FactionWar.Event e = ev;
                team = () -> AotRpg.WAR.active() == e ? near(p.getServerWorld(), e.x, e.z, 200) : List.of();
                whose = "Defending " + e.town;
            } else {
                double[] h = AotRpg.ACTIVITY.hordeAt();
                if (h != null && Math.hypot(p.getX() - h[0], p.getZ() - h[1]) < 160) {
                    final double hx = h[0], hz = h[1];
                    team = () -> AotRpg.ACTIVITY.hordeAt() != null ? near(p.getServerWorld(), hx, hz, 180) : List.of();
                    whose = "Holding the horde";
                }
            }
        }
        if (team == null) return true;
        List<ServerPlayerEntity> now = new ArrayList<>(team.get());
        now.remove(p);
        // Nobody left to watch: this was the last of them, and they go down as usual.
        if (now.isEmpty()) {
            if (raid != null) AotRpg.RAID_BOSSES.unfall(p);
            return true;
        }
        p.setHealth(p.getMaxHealth());
        W w = new W();
        w.team = team;
        w.before = p.interactionManager.getGameMode();
        w.world = p.getWorld().getRegistryKey();
        w.back = back;
        w.whose = whose;
        watchers.put(p.getUuid(), w);
        UUID id = p.getUuid();
        // A tick later: this may be the end of a bleed-out, in the middle of the downed list.
        AotRpg.SCHEDULER.later(1, () -> {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(id);
            if (pl == null) return;
            AotRpg.DOWNED.release(pl);
            pl.setInvulnerable(false);
            pl.extinguish();
            pl.clearStatusEffects();
            pl.fallDistance = 0;
            pl.changeGameMode(GameMode.SPECTATOR);
            pl.getServerWorld().playSound(null, pl.getBlockPos(), SoundEvents.ENTITY_PLAYER_DEATH, SoundCategory.PLAYERS, 1f, 0.8f);
            Titles.show(pl, Text.literal("YOU FELL").formatted(Formatting.DARK_RED, Formatting.BOLD), Text.literal("Watch the rest of them see it through"), 5, 50, 15);
            next(pl, w, 1);
        });
        return false;
    }

    private static List<ServerPlayerEntity> near(ServerWorld w, double x, double z, double r) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity o : w.getPlayers()) {
            if (o.isAlive() && !o.isSpectator() && !watching(o.getUuid()) && Math.hypot(o.getX() - x, o.getZ() - z) < r) out.add(o);
        }
        return out;
    }

    private static List<ServerPlayerEntity> living(ServerPlayerEntity p, W w) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity o : w.team.get()) if (o != p && o.isAlive() && !o.isSpectator() && o.getWorld() == p.getWorld()) out.add(o);
        return out;
    }

    private static void next(ServerPlayerEntity p, W w, int dir) {
        List<ServerPlayerEntity> can = living(p, w);
        if (can.isEmpty()) {
            end(p);
            return;
        }
        int at = -1;
        for (int i = 0; i < can.size(); i++) if (can.get(i).getUuid().equals(w.watching)) at = i;
        ServerPlayerEntity t = can.get(Math.floorMod(at + dir, can.size()));
        w.watching = t.getUuid();
        p.setCameraEntity(t);
        if (ServerPlayNetworking.canSend(p, Net.SpecView.ID)) {
            ServerPlayNetworking.send(p, new Net.SpecView(true, AotRpg.PROFILES.get(t.getUuid()).name, w.whose));
        }
    }

    /** Over (or they chose to leave): on their feet again where they fell (or where the raid took them from). */
    public static void end(ServerPlayerEntity p) {
        W w = watchers.remove(p.getUuid());
        if (w == null) return;
        p.setCameraEntity(p);
        p.changeGameMode(w.before == null || w.before == GameMode.SPECTATOR ? GameMode.SURVIVAL : w.before);
        ServerWorld world = server.getWorld(w.world);
        if (world != null) p.teleport(world, w.back.x, w.back.y, w.back.z, p.getYaw(), p.getPitch());
        p.setHealth(p.getMaxHealth());
        if (ServerPlayNetworking.canSend(p, Net.SpecView.ID)) ServerPlayNetworking.send(p, new Net.SpecView(false, "", ""));
    }

    public static void action(ServerPlayerEntity p, String action) {
        W w = watchers.get(p.getUuid());
        if (w == null) return;
        switch (action) {
            case "spec_next" -> next(p, w, 1);
            case "spec_prev" -> next(p, w, -1);
            case "spec_leave" -> end(p);
            default -> { }
        }
    }

    public static void tick(MinecraftServer s, int ticks) {
        server = s;
        if (ticks % 10 != 3 || watchers.isEmpty()) return;
        for (UUID id : new ArrayList<>(watchers.keySet())) {
            ServerPlayerEntity p = s.getPlayerManager().getPlayer(id);
            W w = watchers.get(id);
            if (p == null || w == null) continue;
            ServerPlayerEntity t = w.watching == null ? null : s.getPlayerManager().getPlayer(w.watching);
            if (t == null || !t.isAlive() || t.isSpectator() || t.getWorld() != p.getWorld() || !living(p, w).contains(t)) next(p, w, 1);
            else if (p.getCameraEntity() != t) p.setCameraEntity(t);
        }
    }

    /** Left while watching: themselves again, back where they fell, before they go. */
    public static void left(ServerPlayerEntity p) {
        if (watchers.containsKey(p.getUuid())) end(p);
    }
}
