package com.pglol.aotrpg;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
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
 * Caught by a titan: spam attack to strike it in the eye and break free. The client shows the
 * prompt and reports each strike; enough strikes in quick succession wound the titan, blind and
 * slow it, and throw you clear.
 */
public final class Grab {
    public static final int NEEDED = 12;
    private final Map<UUID, int[]> progress = new HashMap<>();

    /** Held by a titan that is not you (a shifter controls its own titan). */
    public static boolean grabbed(Entity p) {
        Entity v = p.getVehicle();
        if (v == null) return false;
        String ns = Registries.ENTITY_TYPE.getId(v.getType()).getNamespace();
        boolean aot = ns.equals("dannys-aot") || ns.equals(AotItems.namespace);
        return aot && !(v instanceof net.minecraft.entity.passive.AbstractHorseEntity) && v.getControllingPassenger() != p;
    }

    public void strike(ServerPlayerEntity p) {
        if (!grabbed(p)) {
            progress.remove(p.getUuid());
            return;
        }
        int[] s = progress.computeIfAbsent(p.getUuid(), k -> new int[2]);
        long now = p.getServerWorld().getTime();
        if (now - s[1] < 2) return; // no faster than ten strikes a second
        s[1] = (int) now;
        s[0]++;
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_WEAK, SoundCategory.PLAYERS, 0.6f, 1.3f);
        int need = Classes.alone(p) ? 8 : NEEDED;
        if (AotRpg.PROFILES.get(p.getUuid()).has(Skill.TNK_ANCHOR)) need = Math.max(4, need / 2);
        if (s[0] < need) return;
        progress.remove(p.getUuid());
        Entity titan = p.getVehicle();
        p.stopRiding();
        if (titan instanceof LivingEntity t) {
            t.damage(p.getDamageSources().playerAttack(p), Math.max(8f, t.getMaxHealth() * 0.05f));
            t.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 80, 0));
            t.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 80, 3));
        }
        Vec3d away = titan != null ? p.getPos().subtract(titan.getPos()).multiply(1, 0, 1) : Vec3d.ZERO;
        away = away.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 0) : away.normalize();
        p.setVelocity(away.x * 0.9, 0.6, away.z * 0.9);
        p.velocityModified = true;
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1f, 0.8f);
        p.sendMessage(Text.literal("You struck it in the eye and broke free!").formatted(Formatting.GOLD, Formatting.BOLD), true);
    }

    /** Strikes fade if you stop. */
    public void tick(ServerPlayerEntity p, int ticks) {
        int[] s = progress.get(p.getUuid());
        if (s == null) return;
        if (!grabbed(p)) progress.remove(p.getUuid());
        else if (ticks % 10 == 0 && s[0] > 0) s[0]--;
    }

    public void forget(ServerPlayerEntity p) {
        progress.remove(p.getUuid());
    }
}
