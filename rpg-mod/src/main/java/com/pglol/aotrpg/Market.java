package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Town markets with a living economy. Every town trades a shared catalog, but each has its own
 * stock and prices: buying drains stock and pushes the price up, selling floods it and pulls the
 * price down, and stock drifts back over time. Regions produce different things (farm villages
 * sell food cheap, the port fish, Shiganshina ODM supplies...), so traders profit by buying
 * where it's plentiful and selling where it's scarce. The faction that controls a sector gives
 * its members better prices there.
 */
public final class Market {
    public enum Category { FOOD, INGREDIENT, FISH, MATERIAL, SUPPLY, TOOL, LUXURY, GEAR }

    public static final class Good {
        public String item;
        public String category;
        public double price;
        public int target = 64;
    }

    public static final class Config {
        public List<Good> goods = new ArrayList<>();
        /** Area-name keyword -> category -> price factor (below 1 = produced there, cheap). */
        public Map<String, Map<String, Double>> regions = new LinkedHashMap<>();
        public double spread = 0.55;
        public double drift = 0.03;
        public int radius = 160;
        public double memberDiscount = 0.10;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public Config config = new Config();
    /** town id -> item -> stock */
    private Map<String, Map<String, Double>> stock = new HashMap<>();
    private Path dir;

    public void open(MinecraftServer server) {
        dir = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg");
        try {
            Path f = dir.resolve("market.json");
            if (Files.exists(f)) config = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Config.class);
            if (config == null || config.goods == null || config.goods.isEmpty()) {
                config = defaults();
                Files.createDirectories(dir);
                Files.writeString(f, GSON.toJson(config), StandardCharsets.UTF_8);
            }
            Path s = dir.resolve("market-stock.json");
            if (Files.exists(s)) {
                stock = GSON.fromJson(Files.readString(s, StandardCharsets.UTF_8), new TypeToken<Map<String, Map<String, Double>>>() { }.getType());
            }
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read market files", e);
        }
        if (stock == null) stock = new HashMap<>();
    }

    public void save() {
        if (dir == null) return;
        try {
            Files.writeString(dir.resolve("market-stock.json"), GSON.toJson(stock), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save market stock", e);
        }
    }

    private static Good good(String item, Category c, double price) {
        Good g = new Good();
        g.item = item;
        g.category = c.name();
        g.price = price;
        return g;
    }

    private static Config defaults() {
        Config c = new Config();
        Object[][] g = {
            {"minecraft:bread", Category.FOOD, 6}, {"minecraft:cooked_beef", Category.FOOD, 10}, {"minecraft:cooked_porkchop", Category.FOOD, 10},
            {"minecraft:baked_potato", Category.FOOD, 5}, {"minecraft:apple", Category.FOOD, 4}, {"minecraft:pumpkin_pie", Category.FOOD, 12},
            {"minecraft:honey_bottle", Category.FOOD, 9}, {"minecraft:wheat", Category.INGREDIENT, 2}, {"minecraft:potato", Category.INGREDIENT, 2},
            {"minecraft:carrot", Category.INGREDIENT, 2}, {"minecraft:beef", Category.INGREDIENT, 5}, {"minecraft:porkchop", Category.INGREDIENT, 5},
            {"minecraft:chicken", Category.INGREDIENT, 4}, {"minecraft:mutton", Category.INGREDIENT, 5}, {"minecraft:egg", Category.INGREDIENT, 2},
            {"minecraft:sugar", Category.INGREDIENT, 2}, {"minecraft:brown_mushroom", Category.INGREDIENT, 3}, {"minecraft:cod", Category.FISH, 4},
            {"minecraft:salmon", Category.FISH, 6}, {"minecraft:tropical_fish", Category.FISH, 9}, {"minecraft:iron_ingot", Category.MATERIAL, 12},
            {"minecraft:gold_ingot", Category.MATERIAL, 30}, {"minecraft:leather", Category.MATERIAL, 6}, {"minecraft:string", Category.MATERIAL, 2},
            {"minecraft:coal", Category.MATERIAL, 3}, {"minecraft:oak_log", Category.MATERIAL, 2}, {"dannys-aot:ultrahard_steel_ingot", Category.MATERIAL, 60},
            {"dannys-aot:ultrahard_leather", Category.MATERIAL, 45}, {"dannys-aot:blade_component", Category.SUPPLY, 8},
            {"dannys-aot:gas_canister", Category.SUPPLY, 20}, {"dannys-aot:ice_burst_cluster", Category.SUPPLY, 3},
            {"dannys-aot:apg_cartridge", Category.SUPPLY, 4}, {"minecraft:iron_pickaxe", Category.TOOL, 40}, {"minecraft:fishing_rod", Category.TOOL, 15},
            {"minecraft:shears", Category.TOOL, 12}, {"minecraft:saddle", Category.TOOL, 60}, {"minecraft:lead", Category.TOOL, 10},
            {"dannys-aot:vintage_wine", Category.LUXURY, 25}, {"minecraft:book", Category.LUXURY, 10}, {"minecraft:amethyst_shard", Category.LUXURY, 14}};
        for (Object[] o : g) c.goods.add(good((String) o[0], (Category) o[1], ((Number) o[2]).doubleValue()));
        c.regions.put("ragako", Map.of("FOOD", 0.75, "INGREDIENT", 0.7, "TOOL", 1.25, "LUXURY", 1.3));
        c.regions.put("village", Map.of("FOOD", 0.8, "INGREDIENT", 0.75, "MATERIAL", 1.15));
        c.regions.put("port", Map.of("FISH", 0.6, "SUPPLY", 1.2, "FOOD", 0.95));
        c.regions.put("trost", Map.of("TOOL", 0.85, "MATERIAL", 0.9, "FISH", 1.2));
        c.regions.put("karanes", Map.of("MATERIAL", 0.85, "FOOD", 1.1));
        c.regions.put("shiganshina", Map.of("SUPPLY", 0.85, "LUXURY", 1.2));
        c.regions.put("stohess", Map.of("LUXURY", 0.8, "FOOD", 1.2, "SUPPLY", 1.15));
        c.regions.put("mitras", Map.of("LUXURY", 0.75, "FOOD", 1.35, "INGREDIENT", 1.3));
        c.regions.put("underground", Map.of("LUXURY", 1.4, "FOOD", 1.5, "MATERIAL", 0.8));
        c.regions.put("training", Map.of("SUPPLY", 0.8, "TOOL", 0.95));
        c.regions.put("liberio", Map.of("SUPPLY", 0.7, "MATERIAL", 0.8, "FOOD", 1.25));
        c.regions.put("marley", Map.of("SUPPLY", 0.75, "MATERIAL", 0.85, "FISH", 0.9));
        return c;
    }

    // ------------------------------------------------------------------ pricing

    /** The town market the player is standing in, or null. */
    public Net.Area town(ServerPlayerEntity p) {
        return AotRpg.PLACES.nearest(p.getX(), p.getZ(), config.radius, "town", "safe", "marley", "camp");
    }

    private double regionFactor(Net.Area town, Good g) {
        String name = (town.name() + " " + town.id()).toLowerCase(java.util.Locale.ROOT);
        double f = 1;
        for (var e : config.regions.entrySet()) {
            if (name.contains(e.getKey())) {
                Double v = e.getValue().get(g.category);
                if (v != null) f *= v;
            }
        }
        // Every town also has its own small quirks, stable over time.
        long h = (town.id() + "/" + g.item).hashCode();
        f *= 0.92 + (Math.floorMod(h, 1000) / 1000.0) * 0.16;
        return f;
    }

    private double stock(Net.Area town, Good g) {
        return stock.computeIfAbsent(town.id(), k -> new HashMap<>()).getOrDefault(g.item, (double) g.target);
    }

    private void setStock(Net.Area town, Good g, double v) {
        stock.computeIfAbsent(town.id(), k -> new HashMap<>()).put(g.item, Math.max(0, Math.min(g.target * 3.0, v)));
    }

    /** Unit price to buy right now (before faction discount). */
    public double buyPrice(Net.Area town, Good g) {
        double s = stock(town, g);
        double scarcity = Math.max(0.5, Math.min(2.4, 1 + 0.8 * (g.target - s) / g.target));
        return Math.max(1, g.price * regionFactor(town, g) * scarcity);
    }

    public double sellPrice(Net.Area town, Good g) {
        return Math.max(0.5, buyPrice(town, g) * config.spread);
    }

    private double discount(ServerPlayerEntity p, Net.Area town) {
        Sector s = Sector.at(town.x(), town.z());
        double d = AotRpg.FACTIONS.controls(p, s) ? config.memberDiscount : 0;
        // Charisma: 2% better prices per point.
        d += 0.02 * AotRpg.PROFILES.get(p.getUuid()).total(Stat.CHARISMA);
        return Math.min(0.4, d);
    }

    public Good find(String item) {
        for (Good g : config.goods) if (g.item.equals(item)) return g;
        return null;
    }

    private static Item item(Good g) {
        Identifier id = Identifier.tryParse(g.item);
        if (id == null || !Registries.ITEM.containsId(id)) return null;
        return Registries.ITEM.get(id);
    }

    // ------------------------------------------------------------------ trading

    public void buy(ServerPlayerEntity p, String itemId, int qty) {
        Net.Area town = town(p);
        Good g = find(itemId);
        if (town == null || g == null || qty <= 0) return;
        Item it = item(g);
        if (it == null) return;
        qty = Math.min(qty, it.getMaxCount());
        double s = stock(town, g);
        if (s < qty) {
            p.sendMessage(Text.literal("They only have " + (int) s + " in stock.").formatted(Formatting.RED), true);
            return;
        }
        double disc = discount(p, town);
        long cost = 0;
        for (int i = 0; i < qty; i++) {
            cost += Math.round(buyPrice(town, g) * (1 - disc));
            setStock(town, g, stock(town, g) - 1);
        }
        if (!AotRpg.WALLET.spendMarks(p, cost)) {
            setStock(town, g, s); // undo
            p.sendMessage(Text.literal("Not enough Marks (" + cost + " needed).").formatted(Formatting.RED), true);
            return;
        }
        ItemStack st = new ItemStack(it, qty);
        Provisions.convert(st);
        p.getInventory().offerOrDrop(st);
        AotRpg.FACTIONS.taxed(Sector.at(town.x(), town.z()), cost);
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.PLAYERS, 0.5f, 1.2f);
        send(p);
    }

    public void sell(ServerPlayerEntity p, String itemId, int qty) {
        Net.Area town = town(p);
        Good g = find(itemId);
        if (town == null || g == null || qty <= 0) return;
        Item it = item(g);
        if (it == null) return;
        int have = count(p, it);
        qty = Math.min(qty, have);
        if (qty <= 0) return;
        double disc = discount(p, town);
        long pay = 0;
        for (int i = 0; i < qty; i++) {
            pay += Math.round(sellPrice(town, g) * (1 + disc));
            setStock(town, g, stock(town, g) + 1);
        }
        remove(p, it, qty);
        AotRpg.WALLET.addMarks(p, pay, null);
        AotRpg.FACTIONS.taxed(Sector.at(town.x(), town.z()), pay);
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.5f, 1.3f);
        send(p);
    }

    /** Gear sells to any market for its scrap value (rarity and item level). */
    public void sellGear(ServerPlayerEntity p, int slot) {
        Net.Area town = town(p);
        if (town == null) return;
        ItemStack s = AotRpg.SATCHEL.at(p, slot);
        if (!Gear.isGear(s)) return;
        long value = gearValue(s);
        AotRpg.SATCHEL.set(p, slot, ItemStack.EMPTY);
        AotRpg.WALLET.addMarks(p, value, "sold " + s.getName().getString());
        send(p);
    }

    public static long gearValue(ItemStack s) {
        var d = Gear.data(s);
        int r = 0;
        try {
            r = Gear.Rarity.valueOf(d.getString("rarity")).ordinal();
        } catch (Exception ignored) { }
        return Math.round((6 + d.getInt("ilvl") * 1.5) * Math.pow(2.2, r) * (1 + 0.15 * d.getInt("up")));
    }

    private static int count(ServerPlayerEntity p, Item it) {
        int n = 0;
        for (ItemStack s : p.getInventory().main) if (s.isOf(it) && !Gear.isGear(s) && !Satchel.isStory(s)) n += s.getCount();
        for (ItemStack s : AotRpg.SATCHEL.get(p.getUuid()).getHeldStacks()) if (s.isOf(it) && !Satchel.isStory(s)) n += s.getCount();
        return n;
    }

    private static void remove(ServerPlayerEntity p, Item it, int qty) {
        List<ItemStack> all = new ArrayList<>(AotRpg.SATCHEL.get(p.getUuid()).getHeldStacks());
        all.addAll(p.getInventory().main);
        for (ItemStack s : all) {
            if (qty <= 0) break;
            if (!s.isOf(it) || Gear.isGear(s) || Satchel.isStory(s)) continue;
            int k = Math.min(qty, s.getCount());
            s.decrement(k);
            qty -= k;
        }
        AotRpg.SATCHEL.get(p.getUuid()).markDirty();
        p.getInventory().markDirty();
    }

    /** Once a minute: stock drifts back towards normal everywhere. */
    public void tick(int ticks) {
        if (ticks % 1200 != 0) return;
        for (Map<String, Double> m : stock.values()) {
            for (Good g : config.goods) {
                Double v = m.get(g.item);
                if (v == null) continue;
                m.put(g.item, v + (g.target - v) * config.drift);
            }
        }
        if (ticks % 12000 == 0) save();
    }

    // ------------------------------------------------------------------ view

    public void open(ServerPlayerEntity p) {
        if (town(p) == null) {
            p.sendMessage(Text.literal("There is no market here. Visit a town, camp or port.").formatted(Formatting.GRAY), true);
            return;
        }
        AotRpg.EXCHANGE.deliver(p);
        send(p, true);
    }

    public void send(ServerPlayerEntity p) {
        send(p, false);
    }

    private void send(ServerPlayerEntity p, boolean open) {
        Net.Area town = town(p);
        if (town == null || !ServerPlayNetworking.canSend(p, Net.MarketView.ID)) return;
        AotRpg.SATCHEL.send(p, false);
        double disc = discount(p, town);
        List<Net.MarketGood> list = new ArrayList<>();
        for (Good g : config.goods) {
            Item it = item(g);
            if (it == null) continue;
            double base = g.price;
            double buy = buyPrice(town, g) * (1 - disc), sell = sellPrice(town, g) * (1 + disc);
            list.add(new Net.MarketGood(g.item, g.category, Math.round(buy), Math.round(sell), (int) stock(town, g),
                (int) Math.round((buy / base - 1) * 100), count(p, it)));
        }
        List<Net.GearOffer> gear = new ArrayList<>();
        for (int i : AotRpg.SATCHEL.addresses(p)) {
            ItemStack s = AotRpg.SATCHEL.at(p, i);
            if (Gear.isGear(s)) gear.add(new Net.GearOffer(i, gearValue(s)));
        }
        Sector sec = Sector.at(town.x(), town.z());
        ServerPlayNetworking.send(p, new Net.MarketView(town.name(), sec.title, AotRpg.FACTIONS.controllerName(sec),
            (int) Math.round(disc * 100), list, gear, open));
    }
}
