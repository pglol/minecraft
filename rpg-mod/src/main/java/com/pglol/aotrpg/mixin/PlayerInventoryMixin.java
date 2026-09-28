package com.pglol.aotrpg.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.collection.DefaultedList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Anything received (pickups, rewards, crates) lands in the backpack rows first, which sweep into
 * the satchel, never into a free loadout slot on its own: no fresh grip in the melee hand while
 * your blades are sheathed. The hotbar is only used when the backpack is full.
 */
@Mixin(PlayerInventory.class)
public abstract class PlayerInventoryMixin {
    @Shadow @Final public DefaultedList<ItemStack> main;
    @Shadow @Final public PlayerEntity player;

    @Inject(method = "getEmptySlot", at = @At("HEAD"), cancellable = true)
    private void aotrpg$backpackFirst(CallbackInfoReturnable<Integer> cir) {
        if (player == null || player.getWorld() == null || player.getWorld().isClient || player.isCreative()) return;
        for (int i = 9; i < main.size(); i++) {
            if (main.get(i).isEmpty()) {
                cir.setReturnValue(i);
                return;
            }
        }
    }
}
