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
        /** Buy-now price (0: auction only). */
        public long price;
        public long created;
        /** Auction: starting bid (0: buy-now only), the highest bid and who holds it, and when it ends. */
        public long startBid, bid, ends;
        public int bids;
        public String bidder, bidderStem, bidderName;

        long endsAt() {
            return ends > 0 ? ends : created + EXPIRE_MS;
        }
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
                if (!s.isEmpty()) AotRpg.SATCHEL.add(p, s);
            }
            if (m.note != null) p.sendMessage(Text.literal("Exchange: " + m.note).formatted(Formatting.GRAY), false);
        }
        if (marks > 0) AotRpg.WALLET.addMarks(p, marks, "Exchange sales");
        save();
    }

    public void list(ServerPlayerEntity p, int slot, long price) {
        list(p, slot, price, 0, 24);
    }

    /** Lists an item with a buy-now price, a starting bid, or both (the auction runs for the given hours). */
    public void list(ServerPlayerEntity p, int slot, long price, long startBid, int hours) {
        price = Math.max(0, price);
        startBid = Math.max(0, startBid);
        if (price <= 0 && startBid <= 0 || price > 10_000_000 || startBid > 10_000_000) return;
        if (price > 0 && startBid >= price) {
            p.sendMessage(Text.literal("The starting bid must be below the buy-now price.").formatted(Formatting.RED), true);
            return;
        }
        hours = hours <= 12 ? 12 : hours <= 24 ? 24 : 48;
        ItemStack s = AotRpg.SATCHEL.at(p, slot);
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
        l.startBid = startBid;
        l.ends = startBid > 0 ? l.created + hours * 3600_000L : l.created + EXPIRE_MS;
        data.listings.add(l);
        AotRpg.SATCHEL.set(p, slot, ItemStack.EMPTY);
        save();
        p.sendMessage(Text.literal("Listed ").formatted(Formatting.GRAY).append(s.getName().copy())
            .append(Text.literal(startBid > 0 ? " for auction from " : " for ").formatted(Formatting.GRAY))
            .append(Wallet.marks(startBid > 0 ? startBid : price)), true);
        send(p);
    }

    public void buy(ServerPlayerEntity p, long id) {
        Listing l = byId(id);
        if (l == null) return;
        if (l.stem.equals(stem(p))) {
            cancel(p, id);
            return;
        }
        if (l.price <= 0) return;
        if (!AotRpg.WALLET.spendMarks(p, l.price)) {
            p.sendMessage(Text.literal("Not enough Marks.").formatted(Formatting.RED), true);
            return;
        }
        data.listings.remove(l);
        refund(l, "it was bought outright");
        ItemStack s = decode(l.item);
        AotRpg.SATCHEL.add(p, s.copy());
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
        if (l.bids > 0) {
            p.sendMessage(Text.literal("Someone has bid on it: it can't be taken back now.").formatted(Formatting.RED), true);
            return;
        }
        data.listings.remove(l);
        AotRpg.SATCHEL.add(p, decode(l.item));
        save();
        send(p);
    }

    /** A bid: at least the starting bid, then 5% over the last. The Marks are held until you are outbid or win. */
    public void bid(ServerPlayerEntity p, long id, long amount) {
        Listing l = byId(id);
        long now = System.currentTimeMillis();
        if (l == null || l.stem.equals(stem(p)) || now >= l.endsAt()) return;
        if (l.startBid <= 0) {
            p.sendMessage(Text.literal("That one is buy-now only.").formatted(Formatting.RED), true);
            return;
        }
        if (l.price > 0 && amount >= l.price) {
            buy(p, id);
            return;
        }
        long min = minBid(l);
        if (amount < min) {
            p.sendMessage(Text.literal("The lowest bid now is " + min + " Marks.").formatted(Formatting.RED), true);
            return;
        }
        if (stem(p).equals(l.bidderStem)) {
            p.sendMessage(Text.literal("You already hold the top bid.").formatted(Formatting.GRAY), true);
            return;
        }
        if (!AotRpg.WALLET.spendMarks(p, amount)) {
            p.sendMessage(Text.literal("Not enough Marks.").formatted(Formatting.RED), true);
            return;
        }
        refund(l, "you were outbid");
        l.bid = amount;
        l.bids++;
        l.bidder = p.getUuidAsString();
        l.bidderStem = stem(p);
        l.bidderName = AotRpg.PROFILES.get(p.getUuid()).name;
        // A late bid gives the others two minutes to answer.
        if (l.endsAt() - now < 120_000) l.ends = now + 120_000;
        save();
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.5f, 1.2f);
        send(p);
    }

    public static long minBid(Listing l) {
        return l.bids == 0 ? Math.max(1, l.startBid) : l.bid + Math.max(1, l.bid * 5 / 100);
    }

    /** Hands the held top bid back to its bidder. */
    private void refund(Listing l, String why) {
        if (l.bids <= 0 || l.bidderStem == null) return;
        ItemStack s = decode(l.item);
        mail(l.bidderStem, l.bid, null, "your " + l.bid + " Marks bid on " + s.getName().getString() + " came back: " + why);
        ServerPlayerEntity b = l.bidder == null ? null : server.getPlayerManager().getPlayer(java.util.UUID.fromString(l.bidder));
        if (b != null && stem(b).equals(l.bidderStem)) {
            deliver(b);
            Notify.toast(b, Text.literal("Outbid").formatted(Formatting.RED), Text.literal(s.getName().getString() + ": your Marks are back"),
                0xC0463A, "minecraft:emerald", null);
        }
        l.bids = 0;
        l.bid = 0;
        l.bidder = l.bidderStem = l.bidderName = null;
    }

    private Listing byId(long id) {
        for (Listing l : data.listings) if (l.id == id) return l;
        return null;
    }

    /** Every few minutes: expired listings go back to their sellers' mail. */
    public void tick(int ticks) {
        if (ticks % 200 != 0) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Listing l : new ArrayList<>(data.listings)) {
            if (now < l.endsAt()) continue;
            data.listings.remove(l);
            ItemStack s = decode(l.item);
            if (l.bids > 0 && l.bidderStem != null) {
                // Sold at auction: the item to the winner, the Marks (less the fee) to the seller.
                long net = Math.round(l.bid * (1 - FEE));
                mail(l.bidderStem, 0, s, "you won " + s.getCount() + "x " + s.getName().getString() + " for " + l.bid + " Marks");
                mail(l.stem, net, null, "auction sold " + s.getCount() + "x " + s.getName().getString() + " for " + net + " Marks");
                for (String who : new String[] {l.bidder, l.seller}) {
                    ServerPlayerEntity o = who == null ? null : server.getPlayerManager().getPlayer(java.util.UUID.fromString(who));
                    if (o != null) deliver(o);
                }
            } else {
                mail(l.stem, 0, s, "a listing ended unsold and came back");
                ServerPlayerEntity o = server.getPlayerManager().getPlayer(java.util.UUID.fromString(l.seller));
                if (o != null) deliver(o);
            }
            changed = true;
        }
        if (changed) save();
    }

    public void send(ServerPlayerEntity p) {
        if (!ServerPlayNetworking.canSend(p, Net.ExchangeView.ID)) return;
        AotRpg.SATCHEL.send(p, false);
        String st = stem(p);
        List<Net.ExchangeEntry> out = new ArrayList<>();
        List<Listing> sorted = new ArrayList<>(data.listings);
        sorted.sort((a, b) -> Long.compare(b.created, a.created));
        for (Listing l : sorted) {
            if (out.size() >= 120) break;
            out.add(new Net.ExchangeEntry(l.id, decode(l.item), l.price, l.sellerName, l.stem.equals(st), l.startBid, l.bid, l.bids,
                st.equals(l.bidderStem), l.bidderName == null ? "" : l.bidderName, Math.max(0, (l.endsAt() - System.currentTimeMillis()) / 1000),
                l.startBid > 0 ? minBid(l) : 0));
        }
        ServerPlayNetworking.send(p, new Net.ExchangeView(out));
    }
}
