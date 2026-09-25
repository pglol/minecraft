package com.pglol.aotrpg.mixin;

import com.pglol.aotrpg.AotRpg;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No placing blocks on protected land. */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;", at = @At("HEAD"), cancellable = true, require = 0)
    private void aotrpg$protect(ItemPlacementContext ctx, CallbackInfoReturnable<ActionResult> cir) {
        var p = ctx.getPlayer();
        if (p == null || p.getWorld().isClient) return;
        if (!AotRpg.CARE.canBuild(p, ctx.getBlockPos())) {
            AotRpg.CARE.deny(p);
            cir.setReturnValue(ActionResult.FAIL);
        }
    }
}
