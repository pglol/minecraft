package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        new Title("st_paths", "Walker of the Paths", 0x80C8E8, 4),
        // Mythic (red): crates only, and only on the rarest rolls.
        new Title("st_rumbling", "The Rumbling", 0xE02A2A, 5),
        new Title("st_strongest", "Humanity's Strongest", 0xE02A2A, 5),
        new Title("st_ymir", "Ymir's Chosen", 0xE02A2A, 5),
        new Title("st_pker", "Pk'er", 0xE02A2A, 5));

    /** Mythic cosmetics: never in the weekly shop, only from a crate's rarest roll. */
    public static final java.util.Set<String> MYTHIC_COSMETICS = java.util.Set.of(
        "back_wings_crimson", "kill_rumbling", "body_coordinate", "head_founder", "slash_moon");

    private static final long[] TITLE_PRICE = {60, 120, 250, 450, 800, 2000};
    public static final String[] RARITY_NAMES = {"Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic"};
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
        for (Title t : TITLES) if (t.rarity() < 5) pool.add("title:" + t.id());
        for (Cosmetics.Def d : Cosmetics.ALL) if (!d.free() && !MYTHIC_COSMETICS.contains(d.id()) && !notSold(d)) pool.add("cosmetic:" + d.id());
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

    /** A crate: bought for Marks (a lot of them) or for Gold. */
    public record Crate(String id, String title, String desc, long marks, long gold) { }

    public static final List<Crate> CRATES = List.of(
        new Crate("supply", "Supply Crate", "Common to rare gear, blades, gas and thunder spears, Marks. A small chance of a cosmetic or title.", 1500, 25),
        new Crate("armory", "Armory Crate", "Gear only: rare, epic, and a real chance at legendary.", 9000, 110),
        new Crate("wardrobe", "Wardrobe Crate", "Cosmetics only: wings, trails, kill effects and more.", 12000, 140),
        new Crate("honors", "Honors Crate", "Titles only, from common to legendary.", 7500, 90),
        new Crate("officer", "Officer's Crate", "Rare and epic gear, cosmetics and titles, some Gold back.", 14000, 160),
        new Crate("commander", "Commander's Crate", "Epic and legendary gear, the rarest titles, cosmetics.", 30000, 350));

    /**
     * One line of a crate's table: its chance in thousandths, what it is ("gear:rare", "marks:800-2500",
     * "gold:40-130", "supplies", "cosmetic", "cosmetic_mythic", "title:0-1"), its rarity (for colour), and how
     * it reads in the preview.
     */
    public record Loot(int permille, String what, int rarity, String label, String icon) { }

    public static final Map<String, List<Loot>> TABLES = new java.util.LinkedHashMap<>();
    static {
        TABLES.put("supply", List.of(
            gear(320, 0), gear(280, 1), gear(120, 2),
            new Loot(100, "marks:800-2500", 1, "800 to 2,500 Marks", "minecraft:gold_nugget"),
            new Loot(20, "item:aot_rpg:gas_refueler:1", 3, "A Gas Refueler", "aot_rpg:gas_refueler"),
            new Loot(130, "supplies", 1, "Blades, gas, ice burst or thunder spears", "minecraft:iron_ingot"),
            new Loot(20, "cosmetic", 3, "A cosmetic you don't own", "minecraft:amethyst_shard"),
            new Loot(10, "title:0-1", 1, "A common or uncommon title", "minecraft:name_tag")));
        TABLES.put("armory", List.of(gear(500, 2), gear(380, 3), gear(115, 4), gear(5, 5)));
        TABLES.put("wardrobe", List.of(
            new Loot(995, "cosmetic", 3, "A cosmetic you don't own: wings, trails, kill effects...", "minecraft:amethyst_shard"),
            new Loot(5, "cosmetic_mythic", 5, "A Mythic cosmetic", "minecraft:nether_star")));
        TABLES.put("honors", List.of(title(400, 0), title(300, 1), title(180, 2), title(90, 3), title(25, 4), title(5, 5)));
        TABLES.put("officer", List.of(gear(280, 2), gear(120, 3),
            new Loot(240, "cosmetic", 3, "A cosmetic you don't own", "minecraft:amethyst_shard"),
            new Loot(238, "title:0-3", 3, "A title, common to epic", "minecraft:name_tag"),
            new Loot(120, "gold:40-130", 2, "40 to 130 Gold back", "minecraft:gold_ingot"),
            gear(2, 5)));
        TABLES.put("commander", List.of(gear(300, 3), gear(115, 4),
            new Loot(240, "cosmetic", 3, "A cosmetic you don't own", "minecraft:amethyst_shard"),
            new Loot(285, "title:2-4", 4, "A title, rare to legendary", "minecraft:name_tag"),
            new Loot(45, "gold:200-450", 2, "200 to 450 Gold back", "minecraft:gold_ingot"),
            gear(7, 5), title(4, 5),
            new Loot(4, "cosmetic_mythic", 5, "A Mythic cosmetic", "minecraft:nether_star")));
    }

    private static Loot gear(int permille, int rarity) {
        String r = RARITY_NAMES[rarity];
        // Icons are Danny's own gear (filled in when sent: "@blade", "@apg_gun", "@uniform").
        return new Loot(permille, "gear:" + r.toLowerCase(java.util.Locale.ROOT), rarity, r + " gear: ODM blades, APG guns, uniforms",
            rarity >= 4 ? "@apg_gun" : "@blade");
    }

    private static Loot title(int permille, int rarity) {
        return new Loot(permille, "title:" + rarity + "-" + rarity, rarity, RARITY_NAMES[rarity] + " title", "minecraft:name_tag");
    }

    /** What a crate gives: a reward spec (see Rewards), rolled on the player's own luck. */
    private String roll(ServerPlayerEntity p, String crate) {
        Random r = new Random(p.getRandom().nextLong());
        List<Loot> table = TABLES.getOrDefault(crate, TABLES.get("supply"));
        int x = r.nextInt(1000);
        Loot pick = table.get(0);
        for (Loot l : table) {
            if (x < l.permille()) {
                pick = l;
                break;
            }
            x -= l.permille();
        }
        String w = pick.what();
        if (w.equals("supplies")) return supplies(r);
        if (w.equals("cosmetic")) return "cosmetic:" + randomCosmetic(p, r, false);
        if (w.equals("cosmetic_mythic")) return "cosmetic:" + randomCosmetic(p, r, true);
        String[] kv = w.split(":", 2);
        if (kv[0].equals("title")) {
            String[] lh = kv[1].split("-");
            return "title:" + randomTitle(p, r, Integer.parseInt(lh[0]), Integer.parseInt(lh[1]));
        }
        if ((kv[0].equals("marks") || kv[0].equals("gold")) && kv[1].contains("-")) {
            String[] lh = kv[1].split("-");
            int lo = Integer.parseInt(lh[0]), hi = Integer.parseInt(lh[1]);
            return kv[0] + ":" + (lo + r.nextInt(hi - lo + 1));
        }
        return w;
    }

    private static String supplies(Random r) {
        String[][] opts = {{"blade_component", "16"}, {"gas_canister", "2"}, {"ice_burst_cluster", "32"}, {"thunder_spear", "2"}};
        for (int i = 0; i < 6; i++) {
            String[] o = opts[r.nextInt(opts.length)];
            if (AotItems.exact(o[0]) != null) return "item:" + AotItems.namespace + ":" + o[0] + ":" + o[1];
        }
        return "marks:600";
    }

    /** Cloaks are uniform, not merchandise: never in the store or a crate. */
    static boolean notSold(Cosmetics.Def d) {
        return d.id().contains("cloak");
    }

    private static String randomCosmetic(ServerPlayerEntity p, Random r, boolean mythic) {
        List<String> left = new ArrayList<>();
        var have = AotRpg.COSMETICS.unlocked(p);
        for (Cosmetics.Def d : Cosmetics.ALL) {
            if (!d.free() && !notSold(d) && !have.contains(d.id()) && MYTHIC_COSMETICS.contains(d.id()) == mythic) left.add(d.id());
        }
        return left.isEmpty() ? "" : left.get(r.nextInt(left.size()));
    }

    private static String randomTitle(ServerPlayerEntity p, Random r, int minRarity, int maxRarity) {
        List<String> left = new ArrayList<>();
        var have = AotRpg.PROFILES.get(p.getUuid()).achievements;
        for (Title t : TITLES) if (t.rarity() >= minRarity && t.rarity() <= maxRarity && !have.contains(t.id())) left.add(t.id());
        // Everything of that rarity owned: any title you don't have yet.
        if (left.isEmpty()) for (Title t : TITLES) if (!have.contains(t.id())) left.add(t.id());
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
            case "crate", "crate_gold" -> {
                Crate c = null;
                for (Crate k : CRATES) if (k.id().equals(id)) c = k;
                if (c == null) return;
                boolean gold = action.equals("crate_gold");
                long price = gold ? c.gold() : c.marks();
                boolean paid = gold ? AotRpg.WALLET.spendGold(p, price) : AotRpg.WALLET.spendMarks(p, price);
                if (!paid) {
                    Notify.toast(p, Text.literal("Not enough " + (gold ? "Gold" : "Marks")).formatted(Formatting.RED),
                        Text.literal(c.title() + " costs " + price + (gold ? " Gold" : " Marks")), 0xC0463A);
                    return;
                }
                String got = roll(p, c.id());
                // Already have everything of that kind: it comes as money instead.
                if (got.endsWith(":")) got = gold ? "gold:" + price / 3 : "marks:" + price / 2;
                String desc = Rewards.describe(got), icon = Rewards.icon(got);
                List<String> lines = new ArrayList<>();
                if (got.startsWith("gear:")) {
                    // Gear is rolled here, so the reveal can show exactly what came out: name, stats, perks.
                    Gear.Rarity rar;
                    try {
                        rar = Gear.Rarity.valueOf(got.substring(5).toUpperCase(java.util.Locale.ROOT));
                    } catch (Exception e) {
                        rar = Gear.Rarity.RARE;
                    }
                    net.minecraft.item.ItemStack s = Gear.roll(p.getRandom(), rar, Gear.dropLevel(p, AotRpg.PROFILES.get(p.getUuid()).level, 1));
                    desc = s.getName().getString();
                    icon = net.minecraft.registry.Registries.ITEM.getId(s.getItem()).toString();
                    var lore = s.get(net.minecraft.component.DataComponentTypes.LORE);
                    if (lore != null) for (Text l : lore.lines()) if (!l.getString().isBlank() && lines.size() < 9) lines.add(l.getString());
                    // Straight into your hands (or at your feet if they're full).
                    p.getInventory().offerOrDrop(s);
                } else {
                    grant(p, got);
                }
                if (ServerPlayNetworking.canSend(p, Net.CrateOpened.ID)) {
                    ServerPlayNetworking.send(p, new Net.CrateOpened(c.id(), c.title(), desc, icon, rarityOf(got), lines));
                } else {
                    Notify.toast(p, Text.literal(c.title() + ": " + Rewards.describe(got)).formatted(Formatting.GOLD),
                        Text.literal("Opened for " + price + (gold ? " Gold" : " Marks")), 0xE0B96A, Rewards.icon(got), "crate");
                }
            }
            default -> { }
        }
        AotRpg.PROFILES.save(p.getUuid());
        send(p, false);
    }

    /** "@path" -> Danny's item of that name (the item id), anything else as it is. */
    static String aotIcon(String icon) {
        if (!icon.startsWith("@")) return icon;
        var it = AotItems.exact(icon.substring(1));
        if (it == null) it = AotItems.exact("blade");
        return it == null ? "minecraft:paper" : net.minecraft.registry.Registries.ITEM.getId(it).toString();
    }

    /** How rare a roll was, 0 (common) to 4 (legendary), for the opening's colour and fanfare. */
    static int rarityOf(String spec) {
        String[] a = spec.split(":", 2);
        String v = a.length > 1 ? a[1] : "";
        return switch (a[0]) {
            case "gear" -> switch (v) {
                case "uncommon" -> 1;
                case "rare" -> 2;
                case "epic" -> 3;
                case "legendary" -> 4;
                case "mythic" -> 5;
                default -> 0;
            };
            case "title" -> {
                Title t = title(v);
                yield t == null ? 1 : t.rarity();
            }
            case "cosmetic" -> MYTHIC_COSMETICS.contains(v) ? 5 : 3;
            case "gold" -> 2;
            default -> 0;
        };
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
            String kind = t != null ? RARITY_NAMES[t.rarity()] + " title" : "Cosmetic";
            list.add(new Net.StoreOffer(o, name, kind, color, price(o), owns(p, o)));
        }
        List<Net.StoreCrate> crates = new ArrayList<>();
        for (Crate c : CRATES) {
            // The preview: every line of the table, "rarity|permille|icon|label".
            List<String> loot = new ArrayList<>();
            for (Loot l : TABLES.getOrDefault(c.id(), List.of())) loot.add(l.rarity() + "|" + l.permille() + "|" + aotIcon(l.icon()) + "|" + l.label());
            crates.add(new Net.StoreCrate(c.id(), c.title(), c.desc(), c.marks(), c.gold(), loot));
        }
        ServerPlayNetworking.send(p, new Net.StoreView(list, crates, secondsLeft(), open));
        AotRpg.WALLET.sync(p);
    }
}
