package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.BalloonStations;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Right-click aboard the balloon: a station in front of you opens, whatever else is in the way. */
@Mixin(MinecraftClient.class)
public abstract class BalloonUseMixin {
    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void aotrpg$station(CallbackInfo ci) {
        if (BalloonStations.use((MinecraftClient) (Object) this)) ci.cancel();
    }
}
