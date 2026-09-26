package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fog on the world map: the land is greyed out until you've been there. Each character remembers
 * the map cells it has walked through (and those around them); the map shows the rest in fog.
 */
public final class Fog {
    public static final int CELL = 128;
    private final Map<UUID, Profile> sentFor = new HashMap<>();
    private MinecraftServer server;

    public void open(MinecraftServer server) {
        this.server = server;
        sentFor.clear();
    }

    public static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    public void forget(UUID id) {
        sentFor.remove(id);
    }

    public void tick(int ticks) {
        if (server == null || ticks % 20 != 7) return;
        for (ServerPlayerEntity p : server.getOverworld().getPlayers()) {
            Profile pr = AotRpg.PROFILES.get(p.getUuid());
            if (!pr.created) continue;
            boolean fresh = sentFor.get(p.getUuid()) != pr;
            if (fresh && pr.explored.isEmpty()) seed(pr);
            List<Long> added = new ArrayList<>();
            int cx = Math.floorDiv(p.getBlockX(), CELL), cz = Math.floorDiv(p.getBlockZ(), CELL);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long k = key(cx + dx, cz + dz);
                    if (pr.explored.add(k)) added.add(k);
                }
            }
            if (!ServerPlayNetworking.canSend(p, Net.Explored.ID)) continue;
            if (fresh) {
                sentFor.put(p.getUuid(), pr);
                ServerPlayNetworking.send(p, new Net.Explored(CELL, true, pr.explored.stream().mapToLong(Long::longValue).toArray()));
            } else if (!added.isEmpty()) {
                ServerPlayNetworking.send(p, new Net.Explored(CELL, false, added.stream().mapToLong(Long::longValue).toArray()));
            }
        }
    }

    /** A character from before the fog: what it had already found is uncovered. */
    private static void seed(Profile pr) {
        for (Net.Area a : AotRpg.PLACES.areas()) {
            boolean known = pr.discovered.contains(a.id());
            int[] q = pr.quests.get("visit:" + a.id());
            if (q != null && q.length > 0 && q[0] == 2) known = true;
            if (pr.origin != null && a.id().equals(pr.origin.placeId)) known = true;
            if (!known) continue;
            int cx = Math.floorDiv(a.x(), CELL), cz = Math.floorDiv(a.z(), CELL);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) pr.explored.add(key(cx + dx, cz + dz));
        }
    }
}
