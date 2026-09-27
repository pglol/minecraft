package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.CleanChat;
import net.minecraft.client.network.message.MessageHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server and mod messages become a small notice instead of filling the chat on screen. */
@Mixin(MessageHandler.class)
public abstract class MessageHandlerMixin {
    @Inject(method = "onGameMessage", at = @At("HEAD"))
    private void aotrpg$system(Text message, boolean overlay, CallbackInfo ci) {
        if (!overlay) CleanChat.onSystem(message);
    }
}
