package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.CleanChat;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The chat, drawn quietly (see CleanChat). */
@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void aotrpg$render(DrawContext context, int currentTick, int mouseX, int mouseY, boolean focused, CallbackInfo ci) {
        if (CleanChat.render((ChatHud) (Object) this, context, currentTick, focused)) ci.cancel();
    }
}
