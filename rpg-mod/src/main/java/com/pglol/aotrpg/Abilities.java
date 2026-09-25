package com.pglol.aotrpg;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The combat abilities of the skill trees (the ✦ ones): damage bonuses that depend on the moment
 * (combos, low-health foes, flight, a parry), defensive triggers (dodge, last stand, strike-back)
 * and rewards for kills. Most grow stronger with the character's level.
 */
public final class Abilities {
    private static final class State {
        int combo;
        long lastHit, riposteUntil, afterimageReady, lastStandReady;
        Vec3d lastPos;
        double speed;
    }

    private final Map<UUID, State> states = new HashMap<>();
    private boolean sweeping;

    private State st(PlayerEntity p) {
        return states.computeIfAbsent(p.getUuid(), k -> new State());
    }

    private static Profile pr(PlayerEntity p) {
        return AotRpg.PROFILES.get(p.getUuid());
    }

    private static void callout(ServerPlayerEntity p, String what, Formatting color) {
        p.sendMessage(Text.literal(what).formatted(color, Formatting.BOLD), true);
    }

    private static void burst(Entity at, ParticleEffect fx, int n) {
        if (at.getWorld() instanceof ServerWorld w) {
            w.spawnParticles(fx, at.getX(), at.getY() + at.getHeight() * 0.6, at.getZ(), n, at.getWidth() / 2, at.getHeight() / 3, at.getWidth() / 2, 0.05);
        }
    }

    /** Once a tick: how fast each player moves (the server's own measure, for Momentum). */
    public void tick(ServerPlayerEntity p) {
        State s = st(p);
        Vec3d now = p.getPos();
        s.speed = s.lastPos == null ? 0 : now.distanceTo(s.lastPos);
        s.lastPos = now;
    }

    public void forget(UUID id) {
        states.remove(id);
    }

    /** A successful block primes Riposte. */
    public void blocked(ServerPlayerEntity p) {
        if (pr(p).has(Skill.RIPOSTE)) st(p).riposteUntil = System.currentTimeMillis() + 2000;
    }

    /** Damage multiplier for a hit this player lands. */
    public double outgoing(ServerPlayerEntity att, LivingEntity target, DamageSource source) {
        Profile pr = pr(att);
        if (!pr.created) return 1;
        State s = st(att);
        long now = System.currentTimeMillis();
        boolean melee = source.getSource() == att;
        double m = 1, lv = pr.level;
        if (AotRpg.isTitan(target) && pr.has(Skill.TITAN_SLAYER)) m *= 1.15;
        if (melee) {
            s.combo = now - s.lastHit < 3000 ? s.combo + 1 : 1;
            s.lastHit = now;
            if (pr.has(Skill.FLURRY) && s.combo % 4 == 0) {
                m *= 1.4 + 0.006 * lv;
                callout(att, "FLURRY", Formatting.GOLD);
                burst(target, ParticleTypes.CRIT, 12);
            }
            if (pr.has(Skill.RIPOSTE) && now < s.riposteUntil) {
                s.riposteUntil = 0;
                m *= 2;
                att.heal(2 + (float) lv / 25);
                callout(att, "RIPOSTE", Formatting.AQUA);
                burst(target, ParticleTypes.ENCHANTED_HIT, 16);
            }
        }
        if (pr.has(Skill.EXECUTIONER) && target.getHealth() < target.getMaxHealth() * 0.3f) m *= 1.3 + 0.004 * lv;
        if (pr.has(Skill.MOMENTUM) && s.speed > 0.55) m *= 1.2 + 0.003 * lv;
        if (pr.has(Skill.AERIAL_ACE) && !att.isOnGround() && !att.hasVehicle()) m *= 1.25 + 0.003 * lv;
        return m;
    }

    /** Damage multiplier for a hit this player takes. */
    public double incoming(ServerPlayerEntity def) {
        return pr(def).has(Skill.UNBREAKABLE) && def.getHealth() < def.getMaxHealth() / 2 ? 0.85 : 1;
    }

    /** Evasion: may cancel a melee hit outright. */
    public boolean dodge(ServerPlayerEntity def, DamageSource source) {
        Profile pr = pr(def);
        if (!pr.has(Skill.EVASION) || source.getAttacker() == null || source.getSource() != source.getAttacker()) return false;
        if (def.getRandom().nextDouble() >= 0.08 + 0.001 * pr.level) return false;
        callout(def, "DODGED", Formatting.GREEN);
        burst(def, ParticleTypes.CLOUD, 10);
        def.getWorld().playSound(null, def.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_NODAMAGE, SoundCategory.PLAYERS, 1f, 1.4f);
        return true;
    }

    /** Last Stand: survives a killing blow once every 90 seconds. */
    public boolean lastStand(ServerPlayerEntity def, float damage) {
        if (!pr(def).has(Skill.LAST_STAND) || damage < def.getHealth()) return false;
        State s = st(def);
        long now = System.currentTimeMillis();
        if (now < s.lastStandReady) return false;
        s.lastStandReady = now + 90_000;
        def.setHealth(1);
        def.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 60, 3, false, true, true));
        callout(def, "LAST STAND", Formatting.RED);
        burst(def, ParticleTypes.TOTEM_OF_UNDYING, 30);
        def.getWorld().playSound(null, def.getBlockPos(), SoundEvents.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 0.6f, 1.3f);
        return true;
    }

    /** After a hit lands: strike-backs, sweeps and the low-health vanish. */
    public void afterHit(LivingEntity victim, DamageSource source, float taken) {
        if (taken <= 0) return;
        Entity src = source.getAttacker();
        if (victim instanceof ServerPlayerEntity def) {
            Profile pr = pr(def);
            if (pr.has(Skill.RETALIATION) && src instanceof LivingEntity att && source.getSource() == src && att != def
                && def.getRandom().nextFloat() < 0.25f) {
                att.damage(def.getDamageSources().thorns(def), taken * (float) (0.35 + 0.003 * pr.level));
                burst(att, ParticleTypes.DAMAGE_INDICATOR, 6);
            }
            if (pr.has(Skill.AFTERIMAGE) && def.getHealth() < def.getMaxHealth() * 0.3f) {
                State s = st(def);
                long now = System.currentTimeMillis();
                if (now >= s.afterimageReady) {
                    s.afterimageReady = now + 60_000;
                    def.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 60, 1, false, false, true));
                    def.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 60, 0, false, false, true));
                    burst(def, ParticleTypes.LARGE_SMOKE, 25);
                    callout(def, "AFTERIMAGE", Formatting.DARK_AQUA);
                }
            }
        }
        if (src instanceof ServerPlayerEntity att && source.getSource() == att && !sweeping && pr(att).has(Skill.SPINNING_SLASH)
            && att.getRandom().nextFloat() < 0.2f) {
            sweeping = true;
            try {
                for (LivingEntity e : att.getWorld().getEntitiesByClass(LivingEntity.class, att.getBoundingBox().expand(3), e -> e != att && e != victim
                    && e.isAlive() && !(e instanceof PlayerEntity pl && AotRpg.PARTIES.same(pl.getUuid(), att.getUuid())))) {
                    e.damage(att.getDamageSources().playerAttack(att), taken * 0.6f);
                }
                burst(att, ParticleTypes.SWEEP_ATTACK, 8);
                callout(att, "SPINNING SLASH", Formatting.GOLD);
            } finally {
                sweeping = false;
            }
        }
    }

    /** A kill: Bloodlust, Wings of Freedom, Field Medic. */
    public void onKill(ServerPlayerEntity killer, LivingEntity dead) {
        Profile pr = pr(killer);
        if (!pr.created || dead == killer) return;
        if (pr.has(Skill.BLOODLUST)) {
            AotRpg.STAMINA.spend(killer, -pr.maxStamina() * 0.25f);
            killer.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 100, 0, false, false, true));
        }
        if (pr.has(Skill.WINGS_OF_FREEDOM)) {
            killer.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 120, 0, false, false, true));
            killer.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, 120, 0, false, false, true));
        }
        if (pr.has(Skill.FIELD_MEDIC)) killer.heal(2 + pr.level / 20f);
    }
}
