package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The staff catalog (/catalog, operators only): any weapon or piece of clothing at any rarity,
 * level and infusion, any item from the AoT mods, crates (regular or a guaranteed jackpot), titles
 * and cosmetics. Given straight to yourself or a player nearby, or sent to anyone online as a
 * tagged gift that waits in their Inbox.
 */
public final class Catalog {
    private Catalog() {}

    public static void open(ServerPlayerEntity p) {
        if (!p.hasPermissionLevel(2) || !ServerPlayNetworking.canSend(p, Net.CatalogView.ID)) return;
        List<String> weapons = new ArrayList<>(), armor = new ArrayList<>(), items = new ArrayList<>();
        for (String w : new String[] {"blade", "apg_gun"}) {
            Item it = AotItems.exact(w);
            if (it != null) weapons.add(Registries.ITEM.getId(it).toString());
        }
        for (Item it : Gear.clothing()) armor.add(Registries.ITEM.getId(it).toString());
        for (Item it : Registries.ITEM) {
            Identifier id = Registries.ITEM.getId(it);
            String ns = id.getNamespace();
            if (!ns.equals("dannys-aot") && !ns.equals("aot_rpg") && !ns.equals(AotItems.namespace)) continue;
            String s = id.toString();
            if (weapons.contains(s) || armor.contains(s) || id.getPath().contains("spawn_egg") || Gear.bodyPart(id.getPath())) continue;
            if (!items.contains(s)) items.add(s);
        }
        List<String> crates = new ArrayList<>();
        for (Store.Crate c : Store.CRATES) crates.add(c.id() + "|" + c.title());
        List<String> titles = new ArrayList<>();
        for (Store.Title t : Store.TITLES) titles.add(t.id() + "|" + t.text() + "|" + t.color() + "|" + t.rarity());
        List<String> cosmetics = new ArrayList<>();
        for (Cosmetics.Def d : Cosmetics.ALL) if (!d.free()) cosmetics.add(d.id() + "|" + d.title());
        List<String> players = new ArrayList<>();
        players.add(p.getUuidAsString() + "|" + name(p) + " (you)|1");
        for (ServerPlayerEntity o : p.getServer().getPlayerManager().getPlayerList()) {
            if (o == p) continue;
            boolean near = o.getServerWorld() == p.getServerWorld() && o.squaredDistanceTo(p) < 64 * 64;
            players.add(o.getUuidAsString() + "|" + name(o) + "|" + (near ? 1 : 0));
        }
        ServerPlayNetworking.send(p, new Net.CatalogView(weapons, armor, items, crates, titles, cosmetics, players));
    }

    private static String name(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        String n = pr.created && pr.name != null && !pr.name.isBlank() ? pr.name : p.getName().getString();
        return n.replace('|', '/');
    }

    public static void give(ServerPlayerEntity p, Net.CatalogGive g) {
        if (!p.hasPermissionLevel(2)) return;
        ServerPlayerEntity to;
        try {
            to = p.getServer().getPlayerManager().getPlayer(UUID.fromString(g.target()));
        } catch (Exception e) {
            to = null;
        }
        if (to == null) {
            Notify.toast(p, Text.literal("That player isn't online").formatted(Formatting.RED), null, 0xC0463A, null, null);
            return;
        }
        // Straight in is for you and players around you; anyone further gets it in their inbox.
        boolean inbox = g.inbox() || (to != p && (to.getServerWorld() != p.getServerWorld() || to.squaredDistanceTo(p) > 64 * 64));
        String from = inbox ? "Staff" : null;
        int amount = Math.max(1, Math.min(g.amount(), 64 * 36));
        String what;
        switch (g.kind()) {
            case "weapon", "armor" -> {
                Item base = item(g.id());
                if (base == null) return;
                Gear.Rarity r = Gear.Rarity.values()[Math.max(0, Math.min(5, g.rarity()))];
                String inf = g.infusion().equals("random") ? null : g.infusion();
                if (inf != null && !inf.isEmpty()) {
                    try {
                        Infusions.Infusion.valueOf(inf);
                    } catch (Exception e) {
                        inf = "";
                    }
                }
                ItemStack last = ItemStack.EMPTY;
                for (int i = 0; i < Math.min(amount, 27); i++) {
                    last = Gear.make(to.getRandom(), base, r, g.level(), inf);
                    handOver(to, last, inbox, from, g.tag());
                }
                what = last.getName().getString() + (amount > 1 ? " x" + Math.min(amount, 27) : "");
            }
            case "item" -> {
                Item base = item(g.id());
                if (base == null) return;
                ItemStack s = new ItemStack(base, amount);
                what = s.getName().getString() + " x" + amount;
                if (inbox) AotRpg.INBOX.sendItem(to.getUuid(), s, from, g.tag());
                else {
                    while (!s.isEmpty()) {
                        int k = Math.min(s.getCount(), s.getMaxCount());
                        to.getInventory().offerOrDrop(s.copyWithCount(k));
                        s.decrement(k);
                    }
                }
            }
            case "crate" -> {
                Store.Crate c = Store.crate(g.id());
                if (c == null) return;
                // Crates always go to the inbox: that's where they're opened, reveal and all.
                for (int i = 0; i < Math.min(amount, 20); i++) AotRpg.INBOX.sendCrate(to.getUuid(), c.id(), g.rigged(), "Staff", g.tag());
                what = c.title() + (g.rigged() ? " (jackpot)" : "") + (amount > 1 ? " x" + Math.min(amount, 20) : "");
                inbox = true;
            }
            case "title", "cosmetic" -> {
                String spec = g.kind() + ":" + g.id();
                what = Rewards.describe(spec);
                if (inbox) AotRpg.INBOX.sendReward(to.getUuid(), spec, from, g.tag());
                else Store.grant(to, spec);
            }
            case "marks", "gold" -> {
                String spec = g.kind() + ":" + Math.max(1, g.amount());
                what = Rewards.describe(spec);
                if (inbox) AotRpg.INBOX.sendReward(to.getUuid(), spec, from, g.tag());
                else Rewards.give(to, spec, "Staff");
            }
            default -> {
                return;
            }
        }
        String who = to == p ? "you" : name(to);
        Notify.toast(p, Text.literal((inbox ? "Sent to " : "Given to ") + who).formatted(Formatting.GOLD), Text.literal(what), 0xE0B96A, null, null);
        AotRpg.LOG.info("[catalog] {} {} {} to {}", p.getName().getString(), inbox ? "sent" : "gave", what, to.getName().getString());
    }

    private static void handOver(ServerPlayerEntity to, ItemStack s, boolean inbox, String from, String tag) {
        if (inbox) AotRpg.INBOX.sendItem(to.getUuid(), s, from, tag);
        else to.getInventory().offerOrDrop(s);
    }

    private static Item item(String id) {
        Identifier i = Identifier.tryParse(id);
        if (i == null || !Registries.ITEM.containsId(i)) return null;
        return Registries.ITEM.get(i);
    }
}
