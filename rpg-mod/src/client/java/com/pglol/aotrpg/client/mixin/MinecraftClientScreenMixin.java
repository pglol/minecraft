package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.ClientState;
import com.pglol.aotrpg.client.RpgInventoryScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opens the RPG inventory instead of the vanilla survival inventory. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void aotrpg$inventory(Screen screen, CallbackInfo ci) {
        MinecraftClient mc = (MinecraftClient) (Object) this;
        if (screen != null && screen.getClass() == InventoryScreen.class && mc.player != null && ClientState.profile != null) {
            ci.cancel();
            mc.setScreen(new RpgInventoryScreen(mc.player));
        }
    }
}
