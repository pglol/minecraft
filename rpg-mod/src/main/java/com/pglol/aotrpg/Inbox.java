package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The inbox: gifts waiting for a player (from the staff, or anyone who sends one). An item, a
 * store reward (title, cosmetic, money) or a crate to open, each with who it's from and a tag.
 * Kept per account on disk until claimed, so nothing is lost to a logout or a restart.
 */
public final class Inbox {
    public static final class Gift {
        String id, kind, spec = "", crate = "", from, tag;
        ItemStack item = ItemStack.EMPTY;
        boolean rigged;
        long at;
    }

    private final Map<UUID, List<Gift>> boxes = new HashMap<>();
    private MinecraftServer server;
    private Path dir;

    public void open(MinecraftServer server) {
        this.server = server;
        boxes.clear();
        dir = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("inbox");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not create the inbox folder", e);
        }
    }

    private List<Gift> box(UUID id) {
        return boxes.computeIfAbsent(id, this::load);
    }

    private List<Gift> load(UUID id) {
        List<Gift> out = new ArrayList<>();
        Path f = dir == null ? null : dir.resolve(id + ".dat");
        if (f == null || !Files.exists(f)) return out;
        try {
            NbtCompound n = NbtIo.readCompressed(f, NbtSizeTracker.ofUnlimitedBytes());
            for (NbtElement e : n.getList("Gifts", NbtElement.COMPOUND_TYPE)) {
                NbtCompound c = (NbtCompound) e;
                Gift g = new Gift();
                g.id = c.getString("id");
                g.kind = c.getString("kind");
                g.spec = c.getString("spec");
                g.crate = c.getString("crate");
                g.from = c.getString("from");
                g.tag = c.getString("tag");
                g.rigged = c.getBoolean("rigged");
                g.at = c.getLong("at");
                if (c.contains("item")) g.item = ItemStack.fromNbt(server.getRegistryManager(), c.getCompound("item")).orElse(ItemStack.EMPTY);
                if (g.kind.equals("item") && g.item.isEmpty()) continue;
                out.add(g);
            }
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read inbox {}", id, e);
        }
        return out;
    }

    private void save(UUID id) {
        List<Gift> list = boxes.get(id);
        if (list == null || dir == null) return;
        try {
            NbtList gifts = new NbtList();
            for (Gift g : list) {
                NbtCompound c = new NbtCompound();
                c.putString("id", g.id);
                c.putString("kind", g.kind);
                c.putString("spec", g.spec);
                c.putString("crate", g.crate);
                c.putString("from", g.from);
                c.putString("tag", g.tag);
                c.putBoolean("rigged", g.rigged);
                c.putLong("at", g.at);
                if (!g.item.isEmpty()) c.put("item", g.item.encode(server.getRegistryManager()));
                gifts.add(c);
            }
            NbtCompound n = new NbtCompound();
            n.put("Gifts", gifts);
            NbtIo.writeCompressed(n, dir.resolve(id + ".dat"));
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save inbox {}", id, e);
        }
    }

    // ------------------------------------------------------------------ sending

    private Gift gift(String kind, String from, String tag) {
        Gift g = new Gift();
        g.id = Long.toString(System.nanoTime(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
        g.kind = kind;
        g.from = from == null || from.isBlank() ? "The Survey Corps" : from;
        g.tag = tag == null ? "" : tag.strip();
        g.at = System.currentTimeMillis();
        return g;
    }

    public void sendItem(UUID to, ItemStack s, String from, String tag) {
        if (s.isEmpty()) return;
        Gift g = gift("item", from, tag);
        g.item = s.copy();
        deliver(to, g);
    }

    /** A store reward: "title:id", "cosmetic:id", "marks:n", "gold:n". */
    public void sendReward(UUID to, String spec, String from, String tag) {
        Gift g = gift("reward", from, tag);
        g.spec = spec;
        deliver(to, g);
    }

    public void sendCrate(UUID to, String crate, boolean rigged, String from, String tag) {
        if (Store.crate(crate) == null) return;
        Gift g = gift("crate", from, tag);
        g.crate = crate;
        g.rigged = rigged;
        deliver(to, g);
    }

    private void deliver(UUID to, Gift g) {
        box(to).add(g);
        save(to);
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(to);
        if (p != null) {
            Notify.toast(p, Text.literal("A gift from " + g.from).formatted(Formatting.GOLD),
                Text.literal(g.tag.isEmpty() ? "Waiting in your Inbox" : g.tag), 0xE0B96A, icon(g), "gift");
            p.playSoundToPlayer(net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP, net.minecraft.sound.SoundCategory.MASTER, 0.6f, 1.6f);
            send(p, false);
        }
    }

    /** On join: a nudge if anything is waiting. */
    public void joined(ServerPlayerEntity p) {
        int n = box(p.getUuid()).size();
        if (n > 0) Notify.toast(p, Text.literal(n == 1 ? "A gift is waiting" : n + " gifts are waiting").formatted(Formatting.GOLD),
            Text.literal("Open your Inbox from the menu"), 0xE0B96A, "minecraft:chest", "gift");
        send(p, false);
    }

    public void forget(UUID id) {
        save(id);
        boxes.remove(id);
    }

    public boolean pending(UUID id) {
        return !box(id).isEmpty();
    }

    // ------------------------------------------------------------------ claiming

    public void action(ServerPlayerEntity p, String action, String id) {
        List<Gift> box = box(p.getUuid());
        switch (action) {
            case "open" -> {
                send(p, true);
                return;
            }
            case "claim" -> {
                for (Gift g : new ArrayList<>(box)) if (g.id.equals(id)) claim(p, box, g);
            }
            case "claim_all" -> {
                // Items and rewards all at once; crates are opened one at a time, for the reveal.
                for (Gift g : new ArrayList<>(box)) if (!g.kind.equals("crate")) claim(p, box, g);
            }
            default -> { }
        }
        save(p.getUuid());
        send(p, false);
    }

    private void claim(ServerPlayerEntity p, List<Gift> box, Gift g) {
        if (!AotRpg.PROFILES.get(p.getUuid()).created) return;
        box.remove(g);
        switch (g.kind) {
            case "item" -> p.getInventory().offerOrDrop(g.item.copy());
            case "reward" -> {
                Store.grant(p, g.spec);
                Notify.toast(p, Text.literal(Rewards.describe(g.spec)).formatted(Formatting.GOLD), Text.literal("From " + g.from), 0xE0B96A, Rewards.icon(g.spec), "gift");
            }
            case "crate" -> {
                Store.Crate c = Store.crate(g.crate);
                if (c != null) AotRpg.STORE.openCrate(p, c, g.rigged, "A gift from " + g.from, "marks:1500");
            }
            default -> { }
        }
        AotRpg.PROFILES.save(p.getUuid());
    }

    private static String icon(Gift g) {
        return switch (g.kind) {
            case "item" -> Registries.ITEM.getId(g.item.getItem()).toString();
            case "crate" -> g.rigged ? "minecraft:ender_chest" : "minecraft:chest";
            default -> Rewards.icon(g.spec);
        };
    }

    public void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.InboxView.ID)) return;
        List<String> gifts = new ArrayList<>(), loot = new ArrayList<>();
        java.util.Set<String> tables = new java.util.HashSet<>();
        for (Gift g : box(p.getUuid())) {
            String title;
            int color;
            switch (g.kind) {
                case "item" -> {
                    title = g.item.getName().getString() + (g.item.getCount() > 1 ? " x" + g.item.getCount() : "");
                    color = Gear.isGear(g.item) ? Store.RARITY_COLORS[Gear.rarityOf(g.item)] : 0xEDE3C8;
                }
                case "crate" -> {
                    Store.Crate c = Store.crate(g.crate);
                    title = (c == null ? g.crate : c.title()) + (g.rigged ? " ★" : "");
                    color = g.rigged ? 0xFF3A3A : 0xE0B96A;
                    if (tables.add(g.crate)) for (Store.Loot l : Store.TABLES.getOrDefault(g.crate, List.of())) {
                        loot.add(g.crate + "\u0001" + l.rarity() + "|" + l.permille() + "|" + Store.aotIcon(l.icon()) + "|" + l.label());
                    }
                }
                default -> {
                    title = Rewards.describe(g.spec);
                    color = Store.RARITY_COLORS[Math.max(0, Math.min(5, Store.rarityOf(g.spec)))];
                }
            }
            gifts.add(String.join("|", g.id, g.kind, clean(title), icon(g), Integer.toString(color), clean(g.from), clean(g.tag),
                g.rigged ? "1" : "0", g.kind.equals("crate") ? g.crate : ""));
        }
        ServerPlayNetworking.send(p, new Net.InboxView(gifts, loot, open));
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('|', '/');
    }
}
