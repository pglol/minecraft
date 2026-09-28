package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What's waiting for a player, for the "!" on the pause menu's tiles: gifts in the inbox, battle
 * pass rewards and finished tasks to claim, points to spend. Checked every few seconds and sent
 * only when it changes.
 */
public final class Alerts {
    private final Map<UUID, List<String>> last = new HashMap<>();

    public void tick(ServerPlayerEntity p, int ticks) {
        if (ticks % 60 != 0 || !ServerPlayNetworking.canSend(p, Net.AlertsView.ID)) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        List<String> now = new ArrayList<>();
        if (pr.created) {
            if (AotRpg.INBOX.pending(p.getUuid())) now.add("inbox");
            if (pr.skillPoints > 0 || pr.points > 0) now.add("character");
            try {
                if (AotRpg.SEASON.pending(p)) now.add("pass");
            } catch (Exception ignored) { }
            try {
                if (AotRpg.TASKS.pending(p)) now.add("tasks");
            } catch (Exception ignored) { }
        }
        if (now.equals(last.get(p.getUuid()))) return;
        last.put(p.getUuid(), now);
        ServerPlayNetworking.send(p, new Net.AlertsView(now));
    }

    public void forget(UUID id) {
        last.remove(id);
    }
}
