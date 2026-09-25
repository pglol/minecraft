package com.pglol.aotrpg.mixin;

import com.pglol.aotrpg.DeathCare;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Story mode: gear is protected, so nothing drops on death (extraction mode drops as usual). */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    @Inject(method = "dropInventory", at = @At("HEAD"), cancellable = true)
    private void aotrpg$keepGear(CallbackInfo ci) {
        // The sheathed grip and stashed off-hand item rejoin the inventory first.
        if ((Object) this instanceof ServerPlayerEntity sp) com.pglol.aotrpg.AotRpg.LOADOUT.unsheathAll(sp);
        if ((Object) this instanceof ServerPlayerEntity sp && DeathCare.keepsItems(sp)) ci.cancel();
    }
}
