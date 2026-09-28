package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.PlayerList;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The player list is ours: the server's name, the count, levels and where everyone is. */
@Mixin(PlayerListHud.class)
public abstract class PlayerListMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void aotrpg$list(DrawContext context, int scaledWindowWidth, Scoreboard scoreboard, ScoreboardObjective objective, CallbackInfo ci) {
        PlayerList.render(context, scaledWindowWidth);
        ci.cancel();
    }

    @Inject(method = "setVisible", at = @At("HEAD"))
    private void aotrpg$visible(boolean visible, CallbackInfo ci) {
        if (!visible) PlayerList.closed();
    }
}
