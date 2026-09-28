package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.Autopilot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The mouse doesn't turn your character while a cutscene or a scripted walk has it. */
@Mixin(Entity.class)
public abstract class LookLockMixin {
    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void aotrpg$lockLook(double dx, double dy, CallbackInfo ci) {
        if ((Object) this == MinecraftClient.getInstance().player && Autopilot.locked()) ci.cancel();
    }
}
