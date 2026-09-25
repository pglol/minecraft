package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Side quests built from the map's areas (explore towns and camps, clear titan caves, titan hunts),
 * the tracked quest, map waypoints and party-highlighted quests. The main story lives in Story.
 */
public final class Quests {
    public record Def(String id, String title, String category, String text, int level, int x, int y, int z,
                      int radius, int kills, long xp) { }

    public static final int ACTIVE = 1, DONE = 2;
    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final Map<UUID, int[]> waypoints = new HashMap<>();
    private final Map<UUID, Integer> lastMarkers = new HashMap<>();
    private MinecraftServer server;

    public void load(MinecraftServer server) {
        this.server = server;
        defs.clear();
        for (Net.Area a : AotRpg.PLACES.areas()) {
            switch (a.look()) {
                case "town", "landmark", "marley" -> add(new Def("visit:" + a.id(), "Explore " + a.name(), "Exploration",
                    "Travel to " + a.name() + " (" + a.sub() + ").", a.min(), a.x(), a.y(), a.z(), 40, 0, 40 + a.min() * 6L));
                case "camp" -> add(new Def("visit:" + a.id(), "Report to " + a.name(), "Survey Corps",
                    "Find the Survey Corps camp " + a.name() + " beyond the walls.", a.min(), a.x(), a.y(), a.z(), 35, 0, 60 + a.min() * 7L));
                case "cave" -> add(new Def("cave:" + a.id(), "Clear " + a.name(), "Titan Cave",
                    "Slay 5 titans inside " + a.name() + ". Bring a party.", a.min(), a.x(), a.y(), a.z(), 90, 5, 150 + a.min() * 10L));
                case "danger" -> {
                    if (a.titans() > 0) add(new Def("hunt:" + a.id(), "Titan Hunt: " + a.name(), "Titan Hunt",
                        "Slay 8 titans in " + a.name() + ".", a.min(), a.x(), a.y(), a.z(), 600, 8, 200 + a.min() * 10L));
                }
                default -> { }
            }
        }
        AotRpg.LOG.info("{} side quests from the map", defs.size());
    }

    private void add(Def d) {
        defs.put(d.id(), d);
    }

    private static String tracked(Profile pr) {
        return pr.tracked == null || pr.tracked.isEmpty() ? "main" : pr.tracked;
    }

    // ------------------------------------------------------------------ actions

    public void action(ServerPlayerEntity p, String id, String action) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created) return;
        Def d = defs.get(id);
        if (!id.equals("main") && d == null) return;
        int[] st = pr.quests.get(id);
        switch (action) {
            case "accept" -> {
                if (d == null || st != null) return;
                pr.quests.put(id, new int[] {ACTIVE, 0});
                pr.tracked = id;
                p.sendMessage(Text.literal("Quest accepted: ").formatted(Formatting.GRAY)
                    .append(Text.literal(d.title()).formatted(Formatting.GOLD)), false);
            }
            case "abandon" -> {
                if (d == null || st == null || st[0] != ACTIVE) return;
                pr.quests.remove(id);
                if (tracked(pr).equals(id)) pr.tracked = "main";
            }
            case "track" -> {
                if (!id.equals("main") && (st == null || st[0] != ACTIVE)) return;
                pr.tracked = tracked(pr).equals(id) && !id.equals("main") ? "main" : id;
            }
            case "highlight" -> {
                Parties.Party party = AotRpg.PARTIES.of(p.getUuid());
                if (party == null) {
                    p.sendMessage(Text.literal("Join a party to highlight quests for them.").formatted(Formatting.GRAY), true);
                    return;
                }
                if (party.highlights.containsKey(id)) party.highlights.remove(id);
                else party.highlights.put(id, p.getUuid());
                for (UUID m : party.members) {
                    ServerPlayerEntity mp = server.getPlayerManager().getPlayer(m);
                    if (mp != null) {
                        send(mp);
                        markers(mp, true);
                    }
                }
            }
            default -> { }
        }
        AotRpg.PROFILES.save(p.getUuid());
        send(p);
        sendObjective(p);
        markers(p, true);
    }

    public void setWaypoint(ServerPlayerEntity p, Net.SetWaypoint w) {
        if (w.clear()) waypoints.remove(p.getUuid());
        else waypoints.put(p.getUuid(), new int[] {w.x(), p.getServerWorld().getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, w.x(), w.z()), w.z()});
        refreshParty(p);
    }

    private void refreshParty(ServerPlayerEntity p) {
        Parties.Party party = AotRpg.PARTIES.of(p.getUuid());
        if (party == null) {
            markers(p, true);
            return;
        }
        for (UUID m : party.members) {
            ServerPlayerEntity mp = server.getPlayerManager().getPlayer(m);
            if (mp != null) markers(mp, true);
        }
    }

    // ------------------------------------------------------------------ progress

    public void onTitanKill(ServerPlayerEntity p, double x, double z) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        boolean changed = false;
        for (Map.Entry<String, int[]> e : new ArrayList<>(pr.quests.entrySet())) {
            Def d = defs.get(e.getKey());
            if (d == null || d.kills() == 0 || e.getValue()[0] != ACTIVE) continue;
            double dx = x - d.x(), dz = z - d.z();
            if (dx * dx + dz * dz > (double) d.radius() * d.radius()) continue;
            e.getValue()[1]++;
            changed = true;
            if (e.getValue()[1] >= d.kills()) complete(p, pr, d);
            else p.sendMessage(Text.literal(d.title() + ": " + e.getValue()[1] + " / " + d.kills()).formatted(Formatting.GOLD), true);
        }
        if (changed) {
            send(p);
            sendObjective(p);
        }
    }

    public void tick(ServerPlayerEntity p, Profile pr, int ticks) {
        if (!pr.created || ticks % 20 != 0) return;
        for (Map.Entry<String, int[]> e : new ArrayList<>(pr.quests.entrySet())) {
            Def d = defs.get(e.getKey());
            if (d == null || d.kills() > 0 || e.getValue()[0] != ACTIVE) continue;
            double dx = p.getX() - d.x(), dz = p.getZ() - d.z();
            if (dx * dx + dz * dz < (double) d.radius() * d.radius()) complete(p, pr, d);
        }
        int[] w = waypoints.get(p.getUuid());
        if (w != null) {
            double dx = p.getX() - w[0], dz = p.getZ() - w[2];
            if (dx * dx + dz * dz < 8 * 8) {
                waypoints.remove(p.getUuid());
                p.sendMessage(Text.literal("You reached your mark.").formatted(Formatting.GRAY), true);
                refreshParty(p);
            }
        }
        if (ticks % 40 == 0) markers(p, false);
    }

    private void complete(ServerPlayerEntity p, Profile pr, Def d) {
        pr.quests.put(d.id(), new int[] {DONE, d.kills()});
        if (tracked(pr).equals(d.id())) pr.tracked = "main";
        Titles.show(p, Text.literal("QUEST COMPLETE").formatted(Formatting.GOLD, Formatting.BOLD),
            Text.literal(d.title() + "  ·  +" + d.xp() + " XP").formatted(Formatting.YELLOW), 10, 60, 20);
        p.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.8f, 1f);
        // Rewards: supplies from the AoT mod for fights, Marks for everything, gear for fights.
        if (d.kills() > 0) {
            ItemStack gas = AotItems.bestStack(4 + d.level() / 10, AotItems.GAS);
            if (!gas.isEmpty()) p.giveItemStack(gas);
            AotRpg.WALLET.earn(p, 30 + d.level() * 6L, d.title());
            AotRpg.GEAR.reward(p, d.level(), d.kills() >= 8 ? 1 : 0);
        } else {
            AotRpg.WALLET.earn(p, 10 + d.level() * 2L, d.title());
        }
        AotRpg.PROGRESSION.addXp(p, pr, d.xp());
        AotRpg.ROLES.addPoints(p, 5);
        AotRpg.SEASON.xp(p, d.kills() > 0 ? Season.XP_QUEST : Season.XP_QUEST / 2);
        AotRpg.EVENTS.onQuest(p);
        AotRpg.TASKS.count(p, Tasks.QUESTS, 1);
        AotRpg.PROFILES.save(p.getUuid());
        send(p);
        sendObjective(p);
        markers(p, true);
    }

    // ------------------------------------------------------------------ syncing

    public void send(ServerPlayerEntity p) {
        if (!ServerPlayNetworking.canSend(p, Net.Quests.ID)) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        Parties.Party party = AotRpg.PARTIES.of(p.getUuid());
        List<Net.QuestView> list = new ArrayList<>();
        Story.View main = AotRpg.STORY.view(pr);
        list.add(new Net.QuestView("main", main.chapter(), "Main Story", main.text(), 1, 1, main.progress(), main.xp(),
            tracked(pr).equals("main"), highlighter(party, "main"), main.hasTarget(), main.x(), main.z()));
        for (Def d : defs.values()) {
            int[] st = pr.quests.get(d.id());
            int state = st == null ? 0 : st[0];
            String progress = d.kills() > 0 ? (st == null ? 0 : Math.min(d.kills(), st[1])) + " / " + d.kills() : "";
            list.add(new Net.QuestView(d.id(), d.title(), d.category(), d.text(), d.level(), state, progress, d.xp(),
                tracked(pr).equals(d.id()), highlighter(party, d.id()), true, d.x(), d.z()));
        }
        ServerPlayNetworking.send(p, new Net.Quests(list));
    }

    private String highlighter(Parties.Party party, String id) {
        if (party == null) return "";
        UUID by = party.highlights.get(id);
        if (by == null) return "";
        Profile pr = AotRpg.PROFILES.get(by);
        return pr.created ? pr.firstName : "?";
    }

    /** The objective under the minimap: the tracked quest. */
    public void sendObjective(ServerPlayerEntity p) {
        if (!ServerPlayNetworking.canSend(p, Net.Objective.ID)) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created) return;
        Def d = defs.get(tracked(pr));
        int[] st = d == null ? null : pr.quests.get(d.id());
        if (d == null || st == null || st[0] != ACTIVE) {
            Story.View v = AotRpg.STORY.view(pr);
            ServerPlayNetworking.send(p, new Net.Objective(v.chapter(), v.text(), v.progress(), v.hasTarget(), v.x(), v.y(), v.z()));
            return;
        }
        String progress = d.kills() > 0 ? Math.min(d.kills(), st[1]) + " / " + d.kills() : "";
        ServerPlayNetworking.send(p, new Net.Objective(d.category(), d.title(), progress, true, d.x(), d.y(), d.z()));
    }

    /** Markers for map, minimap and beams. Sent when they change (or when forced). */
    public void markers(ServerPlayerEntity p, boolean force) {
        if (!ServerPlayNetworking.canSend(p, Net.Markers.ID)) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        List<Net.Marker> list = new ArrayList<>();
        Def d = defs.get(tracked(pr));
        int[] st = d == null ? null : pr.quests.get(d.id());
        if (d != null && st != null && st[0] == ACTIVE) list.add(new Net.Marker("quest", d.title(), d.x(), d.y(), d.z(), 0xE0B96A));
        else {
            Story.View v = AotRpg.STORY.view(pr);
            if (v.hasTarget()) list.add(new Net.Marker("quest", v.text(), v.x(), v.y(), v.z(), 0xE0B96A));
        }
        int[] w = waypoints.get(p.getUuid());
        if (w != null) list.add(new Net.Marker("mark", "Your mark", w[0], w[1], w[2], 0x55C8FF));
        AotRpg.WAR.markers(p, list);
        Parties.Party party = AotRpg.PARTIES.of(p.getUuid());
        if (party != null) {
            for (UUID m : party.members) {
                if (m.equals(p.getUuid())) continue;
                int[] pw = waypoints.get(m);
                Profile mp = AotRpg.PROFILES.get(m);
                if (pw != null) list.add(new Net.Marker("party_mark", (mp.created ? mp.firstName : "Party") + "'s mark", pw[0], pw[1], pw[2], 0x5BD35B));
            }
            for (Map.Entry<String, UUID> h : party.highlights.entrySet()) {
                Profile by = AotRpg.PROFILES.get(h.getValue());
                if (h.getKey().equals("main")) {
                    Story.View v = AotRpg.STORY.view(by);
                    if (v.hasTarget()) list.add(new Net.Marker("party_quest", "★ " + v.text(), v.x(), v.y(), v.z(), 0xD070FF));
                    continue;
                }
                Def hd = defs.get(h.getKey());
                if (hd != null) list.add(new Net.Marker("party_quest", "★ " + hd.title(), hd.x(), hd.y(), hd.z(), 0xD070FF));
            }
        }
        AotRpg.HOMES.markers(p, list);
        int hash = Objects.hash(list.toArray());
        Integer last = lastMarkers.put(p.getUuid(), hash);
        if (!force && last != null && last == hash) return;
        ServerPlayNetworking.send(p, new Net.Markers(list));
    }

    public void forget(ServerPlayerEntity p) {
        lastMarkers.remove(p.getUuid());
    }
}
