package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Character names above players. The client mod draws styled name plates from a roster the
 * server sends; players without the mod see normal usernames.
 * (Earlier versions used text displays riding the player and a team hiding the vanilla tag;
 * both are cleaned up here.)
 */
public final class Nametags {
    public static final String TAG = "aot_nametag";
    private static final String OLD_TEAM = "aot_players";
    private boolean dirty = true;
    private boolean cleaned;

    /** Something about this player's plate changed (name, level, discipline). */
    public void update(ServerPlayerEntity p, Profile pr) {
        dirty = true;
    }

    public void remove(ServerPlayerEntity p) {
        dirty = true;
    }

    public void dirty() {
        dirty = true;
    }

    public void clear() {
        dirty = true;
    }

    /** Old text-display tags left in the world are removed as they load. */
    public boolean isStray(Entity e) {
        return e.getCommandTags().contains(TAG);
    }

    public void tick(MinecraftServer server, int ticks) {
        if (!cleaned) {
            cleaned = true;
            Scoreboard sb = server.getScoreboard();
            Team t = sb.getTeam(OLD_TEAM);
            if (t != null) sb.removeTeam(t);
        }
        if (!dirty && ticks % 100 != 0) return;
        dirty = false;
        List<Net.RosterEntry> list = new ArrayList<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            Profile pr = AotRpg.PROFILES.get(p.getUuid());
            if (pr.created) list.add(new Net.RosterEntry(p.getUuid(), pr.name, pr.level, pr.discipline.ordinal(),
                Roles.tag(pr), Roles.tagColor(pr), pr.rp && (pr.role == null || pr.role.isEmpty()),
                Factions.of(pr) == null ? -1 : Factions.of(pr).ordinal(),
                AotRpg.REGIMENTS.of(p.getUuid()) == null ? "" : AotRpg.REGIMENTS.of(p.getUuid()).tag,
                AotRpg.REGIMENTS.of(p.getUuid()) == null ? 0 : AotRpg.REGIMENTS.of(p.getUuid()).color));
        }
        Net.Roster roster = new Net.Roster(list);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (ServerPlayNetworking.canSend(p, Net.Roster.ID)) ServerPlayNetworking.send(p, roster);
        }
    }
}
