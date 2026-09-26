package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stamina: sprinting and swinging drain it, resting refills it. At zero you are exhausted
 * (no sprinting, slowed) until it is back to a quarter.
 */
public final class Stamina {
    private static final class State {
        float value = -1, sentValue = -2;
        boolean exhausted;
        int rest;
    }

    private final Map<UUID, State> states = new HashMap<>();

    private State state(ServerPlayerEntity p, Profile pr) {
        State s = states.computeIfAbsent(p.getUuid(), u -> new State());
        if (s.value < 0) s.value = pr.maxStamina();
        return s;
    }

    public void tick(ServerPlayerEntity p, Profile pr, int ticks) {
        if (!pr.created || p.isSpectator() || p.isCreative()) return;
        State s = state(p, pr);
        float max = pr.maxStamina();
        boolean moving = p.getVelocity().horizontalLengthSquared() > 0.001;
        if (p.isSprinting() && moving && !p.hasVehicle()) {
            s.value -= 0.45f;
            s.rest = 20;
        } else if (s.rest > 0) {
            s.rest--;
        } else {
            float regen = 0.55f * (pr.has(Skill.DEEP_BREATH) ? 1.4f : 1f) * (pr.has(Skill.RCN_GAS) ? 1.25f : 1f);
            s.value += regen;
        }
        s.value = Math.max(0, Math.min(max, s.value));
        if (s.value <= 0) s.exhausted = true;
        if (s.exhausted && s.value >= max * 0.25f) s.exhausted = false;
        if (s.exhausted) {
            if (p.isSprinting()) p.setSprinting(false);
            if (ticks % 20 == 0) p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, 0, false, false, false));
        }
        if (ticks % 4 == 0 && Math.abs(s.value - s.sentValue) > 0.01f && ServerPlayNetworking.canSend(p, Net.StaminaSync.ID)) {
            ServerPlayNetworking.send(p, new Net.StaminaSync(s.value, max, s.exhausted));
            s.sentValue = s.value;
        }
    }

    /** A melee swing. */
    public void attack(ServerPlayerEntity p, Profile pr) {
        if (!pr.created) return;
        State s = state(p, pr);
        s.value = Math.max(0, s.value - (pr.has(Skill.GAS_DISCIPLINE) ? 3.2f : 4));
        s.rest = 20;
    }

    /** Spends stamina if there is enough; false (and nothing spent) otherwise. */
    public boolean spend(ServerPlayerEntity p, float amount) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created) return true;
        State s = state(p, pr);
        s.rest = 25;
        if (amount > 0 && pr.has(Skill.GAS_DISCIPLINE)) amount *= 0.8f;
        if (s.value < amount) return false;
        s.value -= amount;
        return true;
    }

    /** Empties stamina (a broken guard). */
    public void drain(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created) return;
        State s = state(p, pr);
        s.value = 0;
        s.exhausted = true;
        s.rest = 40;
    }

    /** Holding a guard: no regeneration meanwhile. */
    public void hold(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (pr.created) state(p, pr).rest = Math.max(state(p, pr).rest, 5);
    }

    public boolean exhausted(ServerPlayerEntity p) {
        State s = states.get(p.getUuid());
        return s != null && s.exhausted;
    }

    /** Current stamina as 0..1 (1 if unknown). */
    public float fraction(UUID id) {
        State s = states.get(id);
        if (s == null || s.value < 0) return 1f;
        Profile pr = AotRpg.PROFILES.get(id);
        return Math.min(1f, s.value / pr.maxStamina());
    }

    public void refill(ServerPlayerEntity p) {
        State s = states.get(p.getUuid());
        if (s != null) {
            s.value = -1;
            s.sentValue = -2;
            s.exhausted = false;
        }
    }

    public void remove(ServerPlayerEntity p) {
        states.remove(p.getUuid());
    }
}
