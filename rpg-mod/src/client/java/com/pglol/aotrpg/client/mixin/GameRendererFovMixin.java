package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.InfusionFx;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remembers the world's field of view as drawn (setting, sprinting, speed and all), for blade particles. */
@Mixin(GameRenderer.class)
public abstract class GameRendererFovMixin {
    @Inject(method = "getFov", at = @At("RETURN"), require = 0)
    private void aotrpg$fov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Double> cir) {
        if (!changingFov) return;
        // A cutscene's lens (a dolly zoom, a wide plunge) for the world view.
        float cut = com.pglol.aotrpg.client.story.CutscenePlayer.fov();
        if (cut > 0) cir.setReturnValue((double) cut);
        InfusionFx.worldFov = cir.getReturnValue();
    }
}
