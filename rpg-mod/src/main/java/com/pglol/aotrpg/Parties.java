package com.pglol.aotrpg;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Small in-memory parties (reset when the server restarts). Members share titan XP, cannot hurt
 * each other, and players with the mod see a party HUD and outlines on their party.
 */
public final class Parties {
    public static final int MAX = 6;
    private static final int INVITE_TICKS = 20 * 60;

    public static final class Party {
        public UUID leader;
        public final LinkedHashSet<UUID> members = new LinkedHashSet<>();
    }

    private final Map<UUID, Party> byPlayer = new HashMap<>();
    /** invitee -> (inviter -> expiry tick) */
    private final Map<UUID, Map<UUID, Long>> invites = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private long now;
    private MinecraftServer server;

    public Party of(UUID id) {
        return byPlayer.get(id);
    }

    public boolean same(UUID a, UUID b) {
        Party p = byPlayer.get(a);
        return p != null && !a.equals(b) && p.members.contains(b);
    }

    private String name(UUID id) {
        ServerPlayerEntity p = server == null ? null : server.getPlayerManager().getPlayer(id);
        if (p != null) {
            Profile pr = AotRpg.PROFILES.get(id);
            names.put(id, pr.created ? pr.name : p.getName().getString());
        }
        return names.getOrDefault(id, "?");
    }

    private static MutableText tag() {
        return Text.literal("[Party] ").formatted(Formatting.GOLD, Formatting.BOLD);
    }

    private static void msg(ServerPlayerEntity p, Text t) {
        p.sendMessage(tag().append(t));
    }

    private void tell(Party party, Text t) {
        for (UUID id : party.members) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p != null) msg(p, t);
        }
    }

    private static MutableText gray(String s) {
        return Text.literal(s).formatted(Formatting.GRAY);
    }

    private MutableText who(UUID id) {
        return Text.literal(name(id)).formatted(Formatting.WHITE);
    }

    // ------------------------------------------------------------------ actions

    public void invite(ServerPlayerEntity from, ServerPlayerEntity to) {
        if (from == to) {
            msg(from, gray("You can't invite yourself."));
            return;
        }
        Party party = byPlayer.get(from.getUuid());
        if (party != null && !party.leader.equals(from.getUuid())) {
            msg(from, gray("Only the party leader can invite."));
            return;
        }
        if (party != null && party.members.size() >= MAX) {
            msg(from, gray("Your party is full (" + MAX + ")."));
            return;
        }
        if (byPlayer.containsKey(to.getUuid())) {
            msg(from, who(to.getUuid()).append(gray(" is already in a party.")));
            return;
        }
        invites.computeIfAbsent(to.getUuid(), k -> new LinkedHashMap<>()).put(from.getUuid(), now + INVITE_TICKS);
        msg(from, gray("Invited ").append(who(to.getUuid())).append(gray(". The invite lasts 60 seconds.")));
        String fromName = from.getName().getString();
        msg(to, who(from.getUuid()).append(gray(" invited you to their party.  "))
            .append(button("[Accept]", Formatting.GREEN, "/party accept " + fromName, "Join the party"))
            .append(Text.literal(" "))
            .append(button("[Decline]", Formatting.RED, "/party decline", "Ignore the invite")));
        to.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), SoundCategory.MASTER, 0.8f, 1.4f);
    }

    private static MutableText button(String label, Formatting color, String cmd, String hover) {
        return Text.literal(label).formatted(color, Formatting.BOLD).styled(s -> s
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(hover))));
    }

    public void accept(ServerPlayerEntity p, ServerPlayerEntity from) {
        Map<UUID, Long> mine = invites.get(p.getUuid());
        UUID inviter = null;
        if (mine != null) {
            if (from != null) inviter = mine.containsKey(from.getUuid()) ? from.getUuid() : null;
            else if (!mine.isEmpty()) inviter = mine.keySet().iterator().next();
        }
        if (inviter == null) {
            msg(p, gray("You have no pending party invite" + (from != null ? " from " + from.getName().getString() : "") + "."));
            return;
        }
        mine.remove(inviter);
        if (byPlayer.containsKey(p.getUuid())) {
            msg(p, gray("Leave your current party first (/party leave)."));
            return;
        }
        Party party = byPlayer.get(inviter);
        if (party == null) {
            party = new Party();
            party.leader = inviter;
            party.members.add(inviter);
            byPlayer.put(inviter, party);
        }
        if (party.members.size() >= MAX) {
            msg(p, gray("That party is full."));
            return;
        }
        party.members.add(p.getUuid());
        byPlayer.put(p.getUuid(), party);
        invites.remove(p.getUuid());
        tell(party, who(p.getUuid()).append(Text.literal(" joined the party.").formatted(Formatting.GREEN)));
        sync(party);
    }

    public void decline(ServerPlayerEntity p) {
        Map<UUID, Long> mine = invites.remove(p.getUuid());
        if (mine == null || mine.isEmpty()) {
            msg(p, gray("You have no pending party invite."));
            return;
        }
        msg(p, gray("Invite declined."));
        for (UUID from : mine.keySet()) {
            ServerPlayerEntity f = server.getPlayerManager().getPlayer(from);
            if (f != null) msg(f, who(p.getUuid()).append(gray(" declined your invite.")));
        }
    }

    public void leave(ServerPlayerEntity p) {
        Party party = byPlayer.get(p.getUuid());
        if (party == null) {
            msg(p, gray("You are not in a party."));
            return;
        }
        remove(party, p.getUuid());
        msg(p, gray("You left the party."));
        tell(party, who(p.getUuid()).append(gray(" left the party.")));
    }

    public void kick(ServerPlayerEntity leader, ServerPlayerEntity target) {
        Party party = byPlayer.get(leader.getUuid());
        if (party == null || !party.leader.equals(leader.getUuid())) {
            msg(leader, gray("Only the party leader can kick."));
            return;
        }
        if (!party.members.contains(target.getUuid()) || target == leader) {
            msg(leader, gray("That player is not in your party."));
            return;
        }
        remove(party, target.getUuid());
        msg(target, gray("You were removed from the party."));
        tell(party, who(target.getUuid()).append(gray(" was removed from the party.")));
    }

    public void promote(ServerPlayerEntity leader, ServerPlayerEntity target) {
        Party party = byPlayer.get(leader.getUuid());
        if (party == null || !party.leader.equals(leader.getUuid()) || !party.members.contains(target.getUuid())) {
            msg(leader, gray("You must lead a party that includes that player."));
            return;
        }
        party.leader = target.getUuid();
        tell(party, who(target.getUuid()).append(Text.literal(" now leads the party.").formatted(Formatting.GOLD)));
        sync(party);
    }

    public void disband(ServerPlayerEntity leader) {
        Party party = byPlayer.get(leader.getUuid());
        if (party == null || !party.leader.equals(leader.getUuid())) {
            msg(leader, gray("Only the party leader can disband the party."));
            return;
        }
        tell(party, gray("The party was disbanded."));
        for (UUID id : new ArrayList<>(party.members)) {
            byPlayer.remove(id);
            clear(id);
        }
        party.members.clear();
    }

    public void list(ServerPlayerEntity p) {
        Party party = byPlayer.get(p.getUuid());
        if (party == null) {
            msg(p, gray("You are not in a party. Invite someone with /party invite <player>."));
            return;
        }
        MutableText t = gray("Members (" + party.members.size() + "/" + MAX + "): ");
        boolean first = true;
        for (UUID id : party.members) {
            if (!first) t.append(gray(", "));
            first = false;
            boolean online = server.getPlayerManager().getPlayer(id) != null;
            t.append(Text.literal((id.equals(party.leader) ? "★" : "") + name(id))
                .formatted(online ? (id.equals(party.leader) ? Formatting.GOLD : Formatting.WHITE) : Formatting.DARK_GRAY));
        }
        msg(p, t);
    }

    public void chat(ServerPlayerEntity p, String message) {
        Party party = byPlayer.get(p.getUuid());
        if (party == null) {
            msg(p, gray("You are not in a party."));
            return;
        }
        Text line = Text.literal("[Party] ").formatted(Formatting.GOLD)
            .append(Text.literal(name(p.getUuid()) + ": ").formatted(Formatting.YELLOW))
            .append(Text.literal(message).formatted(Formatting.WHITE));
        for (UUID id : party.members) {
            ServerPlayerEntity m = server.getPlayerManager().getPlayer(id);
            if (m != null) m.sendMessage(line);
        }
    }

    private void remove(Party party, UUID id) {
        party.members.remove(id);
        byPlayer.remove(id);
        clear(id);
        if (party.members.size() <= 1) {
            for (UUID rest : party.members) {
                byPlayer.remove(rest);
                clear(rest);
                ServerPlayerEntity r = server.getPlayerManager().getPlayer(rest);
                if (r != null) msg(r, gray("The party was disbanded."));
            }
            party.members.clear();
            return;
        }
        if (party.leader.equals(id)) {
            party.leader = party.members.iterator().next();
            tell(party, who(party.leader).append(Text.literal(" now leads the party.").formatted(Formatting.GOLD)));
        }
        sync(party);
    }

    // ------------------------------------------------------------------ syncing

    private void clear(UUID id) {
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
        if (p != null && ServerPlayNetworking.canSend(p, Net.PartySync.ID)) ServerPlayNetworking.send(p, new Net.PartySync(List.of()));
    }

    private void sync(Party party) {
        for (UUID id : party.members) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p == null || !ServerPlayNetworking.canSend(p, Net.PartySync.ID)) continue;
            List<Net.PartyMember> list = new ArrayList<>();
            for (UUID m : party.members) {
                if (m.equals(id)) continue;
                ServerPlayerEntity mp = server.getPlayerManager().getPlayer(m);
                Profile pr = AotRpg.PROFILES.get(m);
                boolean online = mp != null;
                list.add(new Net.PartyMember(m, name(m), pr.created ? pr.level : 0,
                    pr.created ? pr.discipline.ordinal() : -1,
                    online ? mp.getHealth() : 0, online ? mp.getMaxHealth() : 20,
                    online ? AotRpg.STAMINA.fraction(m) : 0, online, m.equals(party.leader),
                    online ? mp.getX() : 0, online ? mp.getY() : 0, online ? mp.getZ() : 0,
                    online && mp.getWorld() == p.getWorld()));
            }
            ServerPlayNetworking.send(p, new Net.PartySync(list));
        }
    }

    public void tick(MinecraftServer server, int ticks) {
        this.server = server;
        now++;
        if (ticks % 20 == 0) {
            for (Iterator<Map.Entry<UUID, Map<UUID, Long>>> it = invites.entrySet().iterator(); it.hasNext(); ) {
                Map<UUID, Long> m = it.next().getValue();
                m.values().removeIf(exp -> exp < now);
                if (m.isEmpty()) it.remove();
            }
        }
        if (ticks % 5 == 0) {
            for (Party party : new LinkedHashSet<>(byPlayer.values())) sync(party);
        }
    }

    public void joined(ServerPlayerEntity p) {
        this.server = p.getServer();
        Party party = byPlayer.get(p.getUuid());
        if (party != null) {
            name(p.getUuid());
            tell(party, who(p.getUuid()).append(gray(" is back online.")));
        }
    }

    // ------------------------------------------------------------------ commands

    private static ServerPlayerEntity self(CommandContext<ServerCommandSource> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return c.getSource().getPlayerOrThrow();
    }

    void register(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("party")
            .executes(c -> {
                list(self(c));
                return 1;
            })
            .then(CommandManager.literal("invite").then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(c -> {
                    invite(self(c), EntityArgumentType.getPlayer(c, "player"));
                    return 1;
                })))
            .then(CommandManager.literal("accept")
                .executes(c -> {
                    accept(self(c), null);
                    return 1;
                })
                .then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> {
                    accept(self(c), EntityArgumentType.getPlayer(c, "player"));
                    return 1;
                })))
            .then(CommandManager.literal("decline").executes(c -> {
                decline(self(c));
                return 1;
            }))
            .then(CommandManager.literal("leave").executes(c -> {
                leave(self(c));
                return 1;
            }))
            .then(CommandManager.literal("kick").then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(c -> {
                    kick(self(c), EntityArgumentType.getPlayer(c, "player"));
                    return 1;
                })))
            .then(CommandManager.literal("leader").then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(c -> {
                    promote(self(c), EntityArgumentType.getPlayer(c, "player"));
                    return 1;
                })))
            .then(CommandManager.literal("disband").executes(c -> {
                disband(self(c));
                return 1;
            }))
            .then(CommandManager.literal("list").executes(c -> {
                list(self(c));
                return 1;
            }))
            .then(CommandManager.literal("chat").then(CommandManager.argument("message", StringArgumentType.greedyString())
                .executes(c -> {
                    chat(self(c), StringArgumentType.getString(c, "message"));
                    return 1;
                }))));
        d.register(CommandManager.literal("pc").then(CommandManager.argument("message", StringArgumentType.greedyString())
            .executes(c -> {
                chat(self(c), StringArgumentType.getString(c, "message"));
                return 1;
            })));
    }
}
