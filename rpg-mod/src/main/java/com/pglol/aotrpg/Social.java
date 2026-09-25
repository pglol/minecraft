package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The social hub: everyone online and your friends (account wide), with party invites, friend
 * requests, whisper and pay shortcuts. Friends are one-way follows; the other player is told and
 * can add you back with one click.
 */
public final class Social {
    private MinecraftServer server;

    public void open(MinecraftServer server) {
        this.server = server;
    }

    public void action(ServerPlayerEntity p, String action, UUID target) {
        ServerPlayerEntity t = target == null ? null : server.getPlayerManager().getPlayer(target);
        ProfileStore.Account a = AotRpg.PROFILES.account(p.getUuid());
        switch (action) {
            case "friend_add" -> {
                if (target == null || target.equals(p.getUuid())) break;
                String name = t != null ? AotRpg.PROFILES.get(target).name : a.friends.getOrDefault(target.toString(), "?");
                if (a.friends.size() >= 100) {
                    p.sendMessage(Text.literal("Your friends list is full.").formatted(Formatting.RED), true);
                    break;
                }
                a.friends.put(target.toString(), name);
                AotRpg.PROFILES.saveAccount(p.getUuid());
                p.sendMessage(Text.literal("Added " + name + " as a friend").formatted(Formatting.GREEN), true);
                if (t != null && !AotRpg.PROFILES.account(target).friends.containsKey(p.getUuid().toString())) {
                    String me = AotRpg.PROFILES.get(p.getUuid()).name;
                    t.sendMessage(Text.literal(me + " added you as a friend. ").formatted(Formatting.GREEN)
                        .append(Text.literal("[Add back]").formatted(Formatting.GOLD, Formatting.UNDERLINE).styled(st -> st
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/friend add " + p.getUuid()))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Add " + me))))), false);
                }
            }
            case "friend_remove" -> {
                if (target != null && a.friends.remove(target.toString()) != null) AotRpg.PROFILES.saveAccount(p.getUuid());
            }
            case "invite" -> {
                if (t != null) AotRpg.PARTIES.invite(p, t);
            }
            default -> { }
        }
        send(p, action.equals("open"));
    }

    public void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.SocialView.ID)) return;
        ProfileStore.Account a = AotRpg.PROFILES.account(p.getUuid());
        List<Net.SocialPlayer> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
            if (o == p) continue;
            Profile pr = AotRpg.PROFILES.get(o.getUuid());
            if (!pr.created) continue;
            String id = o.getUuid().toString();
            seen.add(id);
            if (a.friends.containsKey(id) && !pr.name.equals(a.friends.get(id))) a.friends.put(id, pr.name);
            Factions.Faction f = Factions.of(pr);
            boolean home = o.getServerWorld().getRegistryKey().equals(Homes.WORLD);
            list.add(new Net.SocialPlayer(o.getUuid(), pr.name, o.getGameProfile().getName(), pr.level, Roles.tag(pr), Roles.tagColor(pr), true,
                a.friends.containsKey(id), AotRpg.PARTIES.same(p.getUuid(), o.getUuid()),
                home ? "At home" : Sector.at(o.getX(), o.getZ()).title, f == null ? "" : f.title, f == null ? 0 : f.color));
        }
        for (var e : a.friends.entrySet()) {
            if (seen.contains(e.getKey())) continue;
            UUID id;
            try {
                id = UUID.fromString(e.getKey());
            } catch (Exception ex) {
                continue;
            }
            list.add(new Net.SocialPlayer(id, e.getValue(), "", 0, "", 0, false, true, false, "Offline", "", 0));
        }
        ServerPlayNetworking.send(p, new Net.SocialView(list, open));
    }
}
