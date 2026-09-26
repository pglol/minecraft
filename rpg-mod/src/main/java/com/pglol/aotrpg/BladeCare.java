package com.pglol.aotrpg;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.AbstractNbtNumber;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtInt;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Better blades last longer. Danny's grips keep their blade wear on the item; whenever a cut
 * wears it down, finer steel (rarer gear, and the Tempered Steel perk) has a chance to shrug the
 * wear off, so a Legendary blade lasts about three times as long as a plain one.
 */
public final class BladeCare {
    private record Seen(ItemStack stack, int value) { }

    private final Map<UUID, Seen[]> seen = new HashMap<>();

    /** Chance each point of wear is shrugged off. */
    public static double temper(ItemStack s) {
        if (!Gear.isGear(s)) return 0;
        var g = Gear.data(s);
        double t = switch (g.getString("rarity")) {
            case "UNCOMMON" -> 0.2;
            case "RARE" -> 0.35;
            case "EPIC" -> 0.5;
            case "LEGENDARY" -> 0.65;
            default -> 0;
        };
        if (g.getBoolean("tempered")) t += 0.25;
        return Math.min(0.85, t);
    }

    /** Where a grip keeps its blade wear: a path of NBT keys to the durability number, or null. */
    private static List<String> path(NbtCompound n, List<String> prefix) {
        for (String k : n.getKeys()) {
            NbtElement e = n.get(k);
            String lk = k.toLowerCase(java.util.Locale.ROOT);
            if (e instanceof AbstractNbtNumber && lk.contains("durab") && !lk.contains("max")) {
                List<String> p = new ArrayList<>(prefix);
                p.add(k);
                return p;
            }
            if (e instanceof NbtCompound c && !k.equals("aot_gear")) {
                List<String> p = new ArrayList<>(prefix);
                p.add(k);
                List<String> found = path(c, p);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int read(ItemStack s) {
        if (s.isDamageable()) return s.getMaxDamage() - s.getDamage();
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        if (c == null) return -1;
        NbtCompound n = c.copyNbt();
        List<String> p = path(n, new ArrayList<>());
        if (p == null) return -1;
        NbtCompound at = n;
        for (int i = 0; i < p.size() - 1; i++) at = at.getCompound(p.get(i));
        return at.getInt(p.get(p.size() - 1));
    }

    private static void write(ItemStack s, int value) {
        if (s.isDamageable()) {
            s.setDamage(Math.max(0, s.getMaxDamage() - value));
            return;
        }
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        if (c == null) return;
        NbtCompound n = c.copyNbt();
        List<String> p = path(n, new ArrayList<>());
        if (p == null) return;
        NbtCompound at = n;
        for (int i = 0; i < p.size() - 1; i++) at = at.getCompound(p.get(i));
        at.put(p.get(p.size() - 1), NbtInt.of(value));
        s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(n));
    }

    /** Every couple of ticks: wear on held blades may be undone by good steel. */
    public void tick(ServerPlayerEntity p) {
        Seen[] last = seen.computeIfAbsent(p.getUuid(), k -> new Seen[2]);
        Hand[] hands = {Hand.MAIN_HAND, Hand.OFF_HAND};
        for (int i = 0; i < 2; i++) {
            ItemStack s = p.getStackInHand(hands[i]);
            if (!Loadout.isGrip(s) || AotItems.isApgGun(s)) {
                last[i] = null;
                continue;
            }
            int v = read(s);
            if (v < 0) {
                last[i] = null;
                continue;
            }
            Seen prev = last[i];
            if (prev != null && prev.stack() == s && v < prev.value()) {
                double t = temper(s);
                int back = 0;
                for (int k = 0; k < prev.value() - v; k++) if (p.getRandom().nextDouble() < t) back++;
                if (back > 0) {
                    v += back;
                    write(s, v);
                }
            }
            last[i] = new Seen(s, v);
        }
    }

    public void forget(UUID id) {
        seen.remove(id);
    }
}
