package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Factions and the sectors they fight over. Each sector posts work orders every cycle (deliver
 * goods to its markets, clear titans in its land). Completing one pays Marks and reputation and
 * shifts the sector's influence towards your faction. The faction with the most influence
 * controls the sector: its members trade 10% better in that sector's markets, and a share of
 * the trade there fills the faction treasury.
 */
public final class Factions {
    public enum Faction {
        SURVEY_CORPS("Survey Corps", 0x3F8F4A, "Wings of Freedom. Beyond the walls."),
        GARRISON("Garrison", 0xB8473A, "The walls and the towns within them."),
        MILITARY_POLICE("Military Police", 0x4A78C0, "Order in the interior, and the King's favour.");

        public final String title, motto;
        public final int color;

        Faction(String title, int color, String motto) {
            this.title = title;
            this.color = color;
            this.motto = motto;
        }
    }

    public static String abbr(Faction f) {
        return switch (f) {
            case SURVEY_CORPS -> "SC";
            case GARRISON -> "GAR";
            case MILITARY_POLICE -> "MP";
        };
    }

    public static final long CYCLE_MS = 6L * 3600 * 1000;
    public static final int MAX_ACTIVE = 3;

    private static final class Data {
        Map<Sector, Map<Faction, Double>> influence = new EnumMap<>(Sector.class);
        Map<Faction, Long> treasury = new EnumMap<>(Faction.class);
    }

    /** A work order, derived from the cycle so every player sees the same board. */
    public record Order(String id, Sector sector, String type, String item, int qty, long marks, int rep) { }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private Data data = new Data();
    private Path file;

    public void open(MinecraftServer server) {
        file = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("factions.json");
        try {
            if (Files.exists(file)) data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read factions.json", e);
        }
        if (data == null) data = new Data();
        if (data.influence == null) data.influence = new EnumMap<>(Sector.class);
        if (data.treasury == null) data.treasury = new EnumMap<>(Faction.class);
        for (Sector s : Sector.values()) {
            Map<Faction, Double> m = data.influence.computeIfAbsent(s, k -> new EnumMap<>(Faction.class));
            for (Faction f : Faction.values()) m.putIfAbsent(f, 100.0 / Faction.values().length);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save factions.json", e);
        }
    }

    public static Faction of(Profile pr) {
        if (pr.faction == null || pr.faction.isEmpty()) return null;
        try {
            return Faction.valueOf(pr.faction);
        } catch (Exception e) {
            return null;
        }
    }

    public Faction controller(Sector s) {
        Faction best = null;
        double bv = -1;
        for (var e : data.influence.get(s).entrySet()) {
            if (e.getValue() > bv + 0.001) {
                bv = e.getValue();
                best = e.getKey();
            }
        }
        // A tie at the start means nobody controls it yet.
        double even = 100.0 / Faction.values().length;
        return bv <= even + 0.5 ? null : best;
    }

    public String controllerName(Sector s) {
        Faction f = controller(s);
        return f == null ? "" : f.title;
    }

    public boolean controls(ServerPlayerEntity p, Sector s) {
        Faction f = of(AotRpg.PROFILES.get(p.getUuid()));
        return f != null && f == controller(s);
    }

    /** A share of market trade goes to the controlling faction's treasury. */
    public void taxed(Sector s, long amount) {
        Faction f = controller(s);
        if (f == null || amount <= 0) return;
        data.treasury.merge(f, Math.max(1, amount / 20), Long::sum);
    }

    /** Influence gained (or lost) in a sector. */
    public void influence(Sector s, Faction f, double amount) {
        shift(s, f, amount);
    }

    public int sectorsHeld(Faction f) {
        int n = 0;
        if (f == null) return 0;
        for (Sector s : Sector.values()) if (controller(s) == f) n++;
        return n;
    }

    /** The faction holding the most sectors (none on a tie). */
    public Faction leader() {
        Faction best = null;
        int bn = 0;
        boolean tie = false;
        for (Faction f : Faction.values()) {
            int n = sectorsHeld(f);
            if (n > bn) {
                bn = n;
                best = f;
                tie = false;
            } else if (n == bn && n > 0) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    /** Faction rewards (orders, Calls to Arms): +8% per sector held, +10% more for the leading faction. */
    public double multiplier(Faction f) {
        if (f == null) return 1;
        return 1 + 0.08 * sectorsHeld(f) + (leader() == f ? 0.10 : 0);
    }

    /** The leading faction's soldiers earn 10% more Marks from everything. */
    public double earnBonus(Profile pr) {
        Faction f = of(pr);
        return f != null && leader() == f ? 0.10 : 0;
    }

    private void shift(Sector s, Faction f, double amount) {
        Map<Faction, Double> m = data.influence.get(s);
        m.merge(f, amount, Double::sum);
        double total = 0;
        for (double v : m.values()) total += Math.max(0, v);
        for (var e : m.entrySet()) e.setValue(Math.max(0, e.getValue()) * 100 / total);
        save();
    }

    // ------------------------------------------------------------------ orders

    public static long cycle() {
        return System.currentTimeMillis() / CYCLE_MS;
    }

    public List<Order> orders() {
        long c = cycle();
        List<Order> out = new ArrayList<>();
        List<Market.Good> goods = AotRpg.MARKET.config.goods;
        for (Sector s : Sector.values()) {
            java.util.Random r = new java.util.Random(c * 31 + s.ordinal());
            for (int i = 0; i < 3; i++) {
                boolean slay = (s == Sector.BEYOND || s == Sector.MARIA || s == Sector.MARLEY) && r.nextInt(3) == 0;
                String id = c + ":" + s.name() + ":" + i;
                if (slay) {
                    int n = 5 + r.nextInt(8);
                    out.add(new Order(id, s, "slay", "", n, 25L * n, 6 + n / 2));
                } else if (!goods.isEmpty()) {
                    Market.Good g = goods.get(r.nextInt(goods.size()));
                    int qty = (int) Math.max(4, Math.min(48, Math.round(160 / Math.max(1, g.price))));
                    long pay = Math.round(g.price * qty * 1.6) + 20;
                    out.add(new Order(id, s, "deliver", g.item, qty, pay, 5));
                }
            }
        }
        return out;
    }

    private Order order(String id) {
        for (Order o : orders()) if (o.id().equals(id)) return o;
        return null;
    }

    private void prune(Profile pr) {
        String prefix = cycle() + ":";
        pr.orders.keySet().removeIf(k -> !k.startsWith(prefix));
    }

    public void action(ServerPlayerEntity p, String action, String arg) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created) return;
        prune(pr);
        switch (action) {
            case "join" -> {
                Faction f;
                try {
                    f = Faction.valueOf(arg);
                } catch (Exception e) {
                    return;
                }
                if (of(pr) == f) return;
                long wait = pr.factionJoined + 24L * 3600 * 1000 - System.currentTimeMillis();
                if (of(pr) != null && wait > 0) {
                    p.sendMessage(Text.literal("You can change faction in " + (wait / 3600000 + 1) + " h.").formatted(Formatting.RED), true);
                    return;
                }
                pr.faction = f.name();
                pr.factionRep = 0;
                pr.factionJoined = System.currentTimeMillis();
                AotRpg.NAMETAGS.dirty();
                p.sendMessage(Text.literal("You joined the " + f.title + ".").withColor(f.color), false);
                p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_BELL_USE, SoundCategory.PLAYERS, 0.8f, 1f);
            }
            case "leave" -> {
                AotRpg.NAMETAGS.dirty();
                pr.faction = "";
                pr.factionRep = 0;
                pr.orders.clear();
            }
            case "accept" -> {
                Order o = order(arg);
                if (o == null || of(pr) == null || pr.orders.containsKey(o.id())) return;
                long active = pr.orders.values().stream().filter(v -> v >= 0).count();
                if (active >= MAX_ACTIVE) {
                    p.sendMessage(Text.literal("You can hold " + MAX_ACTIVE + " work orders at once.").formatted(Formatting.RED), true);
                    return;
                }
                pr.orders.put(o.id(), 0);
            }
            case "turnin" -> turnIn(p, pr, arg);
            case "abandon" -> pr.orders.remove(arg);
            default -> { }
        }
        AotRpg.PROFILES.save(p.getUuid());
        send(p, false);
    }

    private void turnIn(ServerPlayerEntity p, Profile pr, String id) {
        Order o = order(id);
        Integer prog = pr.orders.get(id);
        if (o == null || prog == null || prog < 0) return;
        if (o.type().equals("deliver")) {
            if (Sector.at(p.getX(), p.getZ()) != o.sector() || AotRpg.MARKET.town(p) == null) {
                p.sendMessage(Text.literal("Deliver it to a market in " + o.sector().title + ".").formatted(Formatting.RED), true);
                return;
            }
            Item it = Registries.ITEM.get(Identifier.of(o.item()));
            if (!take(p, it, o.qty())) {
                p.sendMessage(Text.literal("You need " + o.qty() + "x " + it.getName().getString() + ".").formatted(Formatting.RED), true);
                return;
            }
        } else if (prog < o.qty()) {
            return;
        }
        complete(p, pr, o);
    }

    private static boolean take(ServerPlayerEntity p, Item it, int qty) {
        int have = 0;
        List<ItemStack> all = new ArrayList<>(p.getInventory().main);
        all.addAll(AotRpg.SATCHEL.get(p.getUuid()).getHeldStacks());
        for (ItemStack s : all) if (s.isOf(it) && !Gear.isGear(s)) have += s.getCount();
        if (have < qty) return false;
        for (ItemStack s : all) {
            if (qty <= 0) break;
            if (!s.isOf(it) || Gear.isGear(s)) continue;
            int k = Math.min(qty, s.getCount());
            s.decrement(k);
            qty -= k;
        }
        AotRpg.SATCHEL.get(p.getUuid()).markDirty();
        return true;
    }

    private void complete(ServerPlayerEntity p, Profile pr, Order o) {
        Faction f = of(pr);
        pr.orders.put(o.id(), -1);
        double mult = multiplier(f);
        pr.factionRep += (int) Math.round(o.rep() * mult);
        AotRpg.WALLET.earn(p, Math.round(o.marks() * mult), "Work order");
        AotRpg.SEASON.xp(p, Season.XP_ORDER);
        AotRpg.TASKS.count(p, Tasks.ORDERS, 1);
        AotRpg.ROLES.addPoints(p, 10);
        if (f != null) shift(o.sector(), f, 1.5 + o.rep() / 6.0);
        Titles.show(p, Text.literal("ORDER COMPLETE").formatted(Formatting.GOLD, Formatting.BOLD),
            Text.literal("+" + o.rep() + " reputation · " + o.sector().title).formatted(Formatting.GRAY), 5, 40, 15);
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.6f, 1.2f);
    }

    public void onTitanKill(ServerPlayerEntity p, double x, double z) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (pr.orders.isEmpty()) return;
        Sector s = Sector.at(x, z);
        boolean changed = false;
        for (Order o : orders()) {
            Integer v = pr.orders.get(o.id());
            if (v == null || v < 0 || !o.type().equals("slay") || o.sector() != s) continue;
            v = Math.min(o.qty(), v + 1);
            pr.orders.put(o.id(), v);
            changed = true;
            if (v >= o.qty()) complete(p, pr, o);
        }
        if (changed) AotRpg.PROFILES.save(p.getUuid());
    }

    public void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.FactionView.ID)) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        prune(pr);
        List<Net.SectorInfo> sectors = new ArrayList<>();
        for (Sector s : Sector.values()) {
            float[] inf = new float[Faction.values().length];
            for (Faction f : Faction.values()) inf[f.ordinal()] = data.influence.get(s).get(f).floatValue();
            Faction c = controller(s);
            sectors.add(new Net.SectorInfo(s.title, inf, c == null ? -1 : c.ordinal()));
        }
        List<Net.OrderInfo> orders = new ArrayList<>();
        for (Order o : orders()) {
            Integer v = pr.orders.get(o.id());
            String what = o.type().equals("slay") ? "Slay " + o.qty() + " titans in " + o.sector().title
                : "Deliver " + o.qty() + "x " + Registries.ITEM.get(Identifier.of(o.item())).getName().getString() + " to a market in " + o.sector().title;
            orders.add(new Net.OrderInfo(o.id(), o.sector().ordinal(), what, o.marks(), o.rep(), v == null ? -2 : v, o.qty()));
        }
        long left = CYCLE_MS - System.currentTimeMillis() % CYCLE_MS;
        long[] treasury = new long[Faction.values().length];
        for (Faction f : Faction.values()) treasury[f.ordinal()] = data.treasury.getOrDefault(f, 0L);
        Faction mine = of(pr);
        int n = Faction.values().length;
        int[] held = new int[n];
        float[] mult = new float[n];
        for (Faction f : Faction.values()) {
            held[f.ordinal()] = sectorsHeld(f);
            mult[f.ordinal()] = (float) multiplier(f);
        }
        Faction lead = leader();
        int[] walls = AotRpg.PLACES.walls == null ? new int[0] : AotRpg.PLACES.walls;
        FactionWar.Event ev = AotRpg.WAR.active();
        long now = System.currentTimeMillis();
        List<String> top = new ArrayList<>();
        String evText = "";
        long evLeft = 0;
        int mine2 = 0;
        if (ev != null) {
            evText = ev.town + " · " + ev.sector.title;
            evLeft = Math.max(0, (ev.until - now) / 1000);
            ev.kills.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(5)
                .forEach(e -> top.add(ev.names.getOrDefault(e.getKey(), "?") + "\u0000" + e.getValue()));
            mine2 = ev.kills.getOrDefault(p.getUuid(), 0);
        }
        ServerPlayNetworking.send(p, new Net.FactionView(mine == null ? -1 : mine.ordinal(), pr.factionRep,
            Sector.at(p.getX(), p.getZ()).ordinal(), sectors, orders, left / 1000, treasury, open, walls, held,
            lead == null ? -1 : lead.ordinal(), mult, evText, evLeft, top, mine2, Math.max(0, (AotRpg.WAR.nextAt() - now) / 1000)));
    }
}
