package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.RpgHud;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides vanilla hearts, armor, hunger, air and the XP bar while the RPG HUD is showing. */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method = "renderStatusBars", at = @At("HEAD"), cancellable = true)
    private void aotrpg$statusBars(CallbackInfo ci) {
        if (RpgHud.active()) ci.cancel();
    }

    /** The combat hotbar replaces the vanilla one (same place, all nine slots). */
    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void aotrpg$hotbar(net.minecraft.client.gui.DrawContext context, net.minecraft.client.render.RenderTickCounter tick, CallbackInfo ci) {
        if (com.pglol.aotrpg.client.CombatHotbar.active()) {
            com.pglol.aotrpg.client.CombatHotbar.render(context, tick);
            ci.cancel();
        }
    }

    @Inject(method = "renderExperienceBar", at = @At("HEAD"), cancellable = true)
    private void aotrpg$xpBar(CallbackInfo ci) {
        if (RpgHud.active()) ci.cancel();
    }

    @Inject(method = "renderExperienceLevel", at = @At("HEAD"), cancellable = true)
    private void aotrpg$xpLevel(CallbackInfo ci) {
        if (RpgHud.active()) ci.cancel();
    }
}
