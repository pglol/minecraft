package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.FishingRodItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fishing as a quick minigame: when a fish bites and the player reels in, the bite is held
 * while the client plays the REEL minigame. Landing it gives the normal catch plus a bonus
 * for good reeling; losing it lets the fish get away. Skill shortens the wait for a bite.
 */
public final class Fishing {
    /** Implemented by the bobber mixin. */
    public interface Hook {
        int aotrpg$bite();

        void aotrpg$holdBite();

        int aotrpg$wait();

        void aotrpg$setWait(int ticks);
    }

    private record Pending(FishingBobberEntity bobber, long since) { }

    private final Map<UUID, Pending> pending = new HashMap<>();
    /** Bobbers allowed through to the vanilla catch. */
    private FishingBobberEntity approved;

    /** Called when the rod is reeled in. True cancels the vanilla reel (the minigame starts instead). */
    public boolean intercept(FishingBobberEntity b) {
        if (b == approved || !(b.getPlayerOwner() instanceof ServerPlayerEntity p) || !(b instanceof Hook h)) return false;
        Pending cur = pending.get(p.getUuid());
        if (cur != null && cur.bobber == b) return true;
        if (h.aotrpg$bite() <= 0) return false;
        int lvl = Lifestyle.level(AotRpg.PROFILES.get(p.getUuid()), Lifestyle.FISHING);
        float difficulty = Math.max(0.1f, 0.55f - lvl * 0.009f + p.getRandom().nextFloat() * 0.25f);
        pending.put(p.getUuid(), new Pending(b, p.getServerWorld().getTime()));
        ServerPlayNetworking.send(p, new Net.FishBite(difficulty));
        return true;
    }

    /** Every bobber tick: hold a bite during the minigame, and let skilled anglers get bites sooner. */
    public void tick(FishingBobberEntity b) {
        if (!(b.getPlayerOwner() instanceof ServerPlayerEntity p) || !(b instanceof Hook h)) return;
        Pending cur = pending.get(p.getUuid());
        if (cur != null && cur.bobber == b) {
            if (p.getServerWorld().getTime() - cur.since > 20 * 40) {
                pending.remove(p.getUuid());
                return;
            }
            h.aotrpg$holdBite();
            return;
        }
        if (h.aotrpg$wait() > 1 && p.age % 20 == 0) {
            int lvl = Lifestyle.level(AotRpg.PROFILES.get(p.getUuid()), Lifestyle.FISHING);
            h.aotrpg$setWait(Math.max(1, h.aotrpg$wait() - lvl));
        }
    }

    public void result(ServerPlayerEntity p, float quality) {
        Pending cur = pending.remove(p.getUuid());
        if (cur == null || cur.bobber.isRemoved()) return;
        FishingBobberEntity b = cur.bobber;
        float q = Float.isNaN(quality) ? 0 : Math.max(0, Math.min(1, quality));
        if (q <= 0) {
            p.sendMessage(Text.literal("It got away...").formatted(Formatting.GRAY), true);
            p.getServerWorld().playSound(null, b.getBlockPos(), SoundEvents.ENTITY_FISHING_BOBBER_SPLASH, SoundCategory.NEUTRAL, 0.6f, 0.7f);
            b.discard();
            Lifestyle.add(p, Lifestyle.FISHING, 2);
            return;
        }
        ItemStack rod = p.getMainHandStack().getItem() instanceof FishingRodItem ? p.getMainHandStack() : p.getOffHandStack();
        approved = b;
        try {
            int dmg = b.use(rod);
            if (rod.getItem() instanceof FishingRodItem) rod.damage(dmg, p, rod == p.getMainHandStack()
                ? net.minecraft.entity.EquipmentSlot.MAINHAND : net.minecraft.entity.EquipmentSlot.OFFHAND);
        } finally {
            approved = null;
        }
        // A clean reel lands something extra for the satchel.
        int extra = 0;
        if (p.getRandom().nextFloat() < q * 0.5f) {
            AotRpg.SATCHEL.add(p, new ItemStack(q > 0.8f && p.getRandom().nextFloat() < 0.4f ? Items.SALMON : Items.COD));
            extra++;
        }
        if (q >= 0.9f && p.getRandom().nextFloat() < 0.25f) AotRpg.WALLET.earn(p, 5 + p.getRandom().nextInt(11), "a fine catch");
        p.sendMessage(Text.literal(q >= 0.85f ? "Perfect catch!" : q >= 0.5f ? "Landed it" : "Barely landed it")
            .formatted(Formatting.AQUA).append(extra > 0 ? Text.literal("  +1 fish to your satchel").formatted(Formatting.GRAY) : Text.empty()), true);
        Lifestyle.add(p, Lifestyle.FISHING, Math.round(8 + 22 * q));
        AotRpg.TASKS.count(p, Tasks.FISH, 1);
    }

    public void forget(UUID id) {
        pending.remove(id);
    }
}
