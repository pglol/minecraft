package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.OdmMoves;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player's ODM move as others see it: the whole body tumbles over the way they flipped, or
 * leans hard into a dash, turning about its middle.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class OdmPlayerModelMixin {
    @Inject(method = "setupTransforms(Lnet/minecraft/client/network/AbstractClientPlayerEntity;Lnet/minecraft/client/util/math/MatrixStack;FFFF)V", at = @At("HEAD"))
    private void aotrpg$tumble(AbstractClientPlayerEntity player, MatrixStack ms, float anim, float bodyYaw, float tickDelta, float scale, CallbackInfo ci) {
        OdmMoves.Anim a = OdmMoves.anim(player.getId());
        if (a == null) return;
        float angle = OdmMoves.bodyAngle(a);
        if (angle == 0) return;
        double y = Math.toRadians(a.yaw());
        // Axis across the direction of travel: turning about it tips the head that way.
        float ax = (float) Math.cos(y), az = (float) Math.sin(y);
        float mid = player.getHeight() / 2f;
        ms.translate(0, mid, 0);
        ms.multiply(new Quaternionf().rotationAxis((float) Math.toRadians(angle), ax, 0, az));
        ms.translate(0, -mid, 0);
    }
}
