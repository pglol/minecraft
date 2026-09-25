package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.Toasts;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Compact boss bars (waves, shifters) instead of the big vanilla ones. */
@Mixin(BossBarHud.class)
public abstract class BossBarHudMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void aotrpg$compact(DrawContext context, CallbackInfo ci) {
        Toasts.bossBars(context, ((BossBarHudAccessor) this).aotrpg$bars().values());
        ci.cancel();
    }
}
