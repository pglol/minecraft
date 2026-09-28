package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.ShoulderCam;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Over the shoulder you see your whole self, so the first-person hands aren't drawn. */
@Mixin(GameRenderer.class)
public abstract class ShoulderHandMixin {
    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true, require = 0)
    private void aotrpg$noHands(Camera camera, float tickDelta, Matrix4f matrix, CallbackInfo ci) {
        if (ShoulderCam.active()) ci.cancel();
    }
}
