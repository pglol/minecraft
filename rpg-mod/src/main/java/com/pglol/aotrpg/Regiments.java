package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Regiments: squads players found themselves. A captain names it and gives it a short tag (shown
 * on name plates) and a colour; officers invite and manage; everyone who fights earns the regiment
 * XP. Regiment levels raise the member cap and give every member a bonus to XP and Marks. Each
 * regiment keeps a treasury members can pay into.
 */
public final class Regiments {
    public static final long CREATE_COST = 5000;
    public static final int CREATE_LEVEL = 10, MAX_LEVEL = 20;
    public static final int[] COLORS = {0xE0B96A, 0x3F8F4A, 0xB8473A, 0x4A78C0, 0x9A5CC8, 0x3AB0B0, 0xE08A3A, 0xD0D0D0};

    public static final class Regiment {
        public String id = "", name = "", tag = "", motto = "";
        public int color = 0xE0B96A;
        public boolean open = true;
        public String captain = "";
        /** uuid -> "captain" / "officer" / "soldier". */
        public Map<String, String> members = new LinkedHashMap<>();
        public Map<String, String> names = new HashMap<>();
        /** uuid -> regiment XP that member earned. */
        public Map<String, Long> contrib = new HashMap<>();
        public Set<String> invites = new HashSet<>();
        public long xp, treasury, created;
    }

    private static final class Data {
        Map<String, Regiment> regiments = new LinkedHashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private Data data = new Data();
    private Path file;
    private MinecraftServer server;

    public void open(MinecraftServer server) {
        this.server = server;
        file = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("regiments.json");
        data = new Data();
        try {
            if (Files.exists(file)) data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read regiments.json", e);
        }
        if (data == null || data.regiments == null) data = new Data();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save regiments.json", e);
        }
    }

    // ------------------------------------------------------------------ queries

    public Regiment of(UUID player) {
        String id = player.toString();
        for (Regiment r : data.regiments.values()) if (r.members.containsKey(id)) return r;
        return null;
    }

    public static int level(Regiment r) {
        return (int) Math.min(MAX_LEVEL, 1 + Math.floor(Math.sqrt(r.xp / 400.0)));
    }

    public static long xpFor(int level) {
        return (long) ((level - 1) * (level - 1) * 400L);
    }

    public static int cap(Regiment r) {
        return Math.min(40, 8 + 2 * level(r));
    }

    /** Members' bonus to XP and Marks: 1% per regiment level. */
    public double bonus(UUID player) {
        Regiment r = of(player);
        return r == null ? 0 : 0.01 * level(r);
    }

    public List<Regiment> all() {
        return new ArrayList<>(data.regiments.values());
    }

    // ------------------------------------------------------------------ XP

    /** Regiment XP for something a member did (titan kills, waves, raids, Calls to Arms). */
    public void gain(ServerPlayerEntity p, long xp) {
        Regiment r = of(p.getUuid());
        if (r == null || xp <= 0) return;
        int before = level(r);
        r.xp += xp;
        r.contrib.merge(p.getUuidAsString(), xp, Long::sum);
        int after = level(r);
        if (after > before) {
            for (ServerPlayerEntity m : online(r)) {
                Notify.toast(m, Text.literal(r.name + " reached level " + after).formatted(Formatting.GOLD),
                    Text.literal("Member cap " + cap(r) + " · +" + after + "% XP and Marks"), 0xE0B96A, "minecraft:white_banner", null);
            }
        }
        if (r.xp % 50 < xp || after > before) save();
    }

    private List<ServerPlayerEntity> online(Regiment r) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (String id : r.members.keySet()) {
            ServerPlayerEntity o = server.getPlayerManager().getPlayer(UUID.fromString(id));
            if (o != null) out.add(o);
        }
        return out;
    }

    // ------------------------------------------------------------------ actions

    private static String role(Regiment r, UUID p) {
        return r.members.getOrDefault(p.toString(), "");
    }

    private static boolean officer(Regiment r, UUID p) {
        String x = role(r, p);
        return x.equals("captain") || x.equals("officer");
    }

    private void msg(ServerPlayerEntity p, String s, boolean bad) {
        Notify.toast(p, Text.literal(s).formatted(bad ? Formatting.RED : Formatting.GOLD), null, bad ? 0xC0463A : 0xE0B96A,
            "minecraft:white_banner", null);
    }

    public void action(ServerPlayerEntity p, String action, String arg) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created) return;
        Regiment mine = of(p.getUuid());
        String me = p.getUuidAsString();
        switch (action) {
            case "create" -> {
                // arg: name|tag|colorIndex|open
                if (mine != null) break;
                String[] a = arg.split("\\|", -1);
                if (a.length < 4) break;
                String name = a[0].trim(), tag = a[1].trim().toUpperCase(Locale.ROOT);
                if (name.length() < 3 || name.length() > 24 || !name.matches("[A-Za-z0-9 '\\-]+")) {
                    msg(p, "Name: 3-24 letters, numbers or spaces", true);
                    break;
                }
                if (!tag.matches("[A-Z0-9]{2,4}")) {
                    msg(p, "Tag: 2-4 letters or numbers", true);
                    break;
                }
                for (Regiment r : data.regiments.values()) {
                    if (r.name.equalsIgnoreCase(name) || r.tag.equals(tag)) {
                        msg(p, "That name or tag is taken", true);
                        return;
                    }
                }
                if (pr.level < CREATE_LEVEL) {
                    msg(p, "Found a regiment from level " + CREATE_LEVEL, true);
                    break;
                }
                if (!AotRpg.WALLET.spendMarks(p, CREATE_COST)) {
                    msg(p, "Founding a regiment costs " + CREATE_COST + " Marks", true);
                    break;
                }
                Regiment r = new Regiment();
                r.id = UUID.randomUUID().toString().substring(0, 8);
                r.name = name;
                r.tag = tag;
                int ci = 0;
                try {
                    ci = Integer.parseInt(a[2]);
                } catch (NumberFormatException ignored) {
                    // default colour
                }
                r.color = COLORS[Math.floorMod(ci, COLORS.length)];
                r.open = a[3].equals("1");
                r.captain = me;
                r.members.put(me, "captain");
                r.names.put(me, pr.name);
                r.created = System.currentTimeMillis();
                data.regiments.put(r.id, r);
                p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.PLAYERS, 0.6f, 1.2f);
                msg(p, "The " + name + " is founded!", false);
            }
            case "join" -> {
                Regiment r = data.regiments.get(arg);
                if (mine != null || r == null) break;
                if (!r.open && !r.invites.contains(me)) {
                    msg(p, "That regiment is invite only", true);
                    break;
                }
                if (r.members.size() >= cap(r)) {
                    msg(p, r.name + " is full", true);
                    break;
                }
                r.invites.remove(me);
                r.members.put(me, "soldier");
                r.names.put(me, pr.name);
                for (ServerPlayerEntity o : online(r)) msg(o, pr.name + " joined " + r.name, false);
            }
            case "decline" -> {
                Regiment r = data.regiments.get(arg);
                if (r != null) r.invites.remove(me);
            }
            case "invite" -> {
                if (mine == null || !officer(mine, p.getUuid())) break;
                ServerPlayerEntity t = server.getPlayerManager().getPlayer(parse(arg));
                if (t == null || of(t.getUuid()) != null) {
                    msg(p, "They can't be invited (offline or in a regiment)", true);
                    break;
                }
                mine.invites.add(t.getUuidAsString());
                msg(p, "Invited " + AotRpg.PROFILES.get(t.getUuid()).name, false);
                Notify.toast(t, Text.literal("Regiment invite").formatted(Formatting.GOLD),
                    Text.literal(pr.name + " invites you to " + mine.name + " [" + mine.tag + "] · Social → Regiment"), 0xE0B96A,
                    "minecraft:white_banner", null);
                send(t, false);
            }
            case "leave" -> {
                if (mine == null) break;
                if (mine.captain.equals(me) && mine.members.size() > 1) {
                    msg(p, "Make another member captain first (or disband)", true);
                    break;
                }
                mine.members.remove(me);
                if (mine.members.isEmpty()) data.regiments.remove(mine.id);
            }
            case "kick", "promote", "demote", "captain" -> {
                if (mine == null || !officer(mine, p.getUuid())) break;
                String t = arg;
                if (!mine.members.containsKey(t) || t.equals(me)) break;
                String tr = mine.members.get(t);
                boolean captain = mine.captain.equals(me);
                if (tr.equals("captain") || (tr.equals("officer") && !captain)) break;
                switch (action) {
                    case "kick" -> mine.members.remove(t);
                    case "promote" -> mine.members.put(t, "officer");
                    case "demote" -> mine.members.put(t, "soldier");
                    default -> {
                        if (!captain) return;
                        mine.members.put(t, "captain");
                        mine.members.put(me, "officer");
                        mine.captain = t;
                    }
                }
            }
            case "disband" -> {
                if (mine == null || !mine.captain.equals(me)) break;
                for (ServerPlayerEntity o : online(mine)) msg(o, mine.name + " was disbanded", true);
                data.regiments.remove(mine.id);
            }
            case "deposit" -> {
                if (mine == null) break;
                long n;
                try {
                    n = Long.parseLong(arg);
                } catch (NumberFormatException e) {
                    break;
                }
                if (n <= 0 || !AotRpg.WALLET.spendMarks(p, n)) break;
                mine.treasury += n;
                gain(p, n / 50);
            }
            case "toggle_open" -> {
                if (mine != null && officer(mine, p.getUuid())) mine.open = !mine.open;
            }
            case "motto" -> {
                if (mine != null && officer(mine, p.getUuid())) mine.motto = arg.length() > 60 ? arg.substring(0, 60) : arg;
            }
            default -> { }
        }
        save();
        AotRpg.NAMETAGS.dirty();
        send(p, action.equals("open"));
    }

    private static UUID parse(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            return new UUID(0, 0);
        }
    }

    // ------------------------------------------------------------------ view

    public void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.RegimentView.ID)) return;
        Regiment mine = of(p.getUuid());
        String me = p.getUuidAsString();
        List<Net.RegimentInfo> list = new ArrayList<>();
        List<Net.RegimentInfo> invites = new ArrayList<>();
        for (Regiment r : data.regiments.values()) {
            Net.RegimentInfo info = info(r);
            if (r.invites.contains(me)) invites.add(info);
            list.add(info);
        }
        list.sort((a, b) -> Long.compare(b.xp(), a.xp()));
        List<Net.RegimentMember> members = new ArrayList<>();
        if (mine != null) {
            for (var e : mine.members.entrySet()) {
                UUID id = UUID.fromString(e.getKey());
                ServerPlayerEntity o = server.getPlayerManager().getPlayer(id);
                String name = o != null ? AotRpg.PROFILES.get(id).name : mine.names.getOrDefault(e.getKey(), "?");
                if (o != null) mine.names.put(e.getKey(), name);
                members.add(new Net.RegimentMember(e.getKey(), name, e.getValue(), o != null, mine.contrib.getOrDefault(e.getKey(), 0L),
                    o != null ? AotRpg.PROFILES.get(id).level : 0));
            }
        }
        ServerPlayNetworking.send(p, new Net.RegimentView(mine == null ? null : info(mine), mine == null ? "" : role(mine, p.getUuid()),
            members, list, invites, mine == null ? "" : mine.motto, CREATE_COST, CREATE_LEVEL, open));
    }

    private Net.RegimentInfo info(Regiment r) {
        String cap = r.names.getOrDefault(r.captain, "?");
        int lv = level(r);
        return new Net.RegimentInfo(r.id, r.name, r.tag, r.color, lv, r.xp, xpFor(lv), xpFor(Math.min(MAX_LEVEL, lv + 1)), r.members.size(),
            cap(r), r.open, cap, r.treasury);
    }
}
