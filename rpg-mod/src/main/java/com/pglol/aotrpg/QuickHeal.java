package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The heal key: uses the best healing item from the inventory or satchel instantly, on a short
 * cooldown. Heals: healing potions, (enchanted) golden apples, medicinal herbs, cooked meals.
 */
public final class QuickHeal {
    public static final int COOLDOWN = 20 * 8;
    private final Map<UUID, Long> ready = new HashMap<>();
    private final Map<UUID, String> lastSent = new HashMap<>();

    /** Higher is better; 0 = not a heal. */
    public static int rank(ItemStack s) {
        if (s.isEmpty()) return 0;
        PotionContentsComponent pc = s.get(DataComponentTypes.POTION_CONTENTS);
        if (pc != null && s.isOf(Items.POTION)) {
            for (StatusEffectInstance e : pc.getEffects()) {
                if (e.getEffectType() == StatusEffects.INSTANT_HEALTH || e.getEffectType() == StatusEffects.REGENERATION) return 50;
            }
        }
        if (s.isOf(Items.ENCHANTED_GOLDEN_APPLE)) return 45;
        if (s.isOf(Items.GOLDEN_APPLE)) return 40;
        if (s.isOf(Items.GLISTERING_MELON_SLICE)) return 30;
        var cd = s.get(DataComponentTypes.CUSTOM_DATA);
        if (Provisions.isCooked(s)) return 20;
        // Provisions: bread, cooked meat, fish... Raw ingredients are for cooking, not healing.
        var food = s.get(DataComponentTypes.FOOD);
        if (food != null && Provisions.isProvision(s)) return 5 + Math.min(10, food.nutrition());
        return 0;
    }

    private record Found(Inventory inv, int slot, ItemStack stack, int count) { }

    private Found best(ServerPlayerEntity p) {
        Found best = null;
        int bestRank = 0, total = 0;
        Inventory[] invs = {p.getInventory(), AotRpg.SATCHEL.get(p.getUuid())};
        // What you put in the heal slot (5) comes first.
        ItemStack slot = p.getInventory().main.get(Loadout.HEAL_SLOT);
        if (rank(slot) > 0) {
            best = new Found(p.getInventory(), Loadout.HEAL_SLOT, slot, 0);
            bestRank = Integer.MAX_VALUE;
        }
        for (Inventory inv : invs) {
            for (int i = 0; i < inv.size(); i++) {
                ItemStack s = inv.getStack(i);
                int r = rank(s);
                if (r == 0) continue;
                if (r > bestRank) {
                    bestRank = r;
                    best = new Found(inv, i, s, 0);
                }
            }
        }
        if (best == null) return null;
        for (Inventory inv : invs) {
            for (int i = 0; i < inv.size(); i++) if (ItemStack.areItemsAndComponentsEqual(inv.getStack(i), best.stack)) total += inv.getStack(i).getCount();
        }
        return new Found(best.inv, best.slot, best.stack, total);
    }

    public void use(ServerPlayerEntity p) {
        long now = p.getServerWorld().getTime();
        Long r = ready.get(p.getUuid());
        if (r != null && now < r) {
            p.sendMessage(Text.literal("Heal ready in " + (int) Math.ceil((r - now) / 20.0) + "s").formatted(Formatting.GRAY), true);
            return;
        }
        Found f = best(p);
        if (f == null) {
            p.sendMessage(Text.literal("Nothing to heal with. Carry food, meals or potions.").formatted(Formatting.RED), true);
            return;
        }
        ItemStack one = f.stack.copyWithCount(1);
        if (f.stack.isOf(Items.GLISTERING_MELON_SLICE)) {
            p.heal(6);
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 100, 0));
        } else {
            // Eats or drinks it instantly: food, effects and all. Food also mends a little at once.
            var food = one.get(DataComponentTypes.FOOD);
            one.getItem().finishUsing(one, p.getWorld(), p);
            if (food != null && !one.isOf(Items.GOLDEN_APPLE) && !one.isOf(Items.ENCHANTED_GOLDEN_APPLE)) p.heal(Math.min(8, food.nutrition()));
        }
        f.stack.decrement(1);
        f.inv.markDirty();
        ready.put(p.getUuid(), now + COOLDOWN);
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.5f, 1.3f);
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.6f, 1.6f);
        sync(p, true);
    }

    /** Tells the HUD which heal is next, how many are left, and the cooldown. */
    public void sync(ServerPlayerEntity p, boolean force) {
        if (!ServerPlayNetworking.canSend(p, Net.HealInfo.ID)) return;
        Found f = best(p);
        long now = p.getServerWorld().getTime();
        Long r = ready.get(p.getUuid());
        int cd = r == null ? 0 : (int) Math.max(0, r - now);
        String item = f == null ? "" : Registries.ITEM.getId(f.stack.getItem()).toString();
        int count = f == null ? 0 : f.count;
        String key = item + count + (cd > 0);
        if (!force && key.equals(lastSent.get(p.getUuid()))) return;
        lastSent.put(p.getUuid(), key);
        ServerPlayNetworking.send(p, new Net.HealInfo(item, count, cd, COOLDOWN));
    }

    public void forget(ServerPlayerEntity p) {
        lastSent.remove(p.getUuid());
    }
}
