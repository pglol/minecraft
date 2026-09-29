package com.pglol.aotrpg.mixin;

import net.minecraft.screen.CraftingScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No crafting grid gives a result, the table's or the inventory's: everything is made at a
 * portable workbench (see Crafting and Recipes).
 */
@Mixin(CraftingScreenHandler.class)
public abstract class NoVanillaCraftingMixin {
    @Inject(method = "updateResult", at = @At("HEAD"), cancellable = true, require = 0)
    private static void aotrpg$noGrid(CallbackInfo ci) {
        ci.cancel();
    }
}
