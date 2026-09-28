package com.pglol.aotrpg;

import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BedBlock;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * No real sleep on this server (the night can't skip for one person), so a bed is for counting
 * sheep instead: they hop a fence, you count each one as it clears it. Some of them are titans in
 * a wool coat. Count well and you wake Well Rested; count a titan and it counts you back.
 */
public final class Dreams {
    private static final long REST_COOLDOWN_MS = 10 * 60_000;
    private final Map<UUID, Long> rested = new HashMap<>(), opened = new HashMap<>();

    public void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(world.getBlockState(hit.getBlockPos()).getBlock() instanceof BedBlock)) return ActionResult.PASS;
            if (world.isClient) return ActionResult.SUCCESS;
            if (player instanceof ServerPlayerEntity p && ServerPlayNetworking.canSend(p, Net.Dream.ID) && !p.isSneaking()) {
                opened.put(p.getUuid(), System.currentTimeMillis());
                ServerPlayNetworking.send(p, new Net.Dream(p.getRandom().nextInt()));
            }
            return ActionResult.SUCCESS;
        });
        // Nothing else puts anyone to sleep either.
        EntitySleepEvents.ALLOW_SLEEPING.register((player, pos) -> PlayerEntity.SleepFailureReason.OTHER_PROBLEM);
    }

    public void done(ServerPlayerEntity p, Net.DreamDone d) {
        Long at = opened.remove(p.getUuid());
        // Only after a real count: opened from a bed, and long enough to have watched the sheep.
        if (at == null || System.currentTimeMillis() - at < 8_000) return;
        if (d.titans() > 0) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 120, 0));
            p.playSoundToPlayer(SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.PLAYERS, 0.5f, 1.4f);
            Notify.toast(p, Text.literal("You counted a titan").formatted(Formatting.RED, Formatting.BOLD),
                Text.literal("It counted you back."), 0xC0463A, "minecraft:white_wool", null);
            return;
        }
        if (d.sasha()) {
            Notify.toast(p, Text.literal("Sasha ate one of the sheep").formatted(Formatting.GOLD),
                Text.literal("You wake up hungry."), 0xE0B96A, "minecraft:baked_potato", null);
            p.getHungerManager().addExhaustion(8f);
            return;
        }
        long now = System.currentTimeMillis();
        boolean good = d.counted() >= 8 && d.missed() <= 3;
        if (!good) {
            Notify.toast(p, Text.literal("You lost count").formatted(Formatting.GRAY), Text.literal("Restless night."), 0x8F8A7A, "minecraft:white_wool", null);
            return;
        }
        if (now - rested.getOrDefault(p.getUuid(), 0L) < REST_COOLDOWN_MS) {
            Notify.toast(p, Text.literal(d.counted() + " sheep").formatted(Formatting.GOLD), Text.literal("You're as rested as you'll get."), 0xE0B96A, "minecraft:white_wool", null);
            return;
        }
        rested.put(p.getUuid(), now);
        boolean perfect = d.missed() == 0;
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, perfect ? 900 : 600, 0));
        p.getHungerManager().add(4, 0.6f);
        if (perfect) p.addStatusEffect(new StatusEffectInstance(StatusEffects.LUCK, 3600, 0));
        p.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.5f, 0.8f);
        Notify.toast(p, Text.literal(perfect ? "Slept like a Titan" : "Well rested").formatted(Formatting.GOLD, Formatting.BOLD),
            Text.literal(d.counted() + " sheep counted"), 0xE0B96A, "minecraft:white_wool", null);
    }
}
