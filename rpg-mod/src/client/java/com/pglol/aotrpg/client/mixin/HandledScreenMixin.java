package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.GearUi;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gear in any inventory gets a glow behind it in its rarity colour. */
@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin {
    @Inject(method = "drawSlot", at = @At("HEAD"))
    private void aotrpg$rarity(DrawContext context, Slot slot, CallbackInfo ci) {
        if (slot.hasStack()) GearUi.backing(context, slot.getStack(), slot.x, slot.y);
    }
}
