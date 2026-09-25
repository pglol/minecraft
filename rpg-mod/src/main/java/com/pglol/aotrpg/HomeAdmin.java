package com.pglol.aotrpg;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The operator's property office: every home and plot, who owns it, and tools to teleport to
 * it, give it to a known or recent player, take it back, or send a player a private offer at a
 * price of your choosing. Index convention: 0.. homes, -1.. plots (-(plot+1)).
 */
final class HomeAdmin {
    private HomeAdmin() {}

    static final int PAGE = 40;

    static boolean op(ServerPlayerEntity p) {
        return p.hasPermissionLevel(2);
    }

    /** Remembers players who joined recently, for the grant/offer pickers. */
    static void joined(ServerPlayerEntity p) {
        List<String> r = AotRpg.HOMES.data.recent;
        String n = p.getName().getString();
        r.remove(n);
        r.add(0, n);
        while (r.size() > 40) r.remove(r.size() - 1);
        AotRpg.HOMES.save();
    }

    private static UUID uuidOf(MinecraftServer server, String name) {
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(name);
        if (online != null) return online.getUuid();
        if (server.getUserCache() == null) return null;
        return server.getUserCache().findByName(name).map(GameProfile::getId).orElse(null);
    }

    static void action(ServerPlayerEntity op, String action, int index, String arg) {
        if (!op(op)) return;
        MinecraftServer server = op.getServer();
        switch (action) {
            case "admin_list" -> send(op, arg);
            case "admin_tp" -> {
                if (index >= 0 && index < AotRpg.PLACES.homes.size()) {
                    int[] h = AotRpg.PLACES.homes.get(index);
                    op.teleport(server.getOverworld(), h[6] + 0.5, h[4] + 1, h[7] + 0.5, op.getYaw(), 0);
                } else if (index < 0 && -index - 1 < AotRpg.PLACES.plots.size()) {
                    Places.PlotInfo p = AotRpg.PLACES.plots.get(-index - 1);
                    op.teleport(server.getOverworld(), (p.x0() + p.x1()) / 2.0, p.y() + 1, (p.z0() + p.z1()) / 2.0, op.getYaw(), 0);
                }
            }
            case "admin_grant", "admin_revoke" -> {
                UUID id = uuidOf(server, arg);
                if (id == null) {
                    op.sendMessage(Text.literal("Unknown player: " + arg).formatted(Formatting.RED), false);
                    return;
                }
                String stem = AotRpg.PROFILES.activeStem(id);
                String name = AotRpg.PROFILES.peek(id, AotRpg.PROFILES.account(id).active).name;
                if (name.isEmpty()) name = arg;
                boolean grant = action.equals("admin_grant");
                if (index >= 0) {
                    if (grant) AotRpg.HOMES.grant(stem, name, index);
                    else AotRpg.HOMES.revoke(stem, index);
                } else {
                    if (grant) HomePlots.give(stem, name, -index - 1, server.getOverworld());
                    else HomePlots.revoke(-index - 1);
                }
                op.sendMessage(Text.literal((grant ? "Gave " : "Took ") + label(index) + (grant ? " to " : " from ") + name).formatted(Formatting.GOLD), false);
            }
            case "admin_offer" -> {
                String[] parts = arg.split("\\|");
                if (parts.length != 2) return;
                UUID id = uuidOf(server, parts[0]);
                long price;
                try {
                    price = Math.max(0, Long.parseLong(parts[1]));
                } catch (NumberFormatException e) {
                    return;
                }
                if (id == null) {
                    op.sendMessage(Text.literal("Unknown player: " + parts[0]).formatted(Formatting.RED), false);
                    return;
                }
                Homes.Offer o = new Homes.Offer();
                o.kind = index >= 0 ? "home" : "plot";
                o.index = index;
                o.player = id.toString();
                o.name = parts[0];
                o.price = price;
                AotRpg.HOMES.data.offers.removeIf(x -> x.player.equals(o.player) && x.index == o.index);
                AotRpg.HOMES.data.offers.add(o);
                AotRpg.HOMES.save();
                op.sendMessage(Text.literal("Offered " + label(index) + " to " + parts[0] + " for " + price + " Marks.").formatted(Formatting.GOLD), false);
                ServerPlayerEntity target = server.getPlayerManager().getPlayer(id);
                if (target != null) notify(target);
            }
            default -> { }
        }
        if (!action.equals("admin_list") && !action.equals("admin_tp")) send(op, "");
    }

    static String label(int index) {
        if (index >= 0) {
            int[] h = AotRpg.PLACES.homes.get(index);
            return "Home #" + index + " (" + AotRpg.HOMES.townOf(h) + ")";
        }
        return HomePlots.label(AotRpg.PLACES.plots.get(-index - 1));
    }

    /** Tells a player they have an offer waiting. */
    static void notify(ServerPlayerEntity p) {
        for (Homes.Offer o : AotRpg.HOMES.data.offers) {
            if (!o.player.equals(p.getUuidAsString())) continue;
            p.sendMessage(Text.literal("You have been offered ").formatted(Formatting.GOLD).append(Text.literal(label(o.index)).formatted(Formatting.YELLOW))
                .append(Text.literal(" for " + o.price + " Marks. ").formatted(Formatting.GOLD))
                .append(Text.literal("[View offer]").formatted(Formatting.GREEN, Formatting.BOLD).styled(s -> s
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/home offers"))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Open the deed"))))), false);
        }
    }

    static Homes.Offer offerFor(ServerPlayerEntity p, int index) {
        for (Homes.Offer o : AotRpg.HOMES.data.offers) if (o.player.equals(p.getUuidAsString()) && o.index == index) return o;
        return null;
    }

    static Homes.Offer firstOffer(ServerPlayerEntity p) {
        for (Homes.Offer o : AotRpg.HOMES.data.offers) if (o.player.equals(p.getUuidAsString())) return o;
        return null;
    }

    /** One page of the list. filter: "text|page|owned". */
    static void send(ServerPlayerEntity op, String filter) {
        if (!ServerPlayNetworking.canSend(op, Net.HomeAdminView.ID)) return;
        String[] f = (filter == null ? "" : filter).split("\\|", -1);
        String text = f.length > 0 ? f[0].toLowerCase(Locale.ROOT) : "";
        int page = f.length > 1 && !f[1].isEmpty() ? Integer.parseInt(f[1]) : 0;
        boolean ownedOnly = f.length > 2 && f[2].equals("1");
        List<Net.AdminRow> all = new ArrayList<>();
        var plots = AotRpg.PLACES.plots;
        for (int i = 0; i < plots.size(); i++) {
            Homes.PlotDeed d = AotRpg.HOMES.data.plots.get(i);
            String owners = d == null ? "" : d.ownerName;
            all.add(new Net.AdminRow(-(i + 1), HomePlots.label(plots.get(i)), plots.get(i).size() + " plot", HomePlots.price(plots.get(i)), owners));
        }
        var homes = AotRpg.PLACES.homes;
        for (int i = 0; i < homes.size(); i++) {
            int[] h = homes.get(i);
            List<String> owners = AotRpg.HOMES.ownersOf(i);
            all.add(new Net.AdminRow(i, "Home #" + i + " · " + AotRpg.HOMES.townOf(h), (h[2] - h[0] + 1) + "x" + (h[3] - h[1] + 1) + " house",
                Homes.price(h), String.join(", ", owners)));
        }
        List<Net.AdminRow> rows = new ArrayList<>();
        for (Net.AdminRow r : all) {
            if (ownedOnly && r.owners().isEmpty()) continue;
            if (!text.isEmpty() && !(r.label() + " " + r.owners()).toLowerCase(Locale.ROOT).contains(text)) continue;
            rows.add(r);
        }
        int pages = Math.max(1, (rows.size() + PAGE - 1) / PAGE);
        page = Math.max(0, Math.min(pages - 1, page));
        List<Net.AdminRow> slice = rows.subList(page * PAGE, Math.min(rows.size(), page * PAGE + PAGE));
        List<String> known = new ArrayList<>();
        for (ServerPlayerEntity o : op.getServer().getPlayerManager().getPlayerList()) known.add(o.getName().getString());
        for (String r : AotRpg.HOMES.data.recent) if (!known.contains(r)) known.add(r);
        ServerPlayNetworking.send(op, new Net.HomeAdminView(new ArrayList<>(slice), page, pages, rows.size(), known, true));
    }
}
