package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Traders in every town: an Armorer, a Bladesmith, a Provisioner and a Toolmaker keep stalls around
 * the square, and somewhere in the back streets a hooded Stranger sells what fell off a wagon:
 * high-ranked gear, cheap, and stolen. Stock is the same for everyone and changes each day; once
 * someone buys a piece, it's gone.
 */
public final class Vendors {
    public static final String TAG = "aot_vendor";

    public enum Kind {
        ARMORER("Armorer", "Armour and uniforms"), BLADESMITH("Bladesmith", "Grips, blades and supplies"),
        PROVISIONER("Provisioner", "The best food in town"), TOOLMAKER("Toolmaker", "Tools for every trade"),
        STRANGER("Hooded Stranger", "No questions asked");

        public final String title, pitch;

        Kind(String title, String pitch) {
            this.title = title;
            this.pitch = pitch;
        }
    }

    private static final String[] NAMES = {"Hilde", "Gunter", "Marta", "Otto", "Liesl", "Bruno", "Frida", "Ulrich", "Greta", "Kaspar", "Wanda", "Ewald"};

    /** What's been bought today: town/kind/day -> offer index -> units gone. */
    private final Map<String, Map<Integer, Integer>> sold = new HashMap<>();

    private List<Net.Area> towns() {
        List<Net.Area> out = new ArrayList<>();
        for (Net.Area a : AotRpg.PLACES.areas()) if (a.look().equals("town")) out.add(a);
        return out;
    }

    public static boolean vendor(Entity e) {
        return e.getCommandTags().contains(TAG);
    }

    // ------------------------------------------------------------------ being there

    public void tick(ServerWorld w, int ticks) {
        if (ticks % 40 != 21) return;
        List<ServerPlayerEntity> players = new ArrayList<>(w.getPlayers());
        for (Net.Area t : towns()) {
            boolean near = false;
            for (ServerPlayerEntity p : players) if ((p.getX() - t.x()) * (p.getX() - t.x()) + (p.getZ() - t.z()) * (p.getZ() - t.z()) < 100 * 100) near = true;
            if (!near || !w.isChunkLoaded(t.x() >> 4, t.z() >> 4)) continue;
            for (Kind kind : Kind.values()) {
                boolean back = kind == Kind.STRANGER;
                Stalls.Spot spot = Stalls.place(w, "vendor:" + t.id() + ":" + kind.name(), t, kind.name(), back ? 26 : 8, back ? 48 : 22,
                    (t.id() + kind.name()).hashCode());
                if (spot == null) continue;
                BlockPos s = spot.pos();
                float yaw = spot.facing().asRotation();
                UUID id = id(t, kind);
                Entity ent = w.getEntity(id);
                if (ent instanceof VillagerEntity v && v.isAlive()) {
                    // Keep them in their stall, facing the counter.
                    if (v.squaredDistanceTo(s.getX() + 0.5, s.getY(), s.getZ() + 0.5) > 0.5) v.refreshPositionAndAngles(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, yaw, 0);
                    v.setHeadYaw(yaw);
                    v.setBodyYaw(yaw);
                } else if (w.isChunkLoaded(s.getX() >> 4, s.getZ() >> 4)) {
                    spawn(w, t, kind, s, id);
                }
            }
        }
        if (ticks % 200 == 21) {
            List<VillagerEntity> ours = new ArrayList<>();
            for (Entity e : w.iterateEntities()) if (e instanceof VillagerEntity v && vendor(v)) ours.add(v);
            for (VillagerEntity v : ours) {
                boolean close = false;
                for (ServerPlayerEntity p : players) if (p.squaredDistanceTo(v) < 130 * 130) close = true;
                if (!close) v.discard();
            }
        }
    }

    /**
     * How a local points you to a trader: their name, which way their stall is from (x, z) and
     * roughly how far. Null when this town has no stall of that kind yet.
     */
    public static String directions(Net.Area t, Kind k, double x, double z) {
        Stalls.Spot sp = Stalls.get("vendor:" + t.id() + ":" + k.name());
        if (sp == null) return null;
        double dx = sp.x() + 0.5 - x, dz = sp.z() + 0.5 - z;
        int paces = (int) Math.round(Math.sqrt(dx * dx + dz * dz) / 5.0) * 5;
        String[] dirs = {"south", "southwest", "west", "northwest", "north", "northeast", "east", "southeast"};
        String dir = dirs[Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(-dx, dz)) / 45.0), 8)];
        String who = k == Kind.STRANGER ? "a hooded fellow" : NAMES[Math.floorMod((t.id() + k.name()).hashCode(), NAMES.length)];
        return who + "|" + dir + "|" + Math.max(5, paces);
    }

    /** The town (with traders) this spot is in, or null. */
    public Net.Area townAt(double x, double z) {
        for (Net.Area a : towns()) if ((x - a.x()) * (x - a.x()) + (z - a.z()) * (z - a.z()) < 110 * 110) return a;
        return null;
    }

    private static UUID id(Net.Area t, Kind k) {
        return UUID.nameUUIDFromBytes(("aot_vendor:" + t.id() + ":" + k.name()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String name(Net.Area t, Kind k) {
        if (k == Kind.STRANGER) return k.title;
        return NAMES[Math.floorMod((t.id() + k.name()).hashCode(), NAMES.length)] + ", " + k.title;
    }

    private void spawn(ServerWorld w, Net.Area t, Kind k, BlockPos at, UUID id) {
        VillagerEntity v = EntityType.VILLAGER.create(w);
        if (v == null) return;
        v.setUuid(id);
        Stalls.Spot spot = Stalls.get("vendor:" + t.id() + ":" + k.name());
        float yaw = spot == null ? 0 : spot.facing().asRotation();
        v.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, 0);
        v.initialize(w, w.getLocalDifficulty(at), SpawnReason.EVENT, null);
        v.setVillagerData(v.getVillagerData().withProfession(VillagerProfession.NONE));
        v.setSilent(true);
        v.setAiDisabled(true);
        v.setInvulnerable(true);
        v.addCommandTag(TAG);
        v.addCommandTag(TAG + ":" + t.id() + ":" + k.name());
        v.setCustomName(Text.literal(name(t, k)));
        v.setCustomNameVisible(k != Kind.STRANGER);
        v.setHeadYaw(yaw);
        v.setBodyYaw(yaw);
        w.spawnEntity(v);
    }

    private record Who(Net.Area town, Kind kind) { }

    private Who who(Entity e) {
        for (String tag : e.getCommandTags()) {
            if (!tag.startsWith(TAG + ":")) continue;
            String[] a = tag.split(":");
            if (a.length < 3) return null;
            Net.Area t = AotRpg.PLACES.area(a[1]);
            try {
                return t == null ? null : new Who(t, Kind.valueOf(a[2]));
            } catch (Exception ex) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ stock

    /** One line of stock: the item (one unit of it), the price each, and how many there are today. */
    private record Offer(ItemStack stack, long each, int units) { }

    private static long day(ServerWorld w) {
        return w.getTimeOfDay() / 24000;
    }

    /** Today's stock for this trader: the same for everyone, rolled from the town, the trade and the day. */
    private List<Offer> stock(ServerWorld w, Net.Area t, Kind k) {
        net.minecraft.util.math.random.Random r = net.minecraft.util.math.random.Random.create((t.id() + ":" + k.name() + ":" + day(w)).hashCode());
        int lo = Math.max(1, t.min()), hi = Math.max(lo + 2, t.max() + 4);
        List<Offer> out = new ArrayList<>();
        switch (k) {
            case ARMORER -> {
                for (int i = 0; i < 7; i++) {
                    Gear.Rarity rar = rarity(r, 0.35f, 0.35f, 0.2f, 0.08f, 0.02f);
                    ItemStack s = Gear.rollArmor(r, rar, lo + r.nextInt(hi - lo + 1));
                    out.add(new Offer(s, Market.gearValue(s) * 4, 1));
                }
            }
            case BLADESMITH -> {
                Item grip = AotItems.exact("blade");
                for (int i = 0; grip != null && i < 3; i++) {
                    Gear.Rarity rar = rarity(r, 0.3f, 0.38f, 0.22f, 0.08f, 0.02f);
                    ItemStack s = Gear.make(r, grip, rar, lo + r.nextInt(hi - lo + 1), null);
                    out.add(new Offer(s, Market.gearValue(s) * 4, 1));
                }
                supply(out, "blade_component", 16, 10);
                supply(out, "gas_canister", 2, 26);
                supply(out, "ice_burst_cluster", 32, 4);
                supply(out, "thunder_spear", 2, 180);
                supply(out, "apg_cartridge", 16, 5);
            }
            case PROVISIONER -> {
                vanilla(out, Items.COOKED_BEEF, 8, 13);
                vanilla(out, Items.BREAD, 12, 7);
                vanilla(out, Items.GOLDEN_CARROT, 6, 30);
                vanilla(out, Items.COOKED_PORKCHOP, 8, 13);
                vanilla(out, Items.PUMPKIN_PIE, 4, 16);
                vanilla(out, Items.BAKED_POTATO, 12, 6);
                vanilla(out, Items.GOLDEN_APPLE, 1, 400);
                vanilla(out, Items.HONEY_BOTTLE, 3, 12);
                Item wine = AotItems.exact("vintage_wine");
                if (wine != null) vanilla(out, wine, 1, 40);
            }
            case TOOLMAKER -> {
                vanilla(out, Items.IRON_PICKAXE, 1, 60);
                vanilla(out, Items.DIAMOND_PICKAXE, 1, 420);
                vanilla(out, Items.IRON_AXE, 1, 55);
                vanilla(out, Items.IRON_SHOVEL, 1, 35);
                vanilla(out, Items.FISHING_ROD, 1, 22);
                vanilla(out, Items.SHEARS, 1, 18);
                vanilla(out, Items.SADDLE, 1, 90);
                vanilla(out, Items.LEAD, 2, 16);
                vanilla(out, Items.SPYGLASS, 1, 70);
                vanilla(out, Items.LANTERN, 2, 12);
            }
            case STRANGER -> {
                // High-ranked and cheap for it: stolen from a quartermaster, a noble, a dead captain.
                for (int i = 0; i < 4; i++) {
                    Gear.Rarity rar = rarity(r, 0, 0, 0.45f, 0.43f, 0.12f);
                    ItemStack s = r.nextBoolean() && AotItems.exact("blade") != null
                        ? Gear.make(r, AotItems.exact("blade"), rar, hi + 2 + r.nextInt(4), null)
                        : Gear.rollArmor(r, rar, hi + 2 + r.nextInt(4));
                    stolen(s);
                    out.add(new Offer(s, Math.round(Market.gearValue(s) * 2.4), 1));
                }
            }
        }
        return out;
    }

    private static Gear.Rarity rarity(net.minecraft.util.math.random.Random r, float un, float rare, float epic, float leg, float myth) {
        float x = r.nextFloat();
        if (x < myth) return Gear.Rarity.MYTHIC;
        if ((x -= myth) < leg) return Gear.Rarity.LEGENDARY;
        if ((x -= leg) < epic) return Gear.Rarity.EPIC;
        if ((x -= epic) < rare) return Gear.Rarity.RARE;
        if ((x - rare) < un) return Gear.Rarity.UNCOMMON;
        return Gear.Rarity.COMMON;
    }

    private static void supply(List<Offer> out, String path, int n, long each) {
        Item it = AotItems.exact(path);
        if (it != null) out.add(new Offer(new ItemStack(it), each, n * 3));
    }

    private static void vanilla(List<Offer> out, Item it, int n, long each) {
        out.add(new Offer(new ItemStack(it), each, n * 2));
    }

    /** Marks a piece as stolen (it says so, and the Military Police know it when they see it). */
    public static void stolen(ItemStack s) {
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        NbtCompound root = c == null ? new NbtCompound() : c.copyNbt();
        NbtCompound g = root.getCompound("aot_gear");
        g.putBoolean("stolen", true);
        root.put("aot_gear", g);
        s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
        Gear.apply(s);
    }

    public static boolean isStolen(ItemStack s) {
        return Gear.isGear(s) && Gear.data(s).getBoolean("stolen");
    }

    // ------------------------------------------------------------------ trading

    /** Using a trader: their stall. True when handled. */
    public boolean use(ServerPlayerEntity p, Entity e) {
        if (!vendor(e)) return false;
        Who who = who(e);
        if (who == null || !ServerPlayNetworking.canSend(p, Net.VendorView.ID)) return true;
        send(p, e, who, true);
        return true;
    }

    private void send(ServerPlayerEntity p, Entity e, Who who, boolean open) {
        ServerWorld w = p.getServerWorld();
        List<Offer> st = stock(w, who.town(), who.kind());
        Map<Integer, Integer> gone = sold.getOrDefault(key(w, who), Map.of());
        List<ItemStack> items = new ArrayList<>();
        List<Long> prices = new ArrayList<>();
        List<Integer> units = new ArrayList<>();
        for (int i = 0; i < st.size(); i++) {
            items.add(st.get(i).stack());
            prices.add(st.get(i).each());
            units.add(Math.max(0, st.get(i).units() - gone.getOrDefault(i, 0)));
        }
        // What they'd buy off you: your backpack and satchel (never what's on your loadout bar).
        List<Net.BagEntry> sells = new ArrayList<>();
        List<Long> sellPrices = new ArrayList<>();
        for (int addr : AotRpg.SATCHEL.addresses(p)) {
            if (addr < 9) continue;
            ItemStack s = AotRpg.SATCHEL.at(p, addr);
            long each = sellEach(who.kind(), s, st);
            if (each <= 0) continue;
            sells.add(new Net.BagEntry(addr, s.copy()));
            sellPrices.add(each);
            if (sells.size() >= 120) break;
        }
        String name = e.hasCustomName() ? e.getCustomName().getString() : who.kind().title;
        ServerPlayNetworking.send(p, new Net.VendorView(e.getId(), name, who.kind().pitch + " · " + who.town().name(),
            who.kind() == Kind.STRANGER, items, prices, units, sells, sellPrices, open));
    }

    private static String key(ServerWorld w, Who who) {
        return who.town().id() + "/" + who.kind().name() + "/" + day(w);
    }

    /** What this trader pays for one of these (0: not something they deal in). */
    private static long sellEach(Kind k, ItemStack s, List<Offer> stock) {
        if (s.isEmpty() || Satchel.isStory(s)) return 0;
        if (Gear.isGear(s)) {
            boolean armor = !Gear.aotWeapon(s);
            boolean stolen = isStolen(s);
            // Only the fence touches stolen goods, and pays best for gear of any kind.
            if (k == Kind.STRANGER) return Math.max(1, Math.round(Market.gearValue(s) * 4 * 0.45));
            if (stolen) return 0;
            if (k == Kind.ARMORER && armor || k == Kind.BLADESMITH && !armor) return Math.max(1, Math.round(Market.gearValue(s) * 4 * 0.3));
            return 0;
        }
        for (Offer o : stock) if (o.stack().isOf(s.getItem()) && !Gear.isGear(o.stack())) return Math.max(1, Math.round(o.each() * 0.35));
        return switch (k) {
            case PROVISIONER -> s.contains(DataComponentTypes.FOOD) ? 2 : 0;
            case TOOLMAKER -> s.getItem() instanceof net.minecraft.item.MiningToolItem || s.getItem() instanceof net.minecraft.item.ShearsItem
                || s.getItem() instanceof net.minecraft.item.FishingRodItem ? 8 : 0;
            case BLADESMITH -> Registries.ITEM.getId(s.getItem()).getNamespace().equals("dannys-aot") ? 3 : 0;
            default -> 0;
        };
    }

    private Who at(ServerPlayerEntity p, int entityId, Entity[] out) {
        Entity e = p.getServerWorld().getEntityById(entityId);
        if (e == null || !vendor(e) || e.squaredDistanceTo(p) > 8 * 8) return null;
        out[0] = e;
        return who(e);
    }

    /** Buys qty of offer index (as many as are left, if fewer). */
    public void buy(ServerPlayerEntity p, int entityId, int index, int qty) {
        ServerWorld w = p.getServerWorld();
        Entity[] ent = new Entity[1];
        Who who = at(p, entityId, ent);
        if (who == null) return;
        Entity e = ent[0];
        List<Offer> st = stock(w, who.town(), who.kind());
        if (index < 0 || index >= st.size()) return;
        Map<Integer, Integer> gone = sold.computeIfAbsent(key(w, who), k -> new HashMap<>());
        Offer o = st.get(index);
        int left = o.units() - gone.getOrDefault(index, 0);
        int n = Math.max(1, Math.min(qty, left));
        if (left <= 0) return;
        long cost = o.each() * n;
        if (!AotRpg.WALLET.spendMarks(p, cost)) {
            Notify.toast(p, Text.literal("Not enough Marks").formatted(Formatting.RED), Text.literal("That's " + String.format("%,d", cost) + " Marks"), 0xC0463A, null, null);
            return;
        }
        gone.merge(index, n, Integer::sum);
        int give = n;
        while (give > 0) {
            ItemStack s = o.stack().copy();
            int k = Math.min(give, s.getMaxCount());
            s.setCount(k);
            give -= k;
            p.getInventory().offerOrDrop(s);
        }
        p.playSoundToPlayer(net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_TRADE, net.minecraft.sound.SoundCategory.PLAYERS, 0.7f, 1f);
        p.playSoundToPlayer(net.minecraft.sound.SoundEvents.BLOCK_CHAIN_PLACE, net.minecraft.sound.SoundCategory.PLAYERS, 0.5f, 1.6f);
        if (who.kind() == Kind.STRANGER) {
            p.sendMessage(Text.literal("\"Didn't get it from me.\"").formatted(Formatting.DARK_GRAY, Formatting.ITALIC), true);
        }
        // Everyone at this stall sees it go.
        for (ServerPlayerEntity o2 : w.getPlayers()) if (o2.squaredDistanceTo(e) < 12 * 12) send(o2, e, who, false);
        if (sold.size() > 500) sold.keySet().removeIf(k -> !k.endsWith("/" + day(w)));
    }

    /** Sells qty of what's at this address (inventory or satchel) to the trader. */
    public void sell(ServerPlayerEntity p, int entityId, int addr, int qty) {
        ServerWorld w = p.getServerWorld();
        Entity[] ent = new Entity[1];
        Who who = at(p, entityId, ent);
        if (who == null || addr < 9) return;
        ItemStack s = AotRpg.SATCHEL.at(p, addr);
        long each = sellEach(who.kind(), s, stock(w, who.town(), who.kind()));
        if (each <= 0) return;
        int n = Math.max(1, Math.min(qty, s.getCount()));
        ItemStack rest = s.copy();
        rest.decrement(n);
        AotRpg.SATCHEL.set(p, addr, rest);
        AotRpg.SATCHEL.save(p.getUuid());
        AotRpg.WALLET.addMarks(p, each * n, "sold to " + who.kind().title);
        p.playSoundToPlayer(net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_YES, net.minecraft.sound.SoundCategory.PLAYERS, 0.6f, 1f);
        p.playSoundToPlayer(net.minecraft.sound.SoundEvents.BLOCK_CHAIN_PLACE, net.minecraft.sound.SoundCategory.PLAYERS, 0.5f, 1.3f);
        AotRpg.SATCHEL.send(p, false);
        send(p, ent[0], who, false);
    }
}
