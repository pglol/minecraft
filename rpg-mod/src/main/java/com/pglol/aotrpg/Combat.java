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
            if (reapplying || !(source.getAttacker() instanceof ServerPlayerEntity attacker)) return true;
            double power = Gear.power(attacker.getMainHandStack());
            if (power <= 0 || amount <= 0) return true;
            reapplying = true;
            try {
                entity.damage(source, (float) (amount * (1 + power)));
            } finally {
                reapplying = false;
            }
            return false;
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!(source.getAttacker() instanceof ServerPlayerEntity attacker) || attacker == entity || taken <= 0) return;
            if (!(entity instanceof PlayerEntity) && !AotRpg.isTitan(entity) && !(entity instanceof net.minecraft.entity.mob.HostileEntity)) return;
            boolean ranged = source.getSource() != attacker || AotItems.isApgGun(attacker.getMainHandStack());
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

    /** Is an entity a living combat target worth a marker (used by tests of the kind bits). */
    static boolean living(LivingEntity e) {
        return e.isAlive();
    }
}
