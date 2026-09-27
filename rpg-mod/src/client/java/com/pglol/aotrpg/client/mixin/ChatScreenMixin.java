package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.CleanChat;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The chat input bar, restyled; and commands typed are noted so their replies stay on screen. */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"))
    private void aotrpg$bar(DrawContext c, int x1, int y1, int x2, int y2, int color) {
        CleanChat.inputBar(c, x1, y1, x2, y2);
    }

    @Inject(method = "sendMessage", at = @At("HEAD"))
    private void aotrpg$sent(String text, boolean addToHistory, CallbackInfo ci) {
        if (text != null && text.startsWith("/")) CleanChat.commandSent();
    }
}
