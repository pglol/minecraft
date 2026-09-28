package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.OdmMoves;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Your own ODM flip, seen from inside: the view turns over forward, back, or to the side. */
@Mixin(GameRenderer.class)
public abstract class OdmCameraMixin {
    @Inject(method = "tiltViewWhenHurt", at = @At("HEAD"))
    private void aotrpg$flip(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || !mc.options.getPerspective().isFirstPerson() || com.pglol.aotrpg.client.ShoulderCam.active()) return;
        OdmMoves.Anim a = OdmMoves.anim(mc.player.getId());
        if (a == null || a.kind() != OdmMoves.FLIP) return;
        float angle = OdmMoves.bodyAngle(a);
        float rel = MathHelper.wrapDegrees(a.yaw() - a.viewYaw());
        if (Math.abs(rel) < 45) matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(angle));
        else if (Math.abs(rel) > 135) matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-angle));
        else matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rel > 0 ? angle : -angle));
    }
}
