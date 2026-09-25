package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Daily, weekly, monthly and seasonal task boards (everyone gets the same board for the period),
 * and lifetime achievements that unlock titles shown by your name. Both run off per-character
 * counters bumped from the game (titans slain, waves cleared, quests, crafts...).
 */
public final class Tasks {
    // Counters.
    public static final String TITANS = "titans", WAVES = "waves", QUESTS = "quests", ORDERS = "orders", COOK = "cook", FISH = "fish",
        FORGE = "forge", MARKS = "marks", DEFEND = "defend", MINUTES = "minutes", PASS = "pass", FURNITURE = "furniture", SOLD = "sold";

    public enum Period { DAILY, WEEKLY, MONTHLY, SEASON }

    /** A task template: the counter, how many, and the reward. */
    public record Task(String id, Period period, String text, String stat, int goal, String reward, long passXp) { }

    public static final List<Task> POOL = List.of(
        new Task("d_titans", Period.DAILY, "Slay %d titans", TITANS, 10, "marks:120", 150),
        new Task("d_titans_s", Period.DAILY, "Slay %d titans", TITANS, 5, "marks:70", 100),
        new Task("d_wave", Period.DAILY, "Clear %d titan wave", WAVES, 1, "marks:100", 150),
        new Task("d_quest", Period.DAILY, "Complete %d quests", QUESTS, 2, "marks:90", 120),
        new Task("d_cook", Period.DAILY, "Cook %d meals", COOK, 4, "marks:60", 90),
        new Task("d_fish", Period.DAILY, "Land %d fish", FISH, 3, "marks:60", 90),
        new Task("d_forge", Period.DAILY, "Work the forge %d times", FORGE, 2, "marks:80", 100),
        new Task("d_order", Period.DAILY, "Finish %d work order", ORDERS, 1, "marks:90", 120),
        new Task("d_play", Period.DAILY, "Play for %d minutes", MINUTES, 30, "tokens:3", 100),
        new Task("w_titans", Period.WEEKLY, "Slay %d titans", TITANS, 75, "gear:rare", 600),
        new Task("w_waves", Period.WEEKLY, "Clear %d titan waves", WAVES, 5, "marks:600", 500),
        new Task("w_quests", Period.WEEKLY, "Complete %d quests", QUESTS, 10, "marks:500", 500),
        new Task("w_orders", Period.WEEKLY, "Finish %d work orders", ORDERS, 5, "marks:550", 500),
        new Task("w_craft", Period.WEEKLY, "Cook %d meals", COOK, 20, "marks:350", 400),
        new Task("w_fish", Period.WEEKLY, "Land %d fish", FISH, 15, "marks:350", 400),
        new Task("w_marks", Period.WEEKLY, "Earn %d Marks", MARKS, 3000, "tokens:10", 400),
        new Task("m_titans", Period.MONTHLY, "Slay %d titans", TITANS, 300, "gear:epic", 2000),
        new Task("m_waves", Period.MONTHLY, "Clear %d titan waves", WAVES, 20, "gold:50", 1500),
        new Task("m_quests", Period.MONTHLY, "Complete %d quests", QUESTS, 40, "marks:2500", 1500),
        new Task("m_play", Period.MONTHLY, "Play for %d minutes", MINUTES, 900, "marks:2000", 1500),
        new Task("s_titans", Period.SEASON, "Slay %d titans this season", TITANS, 1000, "gear:legendary", 5000),
        new Task("s_waves", Period.SEASON, "Clear %d titan waves this season", WAVES, 60, "gold:150", 4000),
        new Task("s_defend", Period.SEASON, "Defend your home %d times", DEFEND, 2, "cosmetic:trail_void", 3000),
        new Task("s_pass", Period.SEASON, "Reach battle pass tier %d", PASS, 20, "gold:100", 0));

    private static final int[] BOARD = {3, 3, 2, 3};

    /** A lifetime achievement; its title can be worn by your name. */
    public record Achievement(String id, String title, String desc, String stat, long goal, String reward, int color) { }

    public static final List<Achievement> ACHIEVEMENTS = List.of(
        new Achievement("first_blood", "Titan Hunter", "Slay your first titan", TITANS, 1, "marks:50", 0xC9B38A),
        new Achievement("slayer_100", "Titan Slayer", "Slay 100 titans", TITANS, 100, "marks:500", 0xE0B96A),
        new Achievement("slayer_1000", "Scourge of Titans", "Slay 1,000 titans", TITANS, 1000, "gear:epic", 0xE04A3A),
        new Achievement("slayer_5000", "Humanity's Strongest", "Slay 5,000 titans", TITANS, 5000, "gear:legendary", 0x9FE3FF),
        new Achievement("waves_10", "Wave Breaker", "Clear 10 titan waves", WAVES, 10, "marks:300", 0xE08A3A),
        new Achievement("waves_100", "Wall of Flesh", "Clear 100 titan waves", WAVES, 100, "gold:50", 0xFF6B3A),
        new Achievement("quests_25", "Dependable", "Complete 25 quests", QUESTS, 25, "marks:400", 0x8FD18F),
        new Achievement("orders_25", "Loyal Soldier", "Finish 25 faction work orders", ORDERS, 25, "marks:500", 0x6FA8FF),
        new Achievement("cook_100", "Field Cook", "Cook 100 meals", COOK, 100, "marks:300", 0xF2C14E),
        new Achievement("fish_50", "Angler", "Land 50 fish", FISH, 50, "marks:300", 0x5BC0DE),
        new Achievement("forge_50", "Master Smith", "Work the forge 50 times", FORGE, 50, "gear:rare", 0xB0B0B0),
        new Achievement("marks_25k", "Merchant Prince", "Earn 25,000 Marks", MARKS, 25_000, "gold:25", 0xFFD54A),
        new Achievement("defend_5", "Hearth Keeper", "Defend your home 5 times", DEFEND, 5, "marks:600", 0xD07A4A),
        new Achievement("furniture_20", "Homemaker", "Place 20 pieces of furniture", FURNITURE, 20, "marks:250", 0xC77DFF),
        new Achievement("veteran", "Veteran", "Play for 50 hours", MINUTES, 3000, "gold:50", 0xEDE3C8));

    public static Achievement achievement(String id) {
        for (Achievement a : ACHIEVEMENTS) if (a.id().equals(id)) return a;
        return null;
    }

    // ------------------------------------------------------------------ periods

    static String key(Period p) {
        LocalDate d = LocalDate.now(ZoneOffset.UTC);
        return switch (p) {
            case DAILY -> d.toString();
            case WEEKLY -> d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case MONTHLY -> d.getYear() + "-" + d.getMonthValue();
            case SEASON -> AotRpg.SEASON.id();
        };
    }

    /** Seconds until this period resets (-1 for a season, which ends when the pass does). */
    static long secondsLeft(Period p) {
        LocalDate d = LocalDate.now(ZoneOffset.UTC);
        LocalDate next = switch (p) {
            case DAILY -> d.plusDays(1);
            case WEEKLY -> d.plusDays(8 - d.getDayOfWeek().getValue());
            case MONTHLY -> d.withDayOfMonth(1).plusMonths(1);
            case SEASON -> null;
        };
        if (next == null) return AotRpg.SEASON.endsAt() > 0 ? (AotRpg.SEASON.endsAt() - System.currentTimeMillis()) / 1000 : -1;
        return next.atStartOfDay().toEpochSecond(ZoneOffset.UTC) - System.currentTimeMillis() / 1000;
    }

    /** This period's board: the same tasks for everyone, picked from the key. */
    static List<Task> board(Period p) {
        List<Task> pool = new ArrayList<>();
        for (Task t : POOL) if (t.period() == p) pool.add(t);
        List<Task> out = new ArrayList<>();
        long h = key(p).hashCode() * 0x9E3779B97F4A7C15L;
        int n = Math.min(BOARD[p.ordinal()], pool.size());
        while (out.size() < n) {
            h ^= h >>> 29;
            h *= 0xBF58476D1CE4E5B9L;
            Task t = pool.get((int) Math.floorMod(h, pool.size()));
            boolean dup = false;
            for (Task o : out) if (o.id().equals(t.id()) || o.stat().equals(t.stat())) dup = true;
            if (!dup) out.add(t);
            else if (pool.size() <= n) break;
            h += 0x632BE59BD9B4E019L;
        }
        return out;
    }

    /** Resets a period's progress when a new period begins. */
    private static void roll(Profile pr) {
        for (Period p : Period.values()) {
            String k = key(p);
            String was = pr.taskKeys.get(p.name());
            if (k.equals(was)) continue;
            pr.taskKeys.put(p.name(), k);
            pr.taskProgress.keySet().removeIf(id -> id.startsWith(p.name() + ":"));
            pr.taskClaimed.removeIf(id -> id.startsWith(p.name() + ":"));
        }
    }

    // ------------------------------------------------------------------ counting

    /** Something happened: bump the counter, the task boards and achievements. */
    public void count(ServerPlayerEntity p, String stat, long n) {
        if (n <= 0) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created) return;
        roll(pr);
        pr.counters.merge(stat, n, Long::sum);
        progress(p, pr, stat, n);
    }

    private void progress(ServerPlayerEntity p, Profile pr, String stat, long n) {
        for (Period per : Period.values()) {
            for (Task t : board(per)) {
                if (!t.stat().equals(stat)) continue;
                String id = per.name() + ":" + t.id();
                long before = pr.taskProgress.getOrDefault(id, 0L);
                if (before >= t.goal()) continue;
                long after = t.stat().equals(PASS) ? Math.min(t.goal(), pr.counters.getOrDefault(stat, 0L)) : Math.min(t.goal(), before + n);
                pr.taskProgress.put(id, after);
                if (after >= t.goal()) {
                    Notify.toast(p, Text.literal("Task complete").formatted(Formatting.GOLD),
                        Text.literal(String.format(t.text(), t.goal()) + " · claim it in Tasks"), 0x6FCF5A, Rewards.icon(t.reward()), null);
                    p.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 0.5f, 1.4f);
                }
            }
        }
        for (Achievement a : ACHIEVEMENTS) {
            if (!a.stat().equals(stat) || pr.achievements.contains(a.id()) || pr.counters.getOrDefault(stat, 0L) < a.goal()) continue;
            pr.achievements.add(a.id());
            Rewards.give(p, a.reward(), "Achievement: " + a.title());
            Titles.show(p, Text.literal("ACHIEVEMENT").formatted(Formatting.GOLD, Formatting.BOLD),
                Text.literal(a.title() + "  ·  " + a.desc()).withColor(a.color()), 10, 60, 20);
            p.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.8f, 1f);
            Notify.toast(p, Text.literal("Title unlocked: " + a.title()).withColor(a.color()), Text.literal("Wear it from Tasks → Achievements"),
                a.color(), "minecraft:name_tag", null);
        }
    }

    /** A counter that is a level, not a sum (battle pass tier). */
    public void set(ServerPlayerEntity p, String stat, long value) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created || value <= pr.counters.getOrDefault(stat, 0L)) return;
        roll(pr);
        pr.counters.put(stat, value);
        progress(p, pr, stat, 0);
    }

    // ------------------------------------------------------------------ actions and view

    public void action(ServerPlayerEntity p, String action, String arg) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created) return;
        roll(pr);
        switch (action) {
            case "claim" -> claim(p, pr, arg);
            case "claimall" -> {
                for (Period per : Period.values()) for (Task t : board(per)) claim(p, pr, per.name() + ":" + t.id());
            }
            case "title" -> {
                if (arg.isEmpty() || pr.achievements.contains(arg)) {
                    pr.title = arg;
                    AotRpg.ROLES.refresh(p);
                }
            }
            default -> { }
        }
        AotRpg.PROFILES.save(p.getUuid());
        send(p, action.equals("open"));
    }

    private void claim(ServerPlayerEntity p, Profile pr, String id) {
        if (pr.taskClaimed.contains(id)) return;
        int colon = id.indexOf(':');
        if (colon < 0) return;
        Period per;
        try {
            per = Period.valueOf(id.substring(0, colon));
        } catch (Exception e) {
            return;
        }
        for (Task t : board(per)) {
            if (!t.id().equals(id.substring(colon + 1))) continue;
            if (pr.taskProgress.getOrDefault(id, 0L) < t.goal()) return;
            pr.taskClaimed.add(id);
            Rewards.give(p, t.reward(), "Task: " + String.format(t.text(), t.goal()));
            if (t.passXp() > 0) AotRpg.SEASON.xp(p, t.passXp());
            p.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 0.6f, 1.2f);
        }
    }

    public void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.TasksView.ID)) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        roll(pr);
        List<Net.TaskEntry> tasks = new ArrayList<>();
        for (Period per : Period.values()) {
            for (Task t : board(per)) {
                String id = per.name() + ":" + t.id();
                String reward = Rewards.describe(t.reward()) + (t.passXp() > 0 ? " · " + t.passXp() + " pass XP" : "");
                tasks.add(new Net.TaskEntry(id, per.ordinal(), String.format(t.text(), t.goal()), (int) Math.min(t.goal(), pr.taskProgress.getOrDefault(id, 0L)),
                    t.goal(), reward, Rewards.icon(t.reward()), pr.taskClaimed.contains(id)));
            }
        }
        List<Net.AchievementEntry> ach = new ArrayList<>();
        for (Achievement a : ACHIEVEMENTS) {
            ach.add(new Net.AchievementEntry(a.id(), a.title(), a.desc(), Math.min(a.goal(), pr.counters.getOrDefault(a.stat(), 0L)), a.goal(),
                Rewards.describe(a.reward()), a.color(), pr.achievements.contains(a.id())));
        }
        long[] left = new long[Period.values().length];
        for (Period per : Period.values()) left[per.ordinal()] = secondsLeft(per);
        ServerPlayNetworking.send(p, new Net.TasksView(tasks, ach, pr.title == null ? "" : pr.title, left, open));
    }

    /** Title text and colour of an achievement id, or null. */
    public static Achievement worn(Profile pr) {
        return pr.title == null || pr.title.isEmpty() || !pr.achievements.contains(pr.title) ? null : achievement(pr.title);
    }

    static Map<String, Long> counters(Profile pr) {
        return pr.counters;
    }
}
