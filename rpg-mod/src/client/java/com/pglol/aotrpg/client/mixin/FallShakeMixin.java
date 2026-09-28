package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.EffectFx;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A long drop shakes the view a little (see EffectFx). */
@Mixin(GameRenderer.class)
public abstract class FallShakeMixin {
    @Inject(method = "tiltViewWhenHurt", at = @At("TAIL"), require = 0)
    private void aotrpg$shake(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        EffectFx.shake(matrices, tickDelta);
    }
}
