package com.pglol.aotrpg.mixin;

import com.pglol.aotrpg.Loadout;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hotbar slots only take the kind of item they are for (melee, ranged, heal...). */
@Mixin(Slot.class)
public abstract class SlotMixin {
    @Shadow @Final public Inventory inventory;

    @Shadow public abstract int getIndex();

    @Inject(method = "canInsert", at = @At("HEAD"), cancellable = true)
    private void aotrpg$loadout(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (inventory instanceof PlayerInventory pi && getIndex() < 9 && Loadout.enforced(pi.player) && !Loadout.allows(getIndex(), stack)) {
            cir.setReturnValue(false);
        }
    }
}
