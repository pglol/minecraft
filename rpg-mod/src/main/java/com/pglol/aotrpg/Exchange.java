package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
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
import java.util.List;
import java.util.Map;

/**
 * The Exchange: players list items for Marks at any town market and buy others' listings from
 * any market. Proceeds (minus a 5% fee) and expired or cancelled items go to the seller's
 * character mailbox, delivered the next time that character visits a market.
 */
public final class Exchange {
    public static final double FEE = 0.05;
    public static final int MAX_LISTINGS = 10;
    public static final long EXPIRE_MS = 72L * 3600 * 1000;

    public static final class Listing {
        public long id;
        public String seller;
        public String stem;
        public String sellerName;
        public String item;
        public long price;
        public long created;
    }

    public static final class Mail {
        public long marks;
        public String item;
        public String note;
    }

    private static final class Data {
        long nextId = 1;
        List<Listing> listings = new ArrayList<>();
        Map<String, List<Mail>> mail = new HashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private Data data = new Data();
    private Path file;
    private MinecraftServer server;

    public void open(MinecraftServer server) {
        this.server = server;
        file = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("exchange.json");
        try {
            if (Files.exists(file)) data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read exchange.json", e);
        }
        if (data == null) data = new Data();
        if (data.listings == null) data.listings = new ArrayList<>();
        if (data.mail == null) data.mail = new HashMap<>();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save exchange.json", e);
        }
    }

    private String encode(ItemStack s) {
        return s.encode(server.getRegistryManager()).toString();
    }

    private ItemStack decode(String snbt) {
        try {
            NbtCompound n = StringNbtReader.parse(snbt);
            return ItemStack.fromNbt(server.getRegistryManager(), n).orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            AotRpg.LOG.error("Bad exchange item {}", snbt, e);
            return ItemStack.EMPTY;
        }
    }

    private String stem(ServerPlayerEntity p) {
        return AotRpg.PROFILES.activeStem(p.getUuid());
    }

    private void mail(String stem, long marks, ItemStack item, String note) {
        Mail m = new Mail();
        m.marks = marks;
        m.item = item == null || item.isEmpty() ? null : encode(item);
        m.note = note;
        data.mail.computeIfAbsent(stem, k -> new ArrayList<>()).add(m);
    }

    /** Hands over this character's mail (Marks and items). */
    public void deliver(ServerPlayerEntity p) {
        List<Mail> box = data.mail.remove(stem(p));
        if (box == null || box.isEmpty()) return;
        long marks = 0;
        for (Mail m : box) {
            marks += m.marks;
            if (m.item != null) {
                ItemStack s = decode(m.item);
                if (!s.isEmpty()) p.getInventory().offerOrDrop(s);
            }
            if (m.note != null) p.sendMessage(Text.literal("Exchange: " + m.note).formatted(Formatting.GRAY), false);
        }
        if (marks > 0) AotRpg.WALLET.addMarks(p, marks, "Exchange sales");
        save();
    }

    public void list(ServerPlayerEntity p, int slot, long price) {
        if (AotRpg.MARKET.town(p) == null || price <= 0 || price > 10_000_000) return;
        if (slot < 0 || slot >= p.getInventory().main.size()) return;
        ItemStack s = p.getInventory().main.get(slot);
        if (s.isEmpty() || Satchel.isStory(s)) return;
        String st = stem(p);
        long mine = data.listings.stream().filter(l -> l.stem.equals(st)).count();
        if (mine >= MAX_LISTINGS) {
            p.sendMessage(Text.literal("You can have " + MAX_LISTINGS + " listings at once.").formatted(Formatting.RED), true);
            return;
        }
        Listing l = new Listing();
        l.id = data.nextId++;
        l.seller = p.getUuidAsString();
        l.stem = st;
        l.sellerName = AotRpg.PROFILES.get(p.getUuid()).name;
        l.item = encode(s);
        l.price = price;
        l.created = System.currentTimeMillis();
        data.listings.add(l);
        p.getInventory().main.set(slot, ItemStack.EMPTY);
        save();
        p.sendMessage(Text.literal("Listed ").formatted(Formatting.GRAY).append(s.getName().copy())
            .append(Text.literal(" for ").formatted(Formatting.GRAY)).append(Wallet.marks(price)), true);
        send(p);
    }

    public void buy(ServerPlayerEntity p, long id) {
        if (AotRpg.MARKET.town(p) == null) return;
        Listing l = byId(id);
        if (l == null) return;
        if (l.stem.equals(stem(p))) {
            cancel(p, id);
            return;
        }
        if (!AotRpg.WALLET.spendMarks(p, l.price)) {
            p.sendMessage(Text.literal("Not enough Marks.").formatted(Formatting.RED), true);
            return;
        }
        data.listings.remove(l);
        ItemStack s = decode(l.item);
        p.getInventory().offerOrDrop(s);
        long net = Math.round(l.price * (1 - FEE));
        mail(l.stem, net, null, "sold " + s.getCount() + "x " + s.getName().getString() + " for " + net + " Marks");
        save();
        // If the seller is online on that character, pay them now.
        ServerPlayerEntity seller = server.getPlayerManager().getPlayer(java.util.UUID.fromString(l.seller));
        if (seller != null && stem(seller).equals(l.stem)) deliver(seller);
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.PLAYERS, 0.5f, 1.2f);
        send(p);
    }

    public void cancel(ServerPlayerEntity p, long id) {
        Listing l = byId(id);
        if (l == null || !l.stem.equals(stem(p))) return;
        data.listings.remove(l);
        p.getInventory().offerOrDrop(decode(l.item));
        save();
        send(p);
    }

    private Listing byId(long id) {
        for (Listing l : data.listings) if (l.id == id) return l;
        return null;
    }

    /** Every few minutes: expired listings go back to their sellers' mail. */
    public void tick(int ticks) {
        if (ticks % 6000 != 0) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Listing l : new ArrayList<>(data.listings)) {
            if (now - l.created < EXPIRE_MS) continue;
            data.listings.remove(l);
            mail(l.stem, 0, decode(l.item), "a listing expired and came back");
            changed = true;
        }
        if (changed) save();
    }

    public void send(ServerPlayerEntity p) {
        if (!ServerPlayNetworking.canSend(p, Net.ExchangeView.ID)) return;
        String st = stem(p);
        List<Net.ExchangeEntry> out = new ArrayList<>();
        List<Listing> sorted = new ArrayList<>(data.listings);
        sorted.sort((a, b) -> Long.compare(b.created, a.created));
        for (Listing l : sorted) {
            if (out.size() >= 120) break;
            out.add(new Net.ExchangeEntry(l.id, decode(l.item), l.price, l.sellerName, l.stem.equals(st)));
        }
        ServerPlayNetworking.send(p, new Net.ExchangeView(out));
    }
}
