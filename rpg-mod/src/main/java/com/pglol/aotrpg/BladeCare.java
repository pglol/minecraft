package com.pglol.aotrpg;

import com.mojang.serialization.Codec;
import net.minecraft.component.Component;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.AbstractNbtNumber;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
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
    private record Seen(net.minecraft.item.Item item, NbtCompound gear, int value) { }

    private final Map<UUID, Seen[]> seen = new HashMap<>();
    /** This session: wear seen on each player's blades, and how much of it temper took back (for /bladecheck). */
    private final Map<UUID, Long> worn = new HashMap<>(), refunded = new HashMap<>();

    public String session(UUID id) {
        return "this session: " + worn.getOrDefault(id, 0L) + " wear seen, " + refunded.getOrDefault(id, 0L) + " taken back by temper";
    }

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

    /**
     * Where a grip keeps its blade wear: a path of NBT keys to the number, or null. Danny's grips
     * count wear up in "BladeDamage" (higher is more worn); other grips may keep a durability that
     * counts down. The damage count is preferred when both are there.
     */
    private static List<String> path(NbtCompound n, List<String> prefix) {
        List<String> dmg = pathFor(n, prefix, k -> k.contains("bladedamage") || (k.contains("blade") && k.contains("damage")));
        return dmg != null ? dmg : pathFor(n, prefix, k -> k.contains("durab") && !k.contains("max"));
    }

    private static List<String> pathFor(NbtCompound n, List<String> prefix, java.util.function.Predicate<String> want) {
        for (String k : n.getKeys()) {
            NbtElement e = n.get(k);
            String lk = k.toLowerCase(java.util.Locale.ROOT);
            if (e instanceof AbstractNbtNumber && want.test(lk)) {
                List<String> p = new ArrayList<>(prefix);
                p.add(k);
                return p;
            }
            if (e instanceof NbtCompound c && !k.equals("aot_gear")) {
                List<String> p = new ArrayList<>(prefix);
                p.add(k);
                List<String> found = pathFor(c, p, want);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** +1 when the number is how much is left, -1 when it's how much is worn (so "health" is always sign * value). */
    private static int sign(List<String> p) {
        return p.get(p.size() - 1).toLowerCase(java.util.Locale.ROOT).contains("damage") ? -1 : 1;
    }

    private interface Wear {
        int get();

        void set(int value);
    }

    private static Wear find(ItemStack s) {
        // 1. Custom NBT.
        NbtComponent cd = s.get(DataComponentTypes.CUSTOM_DATA);
        if (cd != null) {
            NbtCompound n = cd.copyNbt();
            List<String> p = path(n, new ArrayList<>());
            if (p != null) {
                int sg = sign(p);
                return new Wear() {
                    public int get() {
                        return sg * ((AbstractNbtNumber) at(n, p).get(p.get(p.size() - 1))).intValue();
                    }

                    public void set(int value) {
                        NbtCompound fresh = s.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
                        NbtCompound a = at(fresh, p);
                        String k = p.get(p.size() - 1);
                        a.put(k, like(a.get(k), Math.max(0, sg * value)));
                        s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(fresh));
                    }
                };
            }
        }
        // 2. A modded data component.
        for (Component<?> comp : s.getComponents()) {
            Identifier id = Registries.DATA_COMPONENT_TYPE.getId(comp.type());
            if (id == null || id.getNamespace().equals("minecraft")) continue;
            Wear w = fromComponent(s, comp.type(), comp.value(), id.getPath().toLowerCase(java.util.Locale.ROOT));
            if (w != null) return w;
        }
        // 3. Plain vanilla damage.
        if (s.isDamageable()) {
            return new Wear() {
                public int get() {
                    return s.getMaxDamage() - s.getDamage();
                }

                public void set(int value) {
                    s.setDamage(Math.max(0, s.getMaxDamage() - value));
                }
            };
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> Wear fromComponent(ItemStack s, ComponentType<T> type, Object value, String name) {
        Codec<T> codec = type.getCodec();
        if (codec == null) return null;
        NbtElement e = codec.encodeStart(NbtOps.INSTANCE, (T) value).result().orElse(null);
        if (e == null) return null;
        if (e instanceof AbstractNbtNumber num) {
            if (!name.contains("durab") || name.contains("max")) return null;
            return new Wear() {
                public int get() {
                    return num.intValue();
                }

                public void set(int v) {
                    codec.parse(NbtOps.INSTANCE, like(num, v)).result().ifPresent(x -> s.set(type, x));
                }
            };
        }
        if (!(e instanceof NbtCompound n)) return null;
        List<String> p = path(n, new ArrayList<>());
        if (p == null) return null;
        int sg = sign(p);
        return new Wear() {
            public int get() {
                return sg * ((AbstractNbtNumber) at(n, p).get(p.get(p.size() - 1))).intValue();
            }

            public void set(int v) {
                NbtCompound fresh = n.copy();
                NbtCompound a = at(fresh, p);
                String k = p.get(p.size() - 1);
                a.put(k, like(a.get(k), Math.max(0, sg * v)));
                codec.parse(NbtOps.INSTANCE, fresh).result().ifPresent(x -> s.set(type, x));
            }
        };
    }

    private static NbtCompound at(NbtCompound n, List<String> p) {
        NbtCompound a = n;
        for (int i = 0; i < p.size() - 1; i++) a = a.getCompound(p.get(i));
        return a;
    }

    /** A number of the same NBT kind as the one it replaces. */
    private static NbtElement like(NbtElement old, int v) {
        if (old instanceof net.minecraft.nbt.NbtShort) return net.minecraft.nbt.NbtShort.of((short) v);
        if (old instanceof net.minecraft.nbt.NbtByte) return net.minecraft.nbt.NbtByte.of((byte) v);
        if (old instanceof net.minecraft.nbt.NbtLong) return net.minecraft.nbt.NbtLong.of(v);
        if (old instanceof net.minecraft.nbt.NbtFloat) return net.minecraft.nbt.NbtFloat.of(v);
        if (old instanceof net.minecraft.nbt.NbtDouble) return net.minecraft.nbt.NbtDouble.of(v);
        return NbtInt.of(v);
    }

    /** Every couple of ticks: wear on held blades may be undone by good steel. */
    public void tick(ServerPlayerEntity p) {
        Seen[] last = seen.computeIfAbsent(p.getUuid(), k -> new Seen[2]);
        Hand[] hands = {Hand.MAIN_HAND, Hand.OFF_HAND};
        for (int i = 0; i < 2; i++) {
            ItemStack s = p.getStackInHand(hands[i]);
            double t = Loadout.isGrip(s) && !AotItems.isApgGun(s) ? temper(s) : 0;
            Wear w = t > 0 ? find(s) : null;
            if (w == null) {
                last[i] = null;
                continue;
            }
            int v = w.get();
            // The same blade even if the grip handed us a fresh copy of the stack: same item, same gear roll.
            Seen prev = last[i];
            boolean same = prev != null && prev.item() == s.getItem() && prev.gear().equals(Gear.data(s));
            int lost = same ? prev.value() - v : 0;
            // Wear since last look: temper takes back its share of it. (Danny's cuts can add several
            // points of BladeDamage at once, so this isn't limited to a point at a time; only a huge
            // jump, a different blade swapped in, is left alone.)
            if (lost > 0 && lost <= 200) {
                double share = lost * t;
                int back = (int) share + (p.getRandom().nextDouble() < share - (int) share ? 1 : 0);
                if (back > 0) {
                    v += back;
                    w.set(v);
                    refunded.merge(p.getUuid(), (long) back, Long::sum);
                }
                worn.merge(p.getUuid(), (long) lost, Long::sum);
            }
            last[i] = new Seen(s.getItem(), Gear.data(s), v);
        }
    }

    /** For /bladecheck: where this blade's wear is read from, its value and its temper. */
    public static String describe(ItemStack s) {
        if (s.isEmpty()) return "Nothing in hand.";
        StringBuilder b = new StringBuilder(Registries.ITEM.getId(s.getItem()).toString());
        b.append(" | grip: ").append(Loadout.isGrip(s)).append(" | temper: ").append(Math.round(temper(s) * 100)).append('%');
        Wear w = find(s);
        b.append(" | wear: ").append(w == null ? "NOT FOUND" : (w.get() < 0 ? "BladeDamage " + (-w.get()) : "durability " + w.get()));
        b.append(" | damageable: ").append(s.isDamageable());
        NbtComponent cd = s.get(DataComponentTypes.CUSTOM_DATA);
        if (cd != null) b.append(" | nbt keys: ").append(cd.copyNbt().getKeys());
        b.append(" | components:");
        for (Component<?> comp : s.getComponents()) {
            Identifier id = Registries.DATA_COMPONENT_TYPE.getId(comp.type());
            if (id != null && !id.getNamespace().equals("minecraft")) b.append(' ').append(id).append('=').append(String.valueOf(comp.value()));
        }
        return b.toString();
    }

    public void forget(UUID id) {
        seen.remove(id);
    }
}
