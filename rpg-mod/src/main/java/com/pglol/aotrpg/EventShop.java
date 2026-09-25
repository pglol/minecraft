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
 * The special event shop. While an event runs, titans and quests drop event tokens, spent here
 * on limited items (some also sold for Gold). All in <world>/aot_rpg/event.json; change the id
 * for a new event (tokens and purchase limits reset).
 */
public final class EventShop {
    public static final class Item {
        public String key = "";
        public String title = "";
        public String desc = "";
        /** A reward spec (see Rewards). */
        public String reward = "";
        public long tokens;
        public long gold;
        /** Purchases per account; 0 = unlimited. */
        public int limit;

        Item(String key, String title, String desc, String reward, long tokens, long gold, int limit) {
            this.key = key;
            this.title = title;
            this.desc = desc;
            this.reward = reward;
            this.tokens = tokens;
            this.gold = gold;
            this.limit = limit;
        }
    }

    public static final class Data {
        public boolean active = true;
        public String id = "founders";
        public String name = "Founders' Festival";
        public String token = "Festival Token";
        public long endsAt;
        /** Chance a titan kill drops a token, and tokens per completed quest. */
        public double tokenPerTitan = 0.35;
        public int tokensPerQuest = 3;
        public List<Item> items = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private Data data = defaults();
    private Path file;

    private static Data defaults() {
        Data d = new Data();
        d.items.add(new Item("hearts", "Hearts Trail", "Festival exclusive trail", "cosmetic:trail_hearts", 40, 0, 1));
        d.items.add(new Item("rainbow", "Rainbow Trail", "Festival exclusive trail", "cosmetic:trail_rainbow", 90, 450, 1));
        d.items.add(new Item("cache", "Festival Cache", "An epic piece of gear", "gear:epic", 120, 0, 2));
        d.items.add(new Item("purse", "Festival Purse", "500 Marks", "marks:500", 25, 0, 5));
        d.items.add(new Item("apples", "Medic's Crate", "Three golden apples", "item:minecraft:golden_apple:3", 15, 0, 0));
        d.items.add(new Item("relic", "Founder's Relic", "A legendary piece of gear", "gear:legendary", 300, 1200, 1));
        return d;
    }

    public void open(MinecraftServer server) {
        file = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("event.json");
        reload();
    }

    public void reload() {
        try {
            if (Files.exists(file)) {
                Data read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (read != null) data = read;
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read event.json", e);
        }
        if (data.items == null) data.items = new ArrayList<>();
        if (data.id == null) data.id = "event";
        if (data.token == null) data.token = "Event Token";
    }

    public boolean running() {
        return data.active && (data.endsAt <= 0 || System.currentTimeMillis() < data.endsAt);
    }

    public String tokenName() {
        return data.token + "s";
    }

    private ProfileStore.Account account(ServerPlayerEntity p) {
        ProfileStore.Account a = AotRpg.PROFILES.account(p.getUuid());
        if (!data.id.equals(a.eventId)) {
            a.eventId = data.id;
            a.eventTokens = 0;
            a.eventBought.clear();
            AotRpg.PROFILES.saveAccount(p.getUuid());
        }
        return a;
    }

    public void addTokens(ServerPlayerEntity p, long n) {
        ProfileStore.Account a = account(p);
        a.eventTokens = Math.max(0, a.eventTokens + n);
        AotRpg.PROFILES.saveAccount(p.getUuid());
        if (n > 0) p.sendMessage(Text.literal("+" + n + " " + (n == 1 ? data.token : tokenName())).formatted(Formatting.LIGHT_PURPLE), true);
    }

    public void onTitanKill(ServerPlayerEntity p) {
        if (running() && p.getRandom().nextDouble() < data.tokenPerTitan) addTokens(p, 1);
    }

    public void onQuest(ServerPlayerEntity p) {
        if (running() && data.tokensPerQuest > 0) addTokens(p, data.tokensPerQuest);
    }

    public void action(ServerPlayerEntity p, String action, String key) {
        if (action.startsWith("buy")) buy(p, key, action.equals("buy_gold"));
        send(p, action.equals("open"));
    }

    private void buy(ServerPlayerEntity p, String key, boolean withGold) {
        if (!running()) {
            p.sendMessage(Text.literal("The event shop is closed.").formatted(Formatting.RED), true);
            return;
        }
        Item it = null;
        for (Item i : data.items) if (i.key.equals(key)) it = i;
        if (it == null) return;
        ProfileStore.Account a = account(p);
        int bought = a.eventBought.getOrDefault(key, 0);
        if (it.limit > 0 && bought >= it.limit) {
            p.sendMessage(Text.literal("Sold out for you.").formatted(Formatting.RED), true);
            return;
        }
        if (withGold) {
            if (it.gold <= 0 || !AotRpg.WALLET.spendGold(p, it.gold)) {
                p.sendMessage(Text.literal("Not enough Gold.").formatted(Formatting.RED), true);
                return;
            }
        } else {
            if (it.tokens <= 0 || a.eventTokens < it.tokens) {
                p.sendMessage(Text.literal("Not enough " + tokenName() + ".").formatted(Formatting.RED), true);
                return;
            }
            a.eventTokens -= it.tokens;
        }
        a.eventBought.put(key, bought + 1);
        AotRpg.PROFILES.saveAccount(p.getUuid());
        Rewards.give(p, it.reward, data.name);
        p.sendMessage(Text.literal("Bought " + it.title).formatted(Formatting.LIGHT_PURPLE), true);
        p.playSoundToPlayer(SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.MASTER, 0.6f, 1.2f);
    }

    public void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.EventView.ID)) return;
        ProfileStore.Account a = account(p);
        List<Net.EventItem> list = new ArrayList<>();
        for (Item i : data.items) {
            list.add(new Net.EventItem(i.key, i.title, i.desc.isEmpty() ? Rewards.describe(i.reward) : i.desc, Rewards.icon(i.reward),
                Rewards.color(i.reward), i.tokens, i.gold, i.limit, a.eventBought.getOrDefault(i.key, 0)));
        }
        ServerPlayNetworking.send(p, new Net.EventView(running(), data.name, data.token, data.endsAt, a.eventTokens, list, open));
    }
}
