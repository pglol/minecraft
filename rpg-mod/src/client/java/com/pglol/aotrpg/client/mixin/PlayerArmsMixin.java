package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.InfusionFx;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remembers each player's arm pose as last drawn, so blade effects can follow the real arms. */
@Mixin(PlayerEntityModel.class)
public abstract class PlayerArmsMixin {
    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"), require = 0)
    private void aotrpg$arms(LivingEntity e, float a, float b, float c, float d, float f, CallbackInfo ci) {
        PlayerEntityModel<?> m = (PlayerEntityModel<?>) (Object) this;
        InfusionFx.arms(e.getId(), m.rightArm, m.leftArm);
    }
}
