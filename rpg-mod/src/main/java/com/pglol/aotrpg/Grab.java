package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
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
 * Caught by a titan: spam attack to strike it in the eye and break free. Every click counts (the
 * faster you hit, the faster you're out); the count slowly slips back if you stop. The server
 * keeps the count and tells the client, so the bar is always the real one.
 *
 * However you get loose (breaking free, being dropped, a titan letting go), you're thrown back
 * clear and can't be picked up again for a few seconds, so titans can't pass you hand to hand.
 */
public final class Grab {
    public static final int NEEDED = 12;
    /** Ticks after a release during which no titan can take hold of you. */
    public static final int GRACE = 100;

    private static final class Hold {
        float strikes;
        long lastClick;
        Vec3d titanAt = Vec3d.ZERO;
        boolean held;
        long immuneUntil;
        float sent = -1;
    }

    private final Map<UUID, Hold> holds = new HashMap<>();

    /** Held by a titan that is not you (a shifter controls its own titan). */
    public static boolean grabbed(Entity p) {
        Entity v = p.getVehicle();
        return v != null && titan(v) && v.getControllingPassenger() != p;
    }

    private static boolean titan(Entity v) {
        String ns = Registries.ENTITY_TYPE.getId(v.getType()).getNamespace();
        boolean aot = ns.equals("dannys-aot") || ns.equals(AotItems.namespace);
        return aot && !(v instanceof net.minecraft.entity.passive.AbstractHorseEntity);
    }

    /** True while a freshly released player can't be grabbed again by this (titan) vehicle. */
    public boolean immune(ServerPlayerEntity p, Entity vehicle) {
        if (vehicle == null || !titan(vehicle) || vehicle.getControllingPassenger() == p) return false;
        // Early story titans are for learning the nape: they never get a grip at all.
        if (vehicle.getCommandTags().contains(Story.GENTLE)) return true;
        Hold h = holds.get(p.getUuid());
        return h != null && p.getServerWorld().getTime() < h.immuneUntil;
    }

    private int need(ServerPlayerEntity p) {
        int need = Classes.alone(p) ? 8 : NEEDED;
        if (AotRpg.PROFILES.get(p.getUuid()).has(Skill.TNK_ANCHOR)) need = Math.max(4, need / 2);
        if (AotRpg.PROFILES.get(p.getUuid()).has(Skill.ENG_LINES)) need = Math.max(4, (int) Math.ceil(need * 0.75));
        return need;
    }

    public void strike(ServerPlayerEntity p) {
        if (!grabbed(p)) return;
        Hold h = holds.computeIfAbsent(p.getUuid(), k -> new Hold());
        long now = p.getServerWorld().getTime();
        if (now == h.lastClick) return; // one strike per tick at most (no macros)
        h.lastClick = now;
        h.strikes += 1;
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_WEAK, SoundCategory.PLAYERS, 0.6f, 1.1f + h.strikes * 0.04f);
        int need = need(p);
        send(p, h, Math.min(1f, h.strikes / need));
        if (h.strikes < need) return;
        Entity titan = p.getVehicle();
        if (titan instanceof LivingEntity t) {
            t.damage(p.getDamageSources().playerAttack(p), Math.max(8f, t.getMaxHealth() * 0.05f));
            t.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 80, 0));
            t.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 80, 3));
        }
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1f, 0.8f);
        p.sendMessage(Text.literal("You struck it in the eye and broke free!").formatted(Formatting.GOLD, Formatting.BOLD), true);
        p.stopRiding();
        // The release itself (jump back, grace) happens in tick, the same as any other release.
    }

    public void tick(ServerPlayerEntity p, int ticks) {
        boolean g = grabbed(p);
        Hold h = holds.get(p.getUuid());
        if (h == null) {
            if (!g) return;
            h = new Hold();
            holds.put(p.getUuid(), h);
        }
        long now = p.getServerWorld().getTime();
        if (g) {
            Entity v = p.getVehicle();
            if (now < h.immuneUntil) {
                // Still shaking off the last one: slip straight out of this grip.
                p.stopRiding();
                throwClear(p, v.getPos(), 0.7);
                return;
            }
            h.titanAt = v.getPos();
            if (!h.held) {
                h.held = true;
                h.strikes = 0;
                send(p, h, 0);
            }
            // Stop hitting and the grip tightens again, slowly.
            if (now - h.lastClick > 8 && h.strikes > 0) {
                h.strikes = Math.max(0, h.strikes - 0.12f);
                if (ticks % 3 == 0) send(p, h, h.strikes / need(p));
            }
            return;
        }
        if (h.held) {
            // Loose: thrown clear, a moment's head start, and no titan can take you for a few seconds.
            h.held = false;
            h.strikes = 0;
            h.immuneUntil = now + GRACE;
            send(p, h, -1);
            throwClear(p, h.titanAt, 1.0);
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 60, 1, false, false, true));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 30, 0, false, false, false));
            p.getServerWorld().spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1, p.getZ(), 10, 0.4, 0.4, 0.4, 0.05);
            return;
        }
        if (now > h.immuneUntil) holds.remove(p.getUuid());
    }

    /** A leap back, away from the titan and up. */
    private static void throwClear(ServerPlayerEntity p, Vec3d from, double power) {
        Vec3d away = p.getPos().subtract(from).multiply(1, 0, 1);
        if (away.lengthSquared() < 1e-4) away = p.getRotationVec(1f).multiply(-1, 0, -1);
        away = away.lengthSquared() < 1e-4 ? new Vec3d(1, 0, 0) : away.normalize();
        p.setVelocity(away.x * 1.1 * power, 0.55 * power + 0.15, away.z * 1.1 * power);
        p.velocityModified = true;
        p.fallDistance = 0;
    }

    private static void send(ServerPlayerEntity p, Hold h, float frac) {
        if (Math.abs(frac - h.sent) < 0.005f) return;
        h.sent = frac;
        if (ServerPlayNetworking.canSend(p, Net.GrabProgress.ID)) ServerPlayNetworking.send(p, new Net.GrabProgress(frac));
    }

    public void forget(ServerPlayerEntity p) {
        holds.remove(p.getUuid());
    }
}
