package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.ClientState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Party members get an outline (visible through walls) for us only. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "hasOutline", at = @At("HEAD"), cancellable = true)
    private void aotrpg$partyOutline(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof PlayerEntity && ClientState.partyMember(entity.getUuid()) != null) cir.setReturnValue(true);
    }
}
