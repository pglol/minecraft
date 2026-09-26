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
    /**
     * Gear Power and combat abilities scale a hit's damage. This changes the amount as the hit
     * arrives (LivingEntity mixin) instead of cancelling it and dealing a new one: Danny's AoT
     * reads whether its hit landed (a nape strike only kills if the blow went through), so a
     * cancelled-and-replaced hit counted as a miss and upgraded blades could not kill titans.
     */
    public static float scale(LivingEntity entity, net.minecraft.entity.damage.DamageSource source, float amount) {
        if (amount <= 0 || entity.getWorld().isClient) return amount;
        double mult = 1;
        if (source.getAttacker() instanceof ServerPlayerEntity attacker && attacker != entity) {
            // Gear power counts only when the character is high enough level for the weapon.
            if (Gear.canUse(attacker, attacker.getMainHandStack())) mult *= 1 + Gear.power(attacker.getMainHandStack());
            mult *= AotRpg.ABILITIES.outgoing(attacker, entity, source);
            mult *= AotRpg.CLASSES.outgoing(attacker, entity, source);
        }
        if (entity instanceof ServerPlayerEntity def) {
            mult *= AotRpg.ABILITIES.incoming(def);
            mult *= AotRpg.CLASSES.incoming(def, source);
            // Titans hit hard, and far harder when they outrank you; in their grip it's worse still.
            net.minecraft.entity.Entity vehicle = def.getVehicle();
            boolean eaten = vehicle != null && AotRpg.isTitan(vehicle);
            LivingEntity titan = TitanLevels.rootOf(source.getAttacker());
            if (titan == null && eaten) titan = TitanLevels.rootOf(vehicle);
            if (titan == null && source.getSource() != null) titan = TitanLevels.rootOf(source.getSource());
            // (A shifter steered by a player is PvP: that stays as Danny's mod makes it.)
            if (titan != null && TitanLevels.level(titan) > 0 && !(titan.getControllingPassenger() instanceof PlayerEntity)) {
                mult *= TitanLevels.hitMultiplier(titan, def, eaten && !Story.gentle(titan));
                // A story's first titans are there to teach, not to kill: they hit softly.
                if (Story.gentle(titan)) mult *= 0.3;
            }
        }
        return Math.abs(mult - 1) < 1e-4 ? amount : (float) (amount * mult);
    }

    public void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (amount <= 0) return true;
            ServerPlayerEntity def = entity instanceof ServerPlayerEntity sp ? sp : null;
            if (def != null && AotRpg.ABILITIES.dodge(def, source)) return false;
            // (The amount here is already scaled by scale(), from the LivingEntity mixin.)
            return def == null || !AotRpg.ABILITIES.lastStand(def, amount);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (source.getAttacker() instanceof ServerPlayerEntity killer) {
                AotRpg.ABILITIES.onKill(killer, entity);
                AotRpg.CLASSES.onKill(killer, entity);
            }
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            AotRpg.ABILITIES.afterHit(entity, source, taken);
            AotRpg.CLASSES.afterHit(entity, source, taken);
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!(source.getAttacker() instanceof ServerPlayerEntity attacker) || attacker == entity || taken <= 0) return;
            if (!(entity instanceof PlayerEntity) && !AotRpg.isTitan(entity) && !(entity instanceof net.minecraft.entity.mob.HostileEntity)) return;
            boolean ranged = !melee(source);
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

    private final java.util.Map<java.util.UUID, Long> lastSlash = new java.util.HashMap<>();

    /**
     * A blade swing that struck something (reported by the swinging player's client, because
     * Danny's ODM blades deal their damage their own way): the player's slash cosmetic, shown to
     * everyone nearby. Checked for reach and rate so it can't be abused.
     */
    public void slash(ServerPlayerEntity p, int entityId) {
        long now = System.currentTimeMillis();
        if (now - lastSlash.getOrDefault(p.getUuid(), 0L) < 150) return;
        if (AotRpg.DOWNED.isDowned(p)) return;
        if (entityId < 0) {
            // A swing at nothing in particular (mid-air, a near miss): still a swing for clashes.
            if (Guard.melee(p.getMainHandStack()) && !AotItems.isApgGun(p.getMainHandStack())) AotRpg.GUARD_FIGHT.swungAt(p, -1);
            return;
        }
        net.minecraft.entity.Entity target = p.getServerWorld().getEntityById(entityId);
        if (target == null || !target.isAlive() || target == p || AotItems.isApgGun(p.getMainHandStack()) || !Guard.melee(p.getMainHandStack())) return;
        // Titans are huge: measure to the nearest point of their body.
        double reach = 7;
        if (target.getBoundingBox().expand(reach).contains(p.getEyePos()) == false) return;
        lastSlash.put(p.getUuid(), now);
        AotRpg.GUARD_FIGHT.swung(p, target);
        AotRpg.TITAN_LEVELS.slashed(p, target);
        double y = Math.min(p.getEyeY(), target.getBoundingBox().maxY - 0.2);
        net.minecraft.util.math.Vec3d at = target.getBoundingBox().getCenter();
        Net.SlashFx fx = new Net.SlashFx(AotRpg.COSMETICS.selected(p, "slash"), at.x, Math.max(target.getY() + 0.3, y - 0.2), at.z, p.getYaw());
        for (ServerPlayerEntity o : net.fabricmc.fabric.api.networking.v1.PlayerLookup.around(p.getServerWorld(), at, 48)) {
            if (o != p && ServerPlayNetworking.canSend(o, Net.SlashFx.ID)) ServerPlayNetworking.send(o, fx);
        }
    }

    /** Is an entity a living combat target worth a marker (used by tests of the kind bits). */
    static boolean living(LivingEntity e) {
        return e.isAlive();
    }
}
