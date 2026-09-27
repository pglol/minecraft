package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The in-game store (a test run, before the website store is linked). A weekly shop of cosmetics
 * and titles for Gold, the same for everyone and changing every Monday; and crates, for Marks or
 * Gold, that roll gear, supplies, cosmetics, titles or money. Something you already own rolls into
 * Gold or Marks instead.
 */
public final class Store {
    /** A title to wear, bought or won. Rarity 0 (common) to 4 (legendary). */
    public record Title(String id, String text, int color, int rarity) { }

    public static final List<Title> TITLES = List.of(
        new Title("st_wings", "Wings of Freedom", 0x9AB8D8, 1),
        new Title("st_scout_vet", "Survey Veteran", 0x5E8C4A, 1),
        new Title("st_wall_warden", "Warden of the Wall", 0xB8955A, 0),
        new Title("st_rose_guard", "Rose Guard", 0xC05A6A, 0),
        new Title("st_sina_noble", "Sina Noble", 0xD8C070, 2),
        new Title("st_underground", "Of the Underground", 0x8F8A7A, 0),
        new Title("st_nape_artist", "Nape Artist", 0xE0B96A, 2),
        new Title("st_blade_dancer", "Blade Dancer", 0x9AD0E0, 2),
        new Title("st_gas_guzzler", "Gas Guzzler", 0xA0D0A0, 0),
        new Title("st_potato", "Potato Thief", 0xC9A15A, 0),
        new Title("st_cadet", "Top Ten Cadet", 0xEDE3C8, 1),
        new Title("st_hope", "Humanity's Hope", 0xF0E0A0, 3),
        new Title("st_devil", "Devil of Paradis", 0xE04A3A, 3),
        new Title("st_wall_breaker", "Wall Breaker", 0xB08050, 2),
        new Title("st_ackerman", "Ackerman Blood", 0xC0302A, 4),
        new Title("st_coordinate", "The Coordinate", 0xF8F0C8, 4),
        new Title("st_shiganshina", "Shiganshina Survivor", 0xB0A080, 1),
        new Title("st_trost", "Hero of Trost", 0xD0B060, 2),
        new Title("st_garrison", "Garrison Drinker", 0xC06050, 0),
        new Title("st_mp", "Interior Police", 0x5A9A70, 1),
        new Title("st_titan_whisperer", "Titan Whisperer", 0xA070C0, 3),
        new Title("st_paths", "Walker of the Paths", 0x80C8E8, 4));

    private static final long[] TITLE_PRICE = {60, 120, 250, 450, 800};
    private static final long COSMETIC_PRICE = 220;

    public static Title title(String id) {
        for (Title t : TITLES) if (t.id().equals(id)) return t;
        return null;
    }

    // ------------------------------------------------------------------ the weekly shop

    private static long week() {
        return Math.floorDiv(LocalDate.now(ZoneOffset.UTC).toEpochDay() - 4, 7); // weeks start on Monday
    }

    private static long secondsLeft() {
        long day = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        long nextMonday = (week() + 1) * 7 + 4;
        long secOfDay = java.time.LocalTime.now(ZoneOffset.UTC).toSecondOfDay();
        return (nextMonday - day) * 86400 - secOfDay;
    }

    /** This week's offers: "title:id" and "cosmetic:id", six of them, the same for everyone. */
    public static List<String> offers() {
        List<String> pool = new ArrayList<>();
        for (Title t : TITLES) pool.add("title:" + t.id());
        for (Cosmetics.Def d : Cosmetics.ALL) if (!d.free()) pool.add("cosmetic:" + d.id());
        Random r = new Random(week() * 7919L + 17);
        java.util.Collections.shuffle(pool, r);
        return pool.subList(0, Math.min(6, pool.size()));
    }

    private static long price(String spec) {
        String[] a = spec.split(":", 2);
        if (a[0].equals("title")) {
            Title t = title(a[1]);
            return t == null ? 0 : TITLE_PRICE[t.rarity()];
        }
        return COSMETIC_PRICE;
    }

    private static boolean owns(ServerPlayerEntity p, String spec) {
        String[] a = spec.split(":", 2);
        if (a[0].equals("title")) return AotRpg.PROFILES.get(p.getUuid()).achievements.contains(a[1]);
        return AotRpg.COSMETICS.unlocked(p).contains(a[1]);
    }

    // ------------------------------------------------------------------ crates

    public record Crate(String id, String title, String desc, boolean gold, long price) { }

    public static final List<Crate> CRATES = List.of(
        new Crate("supply", "Supply Crate", "Gear, supplies and Marks, with a small chance of a title or cosmetic.", false, 1500),
        new Crate("officer", "Officer's Crate", "Rare and epic gear, cosmetics and titles.", true, 120),
        new Crate("commander", "Commander's Crate", "Epic and legendary gear, rare titles and cosmetics.", true, 350));

    /** What a crate gives: a reward spec (see Rewards), rolled on the player's own luck. */
    private String roll(ServerPlayerEntity p, String crate) {
        Random r = new Random(p.getRandom().nextLong());
        int x = r.nextInt(100);
        return switch (crate) {
            case "supply" -> x < 32 ? "gear:common" : x < 60 ? "gear:uncommon" : x < 72 ? "gear:rare" : x < 84 ? "marks:" + (800 + r.nextInt(1700))
                : x < 97 ? supplies(r) : x < 99 ? "cosmetic:" + randomCosmetic(p, r) : "title:" + randomTitle(p, r, 0, 1);
            case "officer" -> x < 28 ? "gear:rare" : x < 40 ? "gear:epic" : x < 64 ? "cosmetic:" + randomCosmetic(p, r)
                : x < 88 ? "title:" + randomTitle(p, r, 0, 3) : "gold:" + (60 + r.nextInt(120));
            default -> x < 30 ? "gear:epic" : x < 42 ? "gear:legendary" : x < 66 ? "cosmetic:" + randomCosmetic(p, r)
                : x < 95 ? "title:" + randomTitle(p, r, 2, 4) : "gold:" + (250 + r.nextInt(300));
        };
    }

    private static String supplies(Random r) {
        String[][] opts = {{"blade_component", "16"}, {"gas_canister", "2"}, {"ice_burst_cluster", "32"}, {"thunder_spear", "2"}};
        for (int i = 0; i < 6; i++) {
            String[] o = opts[r.nextInt(opts.length)];
            if (AotItems.exact(o[0]) != null) return "item:" + AotItems.namespace + ":" + o[0] + ":" + o[1];
        }
        return "marks:600";
    }

    private static String randomCosmetic(ServerPlayerEntity p, Random r) {
        List<String> left = new ArrayList<>();
        var have = AotRpg.COSMETICS.unlocked(p);
        for (Cosmetics.Def d : Cosmetics.ALL) if (!d.free() && !have.contains(d.id())) left.add(d.id());
        return left.isEmpty() ? "" : left.get(r.nextInt(left.size()));
    }

    private static String randomTitle(ServerPlayerEntity p, Random r, int minRarity, int maxRarity) {
        List<String> left = new ArrayList<>();
        var have = AotRpg.PROFILES.get(p.getUuid()).achievements;
        for (Title t : TITLES) if (t.rarity() >= minRarity && t.rarity() <= maxRarity && !have.contains(t.id())) left.add(t.id());
        return left.isEmpty() ? "" : left.get(r.nextInt(left.size()));
    }

    // ------------------------------------------------------------------ actions

    public void action(ServerPlayerEntity p, String action, String id) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created) return;
        switch (action) {
            case "buy" -> {
                if (!offers().contains(id) || owns(p, id)) return;
                long cost = price(id);
                if (!AotRpg.WALLET.spendGold(p, cost)) {
                    Notify.toast(p, Text.literal("Not enough Gold").formatted(Formatting.RED), Text.literal("That costs " + cost + " Gold"), 0xC0463A);
                    return;
                }
                grant(p, id);
                Notify.toast(p, Text.literal("Bought: " + Rewards.describe(id)).formatted(Formatting.GOLD), Text.literal("-" + cost + " Gold"), 0xE0B96A);
            }
            case "crate" -> {
                Crate c = null;
                for (Crate k : CRATES) if (k.id().equals(id)) c = k;
                if (c == null) return;
                boolean paid = c.gold() ? AotRpg.WALLET.spendGold(p, c.price()) : AotRpg.WALLET.spendMarks(p, c.price());
                if (!paid) {
                    Notify.toast(p, Text.literal("Not enough " + (c.gold() ? "Gold" : "Marks")).formatted(Formatting.RED),
                        Text.literal(c.title() + " costs " + c.price()), 0xC0463A);
                    return;
                }
                String got = roll(p, c.id());
                // Already have everything of that kind: it comes as money instead.
                if (got.endsWith(":")) got = c.gold() ? "gold:" + c.price() / 3 : "marks:" + c.price() / 2;
                grant(p, got);
                Notify.toast(p, Text.literal(c.title() + ": " + Rewards.describe(got)).formatted(Formatting.GOLD),
                    Text.literal("Opened for " + c.price() + (c.gold() ? " Gold" : " Marks")), 0xE0B96A, Rewards.icon(got), "crate");
            }
            default -> { }
        }
        AotRpg.PROFILES.save(p.getUuid());
        send(p, false);
    }

    /** Hands over a store reward, titles included. */
    public static void grant(ServerPlayerEntity p, String spec) {
        if (spec.startsWith("title:")) {
            AotRpg.PROFILES.get(p.getUuid()).achievements.add(spec.substring(6));
            AotRpg.PROFILES.save(p.getUuid());
            return;
        }
        Rewards.give(p, spec, "Store");
    }

    /** Testing: every title there is (achievements and store titles) for this player. */
    public static int unlockAllTitles(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        int n = 0;
        for (Tasks.Achievement a : Tasks.ACHIEVEMENTS) if (pr.achievements.add(a.id())) n++;
        for (Title t : TITLES) if (pr.achievements.add(t.id())) n++;
        AotRpg.PROFILES.save(p.getUuid());
        return n;
    }

    public void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.StoreView.ID)) return;
        List<Net.StoreOffer> list = new ArrayList<>();
        for (String o : offers()) {
            String[] a = o.split(":", 2);
            Title t = a[0].equals("title") ? title(a[1]) : null;
            String name = t != null ? t.text() : Rewards.describe(o);
            int color = t != null ? t.color() : 0xB08CD8;
            String kind = t != null ? new String[] {"Common", "Uncommon", "Rare", "Epic", "Legendary"}[t.rarity()] + " title" : "Cosmetic";
            list.add(new Net.StoreOffer(o, name, kind, color, price(o), owns(p, o)));
        }
        List<Net.StoreCrate> crates = new ArrayList<>();
        for (Crate c : CRATES) crates.add(new Net.StoreCrate(c.id(), c.title(), c.desc(), c.gold(), c.price()));
        ServerPlayNetworking.send(p, new Net.StoreView(list, crates, secondsLeft(), open));
        AotRpg.WALLET.sync(p);
    }
}
