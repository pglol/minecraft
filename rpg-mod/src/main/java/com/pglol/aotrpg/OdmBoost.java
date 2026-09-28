package com.pglol.aotrpg;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.component.Component;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.AbstractNbtNumber;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * ODM double jumps. With the gear on and gas in it, a second press of jump in the air fires the
 * gas: straight up just after leaving the ground, a flip if a direction is held then, and later a
 * dash wherever you look (leaned by the keys you hold). The client moves you at once so it feels
 * immediate; the server checks the gear, the gas and the cooldown, spends the gas, and shows the
 * burst (and the flip) to everyone around.
 */
public final class OdmBoost {
    public static final int COOLDOWN = 20, PER_AIR = 2;
    public static final int DASH = 0, UP = 1, FLIP = 2;
    /** A full tank on Danny's gear, when it doesn't say its own maximum. */
    public static final double FULL = 500;

    private static final class St {
        long lastAt = -1000;
        int inAir;
        boolean gear, refilling;
        float gas = -2;
    }

    private final Map<UUID, St> states = new HashMap<>();
    /** Testing: players whose gear never runs dry (ops toggle it), or everyone at once. */
    private final java.util.Set<UUID> infinite = new java.util.HashSet<>();
    private boolean infiniteAll;

    public boolean toggleInfinite(UUID id) {
        if (!infinite.remove(id)) {
            infinite.add(id);
            return true;
        }
        return false;
    }

    public boolean toggleInfiniteAll() {
        infiniteAll = !infiniteAll;
        return infiniteAll;
    }

    private boolean infinite(ServerPlayerEntity p) {
        return infiniteAll || infinite.contains(p.getUuid());
    }

    /** Towns, villages, camps and HQs are where the gear gets refilled. */
    public static boolean refuelPoint(ServerPlayerEntity p) {
        return AotRpg.PLACES.nearest(p.getX(), p.getZ(), 70, "town", "village", "city", "capital", "camp") != null;
    }

    /** Tops the gear up by `amount` (or to full); true if anything went in. */
    /** Wearing ODM gear? */
    public static boolean gearOn(ServerPlayerEntity p) {
        return !gear(p).isEmpty();
    }

    /** Refuels a fraction of the tank (Gas Rig, Airlift, the Gas Refueler). False if full or unreadable. */
    public static boolean refuel(ServerPlayerEntity p, double fraction) {
        ItemStack g = gear(p);
        if (g.isEmpty()) return false;
        Num max = find(g, GAS_MAX);
        double m = max != null && max.value() > 0 ? max.value() : FULL;
        return refill(p, m * fraction);
    }

    private static boolean refill(ServerPlayerEntity p, double amount) {
        ItemStack g = gear(p);
        if (g.isEmpty()) return false;
        Num v = find(g, GAS);
        if (v == null) return false;
        Num max = find(g, GAS_MAX);
        double m = max != null && max.value() > 0 ? max.value() : FULL;
        if (v.value() >= m) return false;
        v.set().accept(Math.min(m, v.value() + amount));
        return true;
    }

    // ------------------------------------------------------------------ the gear and its gas

    /** The ODM harness you're wearing, or empty. */
    public static ItemStack gear(ServerPlayerEntity p) {
        for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.FEET}) {
            ItemStack st = p.getEquippedStack(s);
            if (st.isEmpty()) continue;
            Identifier id = Registries.ITEM.getId(st.getItem());
            String path = id.getPath().toLowerCase(Locale.ROOT);
            if (path.contains("boot")) continue;
            for (String k : AotItems.ODM) if (path.contains(k)) return st;
        }
        return ItemStack.EMPTY;
    }

    /** A number kept on an item (custom NBT or a modded component) whose key matches. */
    private record Num(double value, java.util.function.DoubleConsumer set) { }

    private static final Predicate<String> GAS = k -> k.contains("gas") && !k.contains("max") && !k.contains("cap");
    private static final Predicate<String> GAS_MAX = k -> k.contains("gas") && (k.contains("max") || k.contains("cap"));

    private static String path(NbtCompound n, Predicate<String> want, java.util.List<String> out) {
        for (String k : n.getKeys()) {
            NbtElement e = n.get(k);
            String lk = k.toLowerCase(Locale.ROOT);
            if (e instanceof AbstractNbtNumber && want.test(lk)) {
                out.add(k);
                return k;
            }
            if (e instanceof NbtCompound c && !k.equals("aot_gear")) {
                out.add(k);
                if (path(c, want, out) != null) return k;
                out.remove(out.size() - 1);
            }
        }
        return null;
    }

    private static NbtCompound at(NbtCompound n, java.util.List<String> p) {
        NbtCompound a = n;
        for (int i = 0; i < p.size() - 1; i++) a = a.getCompound(p.get(i));
        return a;
    }

    private static NbtElement like(NbtElement old, double v) {
        if (old instanceof net.minecraft.nbt.NbtFloat) return net.minecraft.nbt.NbtFloat.of((float) v);
        if (old instanceof net.minecraft.nbt.NbtDouble) return net.minecraft.nbt.NbtDouble.of(v);
        if (old instanceof net.minecraft.nbt.NbtLong) return net.minecraft.nbt.NbtLong.of(Math.round(v));
        if (old instanceof net.minecraft.nbt.NbtShort) return net.minecraft.nbt.NbtShort.of((short) Math.round(v));
        return net.minecraft.nbt.NbtInt.of((int) Math.round(v));
    }

    private static Num find(ItemStack s, Predicate<String> want) {
        NbtComponent cd = s.get(DataComponentTypes.CUSTOM_DATA);
        if (cd != null) {
            NbtCompound n = cd.copyNbt();
            java.util.List<String> p = new java.util.ArrayList<>();
            if (path(n, want, p) != null) {
                String k = p.get(p.size() - 1);
                double v = ((AbstractNbtNumber) at(n, p).get(k)).doubleValue();
                return new Num(v, nv -> {
                    NbtCompound fresh = s.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
                    NbtCompound a = at(fresh, p);
                    a.put(k, like(a.get(k), nv));
                    s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(fresh));
                });
            }
        }
        for (Component<?> comp : s.getComponents()) {
            Identifier id = Registries.DATA_COMPONENT_TYPE.getId(comp.type());
            if (id == null || id.getNamespace().equals("minecraft")) continue;
            Num n = fromComponent(s, comp.type(), comp.value(), id.getPath().toLowerCase(Locale.ROOT), want);
            if (n != null) return n;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> Num fromComponent(ItemStack s, ComponentType<T> type, Object value, String name, Predicate<String> want) {
        Codec<T> codec = type.getCodec();
        if (codec == null) return null;
        NbtElement e = codec.encodeStart(NbtOps.INSTANCE, (T) value).result().orElse(null);
        if (e instanceof AbstractNbtNumber num) {
            if (!want.test(name)) return null;
            return new Num(num.doubleValue(), nv -> codec.parse(NbtOps.INSTANCE, like(num, nv)).result().ifPresent(x -> s.set(type, x)));
        }
        if (!(e instanceof NbtCompound n)) return null;
        java.util.List<String> p = new java.util.ArrayList<>();
        if (path(n, want, p) == null) return null;
        String k = p.get(p.size() - 1);
        double v = ((AbstractNbtNumber) at(n, p).get(k)).doubleValue();
        return new Num(v, nv -> {
            NbtCompound fresh = n.copy();
            NbtCompound a = at(fresh, p);
            a.put(k, like(a.get(k), nv));
            codec.parse(NbtOps.INSTANCE, fresh).result().ifPresent(x -> s.set(type, x));
        });
    }

    /** Gas left as a fraction (0..1), or -1 when the gear doesn't say. */
    public static float gas(ServerPlayerEntity p) {
        ItemStack g = gear(p);
        if (g.isEmpty()) return 0;
        Num v = find(g, GAS);
        if (v == null) return -1;
        Num max = find(g, GAS_MAX);
        double m = max != null && max.value() > 0 ? max.value() : Math.max(v.value(), FULL);
        return (float) Math.max(0, Math.min(1, v.value() / m));
    }

    /** Spends one burst of gas; false when there's none to spend. */
    private static boolean spend(ServerPlayerEntity p) {
        ItemStack g = gear(p);
        if (g.isEmpty()) return false;
        Num v = find(g, GAS);
        if (v == null) return true; // the gear keeps no count we can read: the burst is free
        if (v.value() <= 0) return false;
        Num max = find(g, GAS_MAX);
        double m = max != null && max.value() > 0 ? max.value() : Math.max(v.value(), FULL);
        // Efficient Valves (Engineer): every burst costs a fifth less.
        double cost = Math.max(1, m * 0.05) * (AotRpg.PROFILES.get(p.getUuid()).has(Skill.ENG_VALVES) ? 0.8 : 1);
        v.set().accept(Math.max(0, v.value() - cost));
        return true;
    }

    // ------------------------------------------------------------------ the boost

    public void boost(ServerPlayerEntity p, Net.OdmJump j) {
        if (AotRpg.DUELS.blocksOdm(p)) return;
        St s = states.computeIfAbsent(p.getUuid(), k -> new St());
        long now = p.getServerWorld().getTime();
        // Not checking the ground here: right after take-off the server may still think you are on it.
        if (p.hasVehicle() || AotRpg.DOWNED.isDowned(p) || Grab.grabbed(p)) return;
        // A little slack for lag between the client's timing and ours.
        if (now - s.lastAt < COOLDOWN - 4 || s.inAir >= PER_AIR) return;
        if (gear(p).isEmpty()) {
            p.sendMessage(Text.literal("You need ODM gear on to do that.").formatted(Formatting.GRAY), true);
            return;
        }
        if (!infinite(p) && !AotRpg.CLASSES.freeGas(p) && !spend(p)) {
            p.sendMessage(Text.literal("Out of gas.").formatted(Formatting.RED), true);
            sync(p, s, true);
            return;
        }
        s.lastAt = now;
        s.inAir++;
        p.fallDistance = 0;
        var w = p.getServerWorld();
        w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.8, p.getZ(), 14, 0.3, 0.25, 0.3, 0.06);
        w.spawnParticles(ParticleTypes.POOF, p.getX(), p.getY() + 0.6, p.getZ(), 6, 0.2, 0.1, 0.2, 0.02);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), SoundCategory.PLAYERS, 0.7f, 1.3f + p.getRandom().nextFloat() * 0.2f);
        Net.OdmMove move = new Net.OdmMove(p.getId(), j.kind(), j.yaw());
        for (ServerPlayerEntity o : PlayerLookup.tracking(p)) {
            if (o != p && ServerPlayNetworking.canSend(o, Net.OdmMove.ID)) ServerPlayNetworking.send(o, move);
        }
        sync(p, s, true);
    }

    /** Every few ticks: the gear and gas your client should know about, and landing resets the air count. */
    public void tick(ServerPlayerEntity p, int ticks) {
        St s = states.computeIfAbsent(p.getUuid(), k -> new St());
        if (p.isOnGround() || p.isTouchingWater() || p.hasVehicle()) s.inAir = 0;
        if (ticks % 20 == 11) {
            if (infinite(p)) refill(p, 1e9);
            else if (refuelPoint(p) && refill(p, FULL * 0.05)) {
                // Refilling at a town, camp or HQ: a quiet hiss and a note the first time.
                if (!s.refilling) p.sendMessage(net.minecraft.text.Text.literal("Refilling gas...").formatted(Formatting.AQUA), true);
                s.refilling = true;
            } else {
                s.refilling = false;
            }
        }
        if (ticks % 10 == 0) sync(p, s, false);
    }

    private void sync(ServerPlayerEntity p, St s, boolean force) {
        boolean g = !gear(p).isEmpty();
        float gas = g ? gas(p) : 0;
        if (!force && g == s.gear && Math.abs(gas - s.gas) < 0.01f) return;
        s.gear = g;
        s.gas = gas;
        if (ServerPlayNetworking.canSend(p, Net.OdmState.ID)) ServerPlayNetworking.send(p, new Net.OdmState(g, gas));
    }

    /** For /odmcheck: every number the worn gear carries (custom NBT and modded components), and what we make of the gas. */
    public static List<String> describe(ServerPlayerEntity p) {
        List<String> out = new java.util.ArrayList<>();
        ItemStack g = gear(p);
        if (g.isEmpty()) {
            out.add("No ODM gear worn (looked at legs, chest and feet for an item named odm/maneuver/3dmg).");
            return out;
        }
        out.add("Gear: " + Registries.ITEM.getId(g.getItem()) + (g.isDamageable() ? "  damage " + g.getDamage() + "/" + g.getMaxDamage() : ""));
        NbtComponent cd = g.get(DataComponentTypes.CUSTOM_DATA);
        if (cd != null) numbers(cd.copyNbt(), "nbt", out);
        for (Component<?> comp : g.getComponents()) {
            Identifier id = Registries.DATA_COMPONENT_TYPE.getId(comp.type());
            if (id == null || id.getNamespace().equals("minecraft")) continue;
            out.add("component " + id + " = " + comp.value());
        }
        Num v = find(g, GAS), max = find(g, GAS_MAX);
        out.add("Gas read as: " + (v == null ? "NOT FOUND (boosts are free)" : v.value() + " / " + (max != null ? max.value() + " (from the gear)" : FULL + " (assumed)"))
            + "  ->  " + Math.round(gas(p) * 100) + "%");
        return out;
    }

    private static void numbers(NbtCompound n, String at, List<String> out) {
        for (String k : n.getKeys()) {
            NbtElement e = n.get(k);
            if (e instanceof AbstractNbtNumber num) out.add(at + "." + k + " = " + num.doubleValue());
            else if (e instanceof NbtCompound c) numbers(c, at + "." + k, out);
        }
    }

    public void forget(UUID id) {
        states.remove(id);
    }
}
