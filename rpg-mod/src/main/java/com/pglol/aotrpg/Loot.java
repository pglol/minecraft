package com.pglol.aotrpg;

import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Personal loot. Everyone who fought a titan gets their own drops from it: only they can see
 * them, only they can pick them up. Nobody runs off with your legendary.
 *
 * Who counts: the killer, and anyone who hit the titan in the last 60 seconds and is still
 * within 96 blocks of it.
 */
public final class Loot {
    private static final String TAG = "aot_loot:";
    private static final long WINDOW_MS = 60_000;

    /** Titan -> (player -> when they last hit it). */
    private final Map<UUID, Map<UUID, Long>> hits = new HashMap<>();

    /** A player hit a titan. */
    public void hit(ServerPlayerEntity p, Entity titan) {
        hits.computeIfAbsent(titan.getUuid(), k -> new HashMap<>()).put(p.getUuid(), System.currentTimeMillis());
    }

    /** Who shares in a titan's loot (the killer first). Forgets the titan. */
    public List<ServerPlayerEntity> earners(ServerPlayerEntity killer, LivingEntity titan) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        if (killer != null) out.add(killer);
        Map<UUID, Long> m = hits.remove(titan.getUuid());
        if (m == null || killer == null) return out;
        long now = System.currentTimeMillis();
        for (var e : m.entrySet()) {
            if (now - e.getValue() > WINDOW_MS || e.getKey().equals(killer.getUuid())) continue;
            ServerPlayerEntity p = killer.getServer().getPlayerManager().getPlayer(e.getKey());
            if (p != null && p.getWorld() == titan.getWorld() && p.squaredDistanceTo(titan) < 96 * 96) out.add(p);
        }
        return out;
    }

    /** Every so often: forget titans nobody has hit for a while. */
    public void tick(int ticks) {
        if (ticks % 1200 != 0) return;
        long now = System.currentTimeMillis();
        hits.values().removeIf(m -> {
            m.values().removeIf(t -> now - t > WINDOW_MS);
            return m.isEmpty();
        });
    }

    /** Makes a dropped item this player's alone: nobody else sees it or picks it up. */
    public static void own(ItemEntity e, ServerPlayerEntity p) {
        e.setOwner(p.getUuid());
        e.addCommandTag(TAG + p.getUuidAsString());
    }

    /** The player a dropped item belongs to, or null if it's anyone's. */
    public static UUID owner(Entity e) {
        if (!(e instanceof ItemEntity)) return null;
        for (String t : e.getCommandTags()) {
            if (t.startsWith(TAG)) {
                try {
                    return UUID.fromString(t.substring(TAG.length()));
                } catch (Exception ignored) { }
            }
        }
        return null;
    }

    /** Someone else's loot: not sent to this player at all. */
    public static boolean hiddenFrom(Entity e, ServerPlayerEntity p) {
        UUID o = owner(e);
        return o != null && !o.equals(p.getUuid());
    }
}
