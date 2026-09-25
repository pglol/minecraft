package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.GearUi;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every slot drawing an item (inventories, hotbar, satchel) marks gear you are too low level for. */
@Mixin(DrawContext.class)
public abstract class DrawContextMixin {
    @Inject(method = "drawItemInSlot(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V", at = @At("TAIL"))
    private void aotrpg$locked(TextRenderer tr, ItemStack stack, int x, int y, String countOverride, CallbackInfo ci) {
        GearUi.lockOverlay((DrawContext) (Object) this, tr, stack, x, y);
    }
}
