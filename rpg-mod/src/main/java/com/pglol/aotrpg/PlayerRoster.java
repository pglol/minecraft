package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/** Every few seconds: who's on, their character, level and where they are, for the player list. */
public final class PlayerRoster {
    private PlayerRoster() {}

    public static void broadcast(MinecraftServer server) {
        List<Net.ListedPlayer> list = new ArrayList<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            Profile pr = AotRpg.PROFILES.get(p.getUuid());
            String name = pr.created && !pr.name.isEmpty() ? pr.name : p.getName().getString();
            list.add(new Net.ListedPlayer(p.getUuid(), name, pr.created ? pr.level : 0, where(p), pr.roleColor != 0 ? pr.roleColor : 0xEDE3C8));
        }
        Net.PlayerListing msg = new Net.PlayerListing(list);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (ServerPlayNetworking.canSend(p, Net.PlayerListing.ID)) ServerPlayNetworking.send(p, msg);
        }
    }

    private static String where(ServerPlayerEntity p) {
        if (p.getWorld().getRegistryKey() == World.OVERWORLD) return "Open World";
        if (Extraction.inLobby(p)) return "Balloon";
        if (p.getWorld().getRegistryKey() == Homes.WORLD) return "Home";
        Extraction.Island i = Extraction.Island.of(p.getWorld());
        return i != null ? i.title : "Elsewhere";
    }
}
