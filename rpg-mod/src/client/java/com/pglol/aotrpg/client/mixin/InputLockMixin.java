package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.Autopilot;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cutscenes and scripted walks hold the controls: the keyboard's movement is cleared (or steered). */
@Mixin(KeyboardInput.class)
public abstract class InputLockMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void aotrpg$lock(boolean slowDown, float slowDownFactor, CallbackInfo ci) {
        Autopilot.steer((KeyboardInput) (Object) this);
    }
}
