package com.pglol.aotrpg;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Marks dropped as coins: slain titans scatter a few, and walking near them sweeps them into
 * your purse (they are never picked up as items, so they can't be stored or duplicated).
 */
public final class Coins {
    private static final String KEY = "aot_marks";
    /** Recent pickups, gathered into one "+N Marks" line. */
    private final Map<UUID, long[]> tally = new HashMap<>();

    public static void drop(LivingEntity from, long total) {
        if (total <= 0) return;
        int piles = (int) Math.max(1, Math.min(6, total / 4));
        long each = total / piles, rest = total - each * piles;
        for (int i = 0; i < piles; i++) {
            long n = each + (i == 0 ? rest : 0);
            ItemStack s = new ItemStack(n >= 25 ? Items.GOLD_INGOT : Items.GOLD_NUGGET);
            NbtCompound tag = new NbtCompound();
            tag.putLong(KEY, n);
            s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
            s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(n + " Marks").formatted(Formatting.GOLD));
            ItemEntity e = new ItemEntity(from.getWorld(), from.getX(), from.getY() + 0.5, from.getZ(), s,
                (from.getRandom().nextDouble() - 0.5) * 0.5, 0.35, (from.getRandom().nextDouble() - 0.5) * 0.5);
            e.setPickupDelayInfinite();
            e.setGlowing(true);
            from.getWorld().spawnEntity(e);
        }
    }

    private static long value(ItemEntity e) {
        NbtComponent c = e.getStack().get(DataComponentTypes.CUSTOM_DATA);
        return c == null ? 0 : c.copyNbt().getLong(KEY);
    }

    /** Every few ticks: coins drift to nearby players and are collected on touch. */
    public void tick(ServerPlayerEntity p, int ticks) {
        if (ticks % 3 != 0 || p.isSpectator() || !AotRpg.PROFILES.get(p.getUuid()).created) return;
        for (ItemEntity e : p.getServerWorld().getEntitiesByClass(ItemEntity.class, p.getBoundingBox().expand(5), x -> value(x) > 0)) {
            if (e.age > 20 * 120) {
                e.discard();
                continue;
            }
            if (e.age < 12) continue;
            double d = e.squaredDistanceTo(p.getX(), p.getY() + 0.6, p.getZ());
            if (d < 1.8 * 1.8) {
                long n = value(e);
                e.discard();
                long[] t = tally.computeIfAbsent(p.getUuid(), k -> new long[2]);
                t[0] += n;
                t[1] = ticks;
                p.getServerWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS,
                    0.35f, 1.4f + p.getRandom().nextFloat() * 0.4f);
            } else {
                Vec3d pull = p.getPos().add(0, 0.6, 0).subtract(e.getPos()).normalize().multiply(0.28);
                e.setVelocity(e.getVelocity().multiply(0.6).add(pull));
                e.velocityModified = true;
            }
        }
        long[] t = tally.get(p.getUuid());
        if (t != null && t[0] > 0 && ticks - t[1] >= 12) {
            AotRpg.WALLET.earn(p, t[0], null);
            Notify.toast(p, Text.literal("+ ").formatted(Formatting.GREEN).append(Wallet.marks(Math.round(t[0] * (1 + Roles.bonus(AotRpg.PROFILES.get(p.getUuid())))))),
                Text.literal("Titan bounty"), 0xE0B96A, "minecraft:gold_nugget", "coins");
            tally.remove(p.getUuid());
        }
    }

    public void forget(UUID id) {
        tally.remove(id);
    }
}
