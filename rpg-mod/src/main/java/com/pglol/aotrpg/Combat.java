package com.pglol.aotrpg;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Combat feel and gear power. Gear "Power" raises the damage your ODM blades and APG gun deal
 * (Danny's AoT computes their damage itself, so it is applied here), and every hit you land on a
 * player or titan sends you a hit marker: melee slash or gunshot, and whether it was a kill.
 */
public final class Combat {
    private boolean reapplying;

    public void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (reapplying || amount <= 0) return true;
            ServerPlayerEntity def = entity instanceof ServerPlayerEntity sp ? sp : null;
            if (def != null && AotRpg.ABILITIES.dodge(def, source)) return false;
            double mult = 1;
            if (source.getAttacker() instanceof ServerPlayerEntity attacker && attacker != entity) {
                // Gear power counts only when the character is high enough level for the weapon.
                if (Gear.canUse(attacker, attacker.getMainHandStack())) mult *= 1 + Gear.power(attacker.getMainHandStack());
                mult *= AotRpg.ABILITIES.outgoing(attacker, entity, source);
            }
            if (def != null) {
                mult *= AotRpg.ABILITIES.incoming(def);
                if (AotRpg.ABILITIES.lastStand(def, (float) (amount * mult))) return false;
            }
            if (Math.abs(mult - 1) < 1e-4) return true;
            reapplying = true;
            try {
                entity.damage(source, (float) (amount * mult));
            } finally {
                reapplying = false;
            }
            return false;
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (source.getAttacker() instanceof ServerPlayerEntity killer) AotRpg.ABILITIES.onKill(killer, entity);
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> AotRpg.ABILITIES.afterHit(entity, source, taken));
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!(source.getAttacker() instanceof ServerPlayerEntity attacker) || attacker == entity || taken <= 0) return;
            if (!(entity instanceof PlayerEntity) && !AotRpg.isTitan(entity) && !(entity instanceof net.minecraft.entity.mob.HostileEntity)) return;
            boolean ranged = !melee(source);
            if (!ranged) {
                // The attacker's blade slash, for everyone nearby.
                Net.SlashFx fx = new Net.SlashFx(AotRpg.COSMETICS.selected(attacker, "slash"), entity.getX(),
                    entity.getY() + Math.min(entity.getHeight() * 0.6, 3), entity.getZ(), attacker.getYaw());
                for (ServerPlayerEntity o : net.fabricmc.fabric.api.networking.v1.PlayerLookup.around((net.minecraft.server.world.ServerWorld) entity.getWorld(), entity.getPos(), 48)) {
                    if (ServerPlayNetworking.canSend(o, Net.SlashFx.ID)) ServerPlayNetworking.send(o, fx);
                }
            }
            if (!ServerPlayNetworking.canSend(attacker, Net.HitMarker.ID)) return;
            boolean kill = entity.isDead() || entity.getHealth() <= 0;
            int kind = (ranged ? 1 : 0) | (kill ? 2 : 0) | (entity instanceof PlayerEntity ? 4 : 0) | (AotRpg.isTitan(entity) ? 8 : 0);
            ServerPlayNetworking.send(attacker, new Net.HitMarker(entity.getId(), taken, kind));
        });
    }

    /**
     * Was this hit a close-quarters strike? Danny's ODM blades deal their damage with their own
     * damage source (not always the plain player attack), so a hit counts as melee whenever it is
     * not a projectile and the attacker holds a blade or sword rather than an APG gun.
     */
    public static boolean melee(net.minecraft.entity.damage.DamageSource source) {
        if (!(source.getAttacker() instanceof LivingEntity att)) return false;
        net.minecraft.entity.Entity direct = source.getSource();
        if (direct == att) return !(att instanceof PlayerEntity p) || !AotItems.isApgGun(p.getMainHandStack());
        if (direct instanceof net.minecraft.entity.projectile.ProjectileEntity) return false;
        return att.getMainHandStack() != null && Guard.melee(att.getMainHandStack()) && !AotItems.isApgGun(att.getMainHandStack());
    }

    /** Is an entity a living combat target worth a marker (used by tests of the kind bits). */
    static boolean living(LivingEntity e) {
        return e.isAlive();
    }
}
