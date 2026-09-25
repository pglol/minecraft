package com.pglol.aotrpg;

import com.google.gson.Gson;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;

/** Personal stats, server stats and leaderboards for the pause menu. */
public final class Stats {
    private static final Pattern CHARACTER = Pattern.compile("[0-9a-f-]{36}(\\.\\d+)?\\.json");
    private static final Gson GSON = new Gson();
    private MinecraftServer server;
    private long started, scannedAt;
    private List<Profile> everyone = new ArrayList<>();

    public void open(MinecraftServer server) {
        this.server = server;
        started = System.currentTimeMillis();
        scannedAt = 0;
    }

    /** Every created character on the server (saved files, with online players' live copies). */
    private List<Profile> everyone() {
        long now = System.currentTimeMillis();
        if (now - scannedAt < 60_000) return everyone;
        scannedAt = now;
        List<Profile> out = new ArrayList<>();
        java.util.Set<String> live = new java.util.HashSet<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            Profile pr = AotRpg.PROFILES.get(p.getUuid());
            live.add(AotRpg.PROFILES.activeStem(p.getUuid()) + ".json");
            if (pr.created) out.add(pr);
        }
        Path dir = AotRpg.PROFILES.dir();
        if (dir != null) {
            try (var files = Files.list(dir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    String n = f.getFileName().toString();
                    if (!CHARACTER.matcher(n).matches() || live.contains(n)) continue;
                    try {
                        Profile pr = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Profile.class);
                        if (pr != null && pr.created && pr.name != null) out.add(pr);
                    } catch (Exception ignored) {
                        // A broken or foreign file: skip it.
                    }
                }
            } catch (Exception e) {
                AotRpg.LOG.warn("Could not scan profiles for stats", e);
            }
        }
        everyone = out;
        return out;
    }

    private static long counter(Profile pr, String key) {
        return pr.counters == null ? 0 : pr.counters.getOrDefault(key, 0L);
    }

    private static String num(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static String time(long minutes) {
        return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
    }

    private static Net.StatBoard board(List<Profile> all, String title, ToLongFunction<Profile> f, String unit) {
        List<Net.StatLine> rows = new ArrayList<>();
        all.stream().filter(p -> f.applyAsLong(p) > 0).sorted(Comparator.comparingLong(f).reversed()).limit(8)
            .forEach(p -> rows.add(new Net.StatLine(p.name, num(f.applyAsLong(p)) + unit)));
        return new Net.StatBoard(title, rows);
    }

    public void send(ServerPlayerEntity p) {
        if (!ServerPlayNetworking.canSend(p, Net.StatsView.ID)) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        ProfileStore.Account acc = AotRpg.PROFILES.account(p.getUuid());
        List<Net.StatLine> mine = new ArrayList<>();
        if (pr.created) {
            Factions.Faction fa = Factions.of(pr);
            mine.add(new Net.StatLine("Character", pr.name + " · Level " + pr.level + (pr.discipline == null ? "" : " " + pr.discipline.title)));
            mine.add(new Net.StatLine("Time played", time(counter(pr, Tasks.MINUTES))));
            mine.add(new Net.StatLine("Titans slain", num(pr.titanKills)));
            mine.add(new Net.StatLine("Waves survived", num(counter(pr, Tasks.WAVES))));
            mine.add(new Net.StatLine("Home defences", num(counter(pr, Tasks.DEFEND))));
            mine.add(new Net.StatLine("Quests completed", num(counter(pr, Tasks.QUESTS))));
            mine.add(new Net.StatLine("Marks", num(pr.marks) + "  (earned " + num(counter(pr, Tasks.MARKS)) + ")"));
            mine.add(new Net.StatLine("Gold", num(acc.gold)));
            mine.add(new Net.StatLine("Faction", fa == null ? "None" : fa.title + " · " + pr.factionRep + " rep · " + num(counter(pr, Tasks.ORDERS)) + " orders"));
            mine.add(new Net.StatLine("Achievements", pr.achievements.size() + " / " + Tasks.ACHIEVEMENTS.size()
                + (pr.title == null || pr.title.isEmpty() ? "" : " · wearing \"" + pr.title + "\"")));
            mine.add(new Net.StatLine("Battle pass", "Tier " + AotRpg.SEASON.tier(acc) + (acc.passPremium ? " · Premium" : "")));
            mine.add(new Net.StatLine("Horses", pr.horses.size() + " owned"));
            mine.add(new Net.StatLine("Smithing", "Lv " + Lifestyle.level(pr, Lifestyle.SMITHING) + " · " + num(counter(pr, Tasks.FORGE)) + " forged"));
            mine.add(new Net.StatLine("Fishing", "Lv " + Lifestyle.level(pr, Lifestyle.FISHING) + " · " + num(counter(pr, Tasks.FISH)) + " caught"));
            mine.add(new Net.StatLine("Cooking", "Lv " + Lifestyle.level(pr, Lifestyle.COOKING) + " · " + num(counter(pr, Tasks.COOK)) + " dishes"));
            mine.add(new Net.StatLine("Market sales", num(counter(pr, Tasks.SOLD))));
            mine.add(new Net.StatLine("Furniture placed", num(counter(pr, Tasks.FURNITURE))));
            mine.add(new Net.StatLine("Skill resets used", pr.skillResets + " / " + Skill.MAX_RESETS));
        }

        List<Profile> all = everyone();
        List<Net.StatLine> srv = new ArrayList<>();
        long up = (System.currentTimeMillis() - started) / 60_000;
        srv.add(new Net.StatLine("Online now", server.getPlayerManager().getCurrentPlayerCount() + " / " + server.getPlayerManager().getMaxPlayerCount()));
        srv.add(new Net.StatLine("Characters", num(all.size())));
        srv.add(new Net.StatLine("Titans slain (all)", num(all.stream().mapToLong(x -> x.titanKills).sum())));
        srv.add(new Net.StatLine("Marks in purses", num(all.stream().mapToLong(x -> x.marks).sum())));
        srv.add(new Net.StatLine("Highest level", all.stream().mapToInt(x -> x.level).max().orElse(0) + ""));
        Factions.Faction lead = AotRpg.FACTIONS.leader();
        StringBuilder held = new StringBuilder();
        for (Factions.Faction f : Factions.Faction.values()) {
            held.append(held.length() == 0 ? "" : " · ").append(Factions.abbr(f)).append(' ').append(AotRpg.FACTIONS.sectorsHeld(f));
        }
        srv.add(new Net.StatLine("Leading faction", lead == null ? "None (contested)" : lead.title));
        srv.add(new Net.StatLine("Sectors held", held.toString()));
        FactionWar.Event ev = AotRpg.WAR.active();
        srv.add(new Net.StatLine("Call to Arms", ev != null ? "Defend " + ev.town + " now!"
            : "Next in ~" + Math.max(1, (AotRpg.WAR.nextAt() - System.currentTimeMillis()) / 60_000) + " min"));
        long ends = AotRpg.SEASON.endsAt();
        srv.add(new Net.StatLine("Season", AotRpg.SEASON.id() + (ends > 0 ? " · ends in " + Math.max(0, (ends - System.currentTimeMillis()) / 86_400_000L) + " days" : "")));
        srv.add(new Net.StatLine("Uptime", time(up)));

        List<Net.StatBoard> boards = new ArrayList<>();
        boards.add(board(all, "Highest level", x -> x.level, ""));
        boards.add(board(all, "Titan slayers", x -> x.titanKills, " kills"));
        boards.add(board(all, "Richest", x -> x.marks, " Marks"));
        boards.add(board(all, "Faction heroes", x -> x.factionRep, " rep"));
        boards.add(board(all, "Achievers", x -> x.achievements == null ? 0 : x.achievements.size(), ""));
        boards.add(board(all, "Most devoted", x -> counter(x, Tasks.MINUTES), " min"));
        ServerPlayNetworking.send(p, new Net.StatsView(mine, srv, boards));
    }
}
