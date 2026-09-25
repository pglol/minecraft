package com.pglol.aotrpg.mixin;

import com.pglol.aotrpg.AotRpg;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Owned horses open the mod's horse screen instead of the vanilla horse inventory. */
@Mixin(AbstractHorseEntity.class)
public abstract class HorseInventoryMixin {
    @Inject(method = "openInventory", at = @At("HEAD"), cancellable = true, require = 0)
    private void aotrpg$open(PlayerEntity player, CallbackInfo ci) {
        if (player instanceof ServerPlayerEntity sp && AotRpg.HORSES.openInventory(sp, (AbstractHorseEntity) (Object) this)) ci.cancel();
    }
}
