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
import java.util.List;

/**
 * The battle pass: a season track of tiers with a free and a premium reward each. Pass XP comes
 * from playing (titans, quests, work orders, lifestyle crafts, a daily login bonus). Premium costs
 * Gold. Everything is in <world>/aot_rpg/season.json: change the id to start a new season.
 */
public final class Season {
    public static final class Tier {
        public String free = "";
        public String premium = "";

        Tier(String free, String premium) {
            this.free = free;
            this.premium = premium;
        }
    }

    public static final class Data {
        public String id = "s1";
        public String name = "Season 1: The Fall of Shiganshina";
        /** Epoch millis; 0 = no end date. */
        public long endsAt;
        public long premiumGold = 950;
        public long xpPerTier = 1000;
        public List<Tier> tiers = new ArrayList<>();
    }

    public static final long XP_TITAN = 40, XP_QUEST = 120, XP_ORDER = 100, XP_DAILY = 150;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private Data data = defaults();
    private Path file;

    private static Data defaults() {
        Data d = new Data();
        String[] premCos = {"trail_ember", "trail_frost", "trail_confetti", "trail_hearts", "trail_rainbow", "trail_void", "trail_lightning"};
        int c = 0;
        for (int t = 1; t <= 30; t++) {
            String free, prem;
            if (t % 10 == 0) free = t == 30 ? "gear:epic" : "gear:rare";
            else if (t % 5 == 0) free = "gear:uncommon";
            else if (t % 3 == 0) free = "tokens:" + (5 + t / 3);
            else free = "marks:" + (50 + t * 10);
            if (t == 30) prem = "gear:legendary";
            else if (t == 15) prem = "gold:150";
            else if (t % 4 == 1 && c < premCos.length) prem = "cosmetic:" + premCos[c++];
            else if (t % 10 == 0) prem = "gear:epic";
            else if (t % 5 == 0) prem = "gear:rare";
            else if (t % 2 == 0) prem = "marks:" + (150 + t * 20);
            else prem = "tokens:" + (10 + t / 2);
            d.tiers.add(new Tier(free, prem));
        }
        return d;
    }

    public void open(MinecraftServer server) {
        file = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("season.json");
        reload();
    }

    public void reload() {
        try {
            if (Files.exists(file)) {
                Data read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (read != null && read.tiers != null && !read.tiers.isEmpty()) data = read;
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read season.json", e);
        }
        if (data.id == null) data.id = "s1";
        if (data.xpPerTier <= 0) data.xpPerTier = 1000;
    }

    public String id() {
        return data.id;
    }

    public long endsAt() {
        return data.endsAt;
    }

    public boolean ended() {
        return data.endsAt > 0 && System.currentTimeMillis() > data.endsAt;
    }

    /** The account's pass, reset if it is from an older season. */
    private ProfileStore.Account account(ServerPlayerEntity p) {
        ProfileStore.Account a = AotRpg.PROFILES.account(p.getUuid());
        if (!data.id.equals(a.passSeason)) {
            a.passSeason = data.id;
            a.passXp = 0;
            a.passPremium = false;
            a.passFree.clear();
            a.passPrem.clear();
            AotRpg.PROFILES.saveAccount(p.getUuid());
        }
        return a;
    }

    public int tier(ProfileStore.Account a) {
        return (int) Math.min(data.tiers.size(), a.passXp / data.xpPerTier);
    }

    public void xp(ServerPlayerEntity p, long n) {
        if (n <= 0 || ended() || !AotRpg.PROFILES.get(p.getUuid()).created) return;
        ProfileStore.Account a = account(p);
        int before = tier(a);
        a.passXp = Math.min(a.passXp + n, data.xpPerTier * data.tiers.size());
        int after = tier(a);
        AotRpg.PROFILES.saveAccount(p.getUuid());
        AotRpg.TASKS.set(p, Tasks.PASS, after);
        if (after > before) {
            Titles.show(p, Text.literal("PASS TIER " + after).formatted(Formatting.GOLD, Formatting.BOLD),
                Text.literal("Rewards ready: Pause menu → Battle Pass").formatted(Formatting.YELLOW), 5, 50, 15);
            p.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.6f, 1.3f);
        }
    }

    /** First login each day gives a pass XP bonus. */
    public void daily(ServerPlayerEntity p) {
        if (!AotRpg.PROFILES.get(p.getUuid()).created || ended()) return;
        ProfileStore.Account a = account(p);
        long day = System.currentTimeMillis() / 86_400_000L;
        if (a.lastDaily == day) return;
        a.lastDaily = day;
        xp(p, XP_DAILY);
        p.sendMessage(Text.literal("Daily bonus: +" + XP_DAILY + " pass XP").formatted(Formatting.GOLD), false);
    }

    public void action(ServerPlayerEntity p, String action, int tier) {
        ProfileStore.Account a = account(p);
        switch (action) {
            case "claim" -> claim(p, a, tier, true);
            case "claimall" -> {
                for (int t = 0; t < tier(a); t++) claim(p, a, t, false);
            }
            case "buy" -> {
                if (a.passPremium) break;
                if (ended()) {
                    p.sendMessage(Text.literal("This season has ended.").formatted(Formatting.RED), true);
                    break;
                }
                if (!AotRpg.WALLET.spendGold(p, data.premiumGold)) {
                    p.sendMessage(Text.literal("You need ").formatted(Formatting.RED).append(Wallet.gold(data.premiumGold)), true);
                    break;
                }
                a.passPremium = true;
                AotRpg.PROFILES.saveAccount(p.getUuid());
                Titles.show(p, Text.literal("PREMIUM PASS").formatted(Formatting.GOLD, Formatting.BOLD),
                    Text.literal(data.name).formatted(Formatting.YELLOW), 5, 50, 15);
                p.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.8f, 1f);
            }
            default -> { }
        }
        send(p, action.equals("open"));
    }

    private void claim(ServerPlayerEntity p, ProfileStore.Account a, int t, boolean loud) {
        if (t < 0 || t >= tier(a)) return;
        Tier tr = data.tiers.get(t);
        if (!a.passFree.contains(t) && !tr.free.isEmpty()) {
            a.passFree.add(t);
            Rewards.give(p, tr.free, "Battle pass tier " + (t + 1));
        }
        if (a.passPremium && !a.passPrem.contains(t) && !tr.premium.isEmpty()) {
            a.passPrem.add(t);
            Rewards.give(p, tr.premium, "Battle pass tier " + (t + 1));
        }
        AotRpg.PROFILES.saveAccount(p.getUuid());
        if (loud) p.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 0.5f, 1.5f);
    }

    public void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.PassView.ID)) return;
        ProfileStore.Account a = account(p);
        List<Net.PassTier> tiers = new ArrayList<>();
        for (int t = 0; t < data.tiers.size(); t++) {
            Tier tr = data.tiers.get(t);
            tiers.add(new Net.PassTier(Rewards.describe(tr.free), Rewards.icon(tr.free), Rewards.color(tr.free), a.passFree.contains(t),
                Rewards.describe(tr.premium), Rewards.icon(tr.premium), Rewards.color(tr.premium), a.passPrem.contains(t)));
        }
        ServerPlayNetworking.send(p, new Net.PassView(data.name, data.endsAt, a.passXp, data.xpPerTier, a.passPremium,
            data.premiumGold, ended(), tiers, open));
    }

    public void setPremium(ServerPlayerEntity p, boolean on) {
        account(p).passPremium = on;
        AotRpg.PROFILES.saveAccount(p.getUuid());
        send(p, false);
    }
}
