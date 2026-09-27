package com.pglol.aotrpg.mixin;

import com.pglol.aotrpg.AotRpg;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A player who just broke loose from a titan can't be picked straight back up. */
@Mixin(Entity.class)
public abstract class EntityRidingMixin {
    @Inject(method = "startRiding(Lnet/minecraft/entity/Entity;Z)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void aotrpg$grace(Entity vehicle, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerPlayerEntity p && AotRpg.GRAB != null && AotRpg.GRAB.immune(p, vehicle)) cir.setReturnValue(false);
        // Titans don't pick up townsfolk (they can't die, so they'd only dangle), nor anyone downed.
        if ((Object) this instanceof net.minecraft.entity.passive.MerchantEntity && AotRpg.isTitan(vehicle)) cir.setReturnValue(false);
        if ((Object) this instanceof ServerPlayerEntity p && AotRpg.DOWNED != null && AotRpg.DOWNED.isDowned(p) && AotRpg.isTitan(vehicle)) cir.setReturnValue(false);
    }
}
