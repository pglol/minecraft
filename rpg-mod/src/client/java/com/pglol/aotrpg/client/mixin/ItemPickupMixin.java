package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.Toasts;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.network.packet.s2c.play.ItemPickupAnimationS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Real pickups only (an item picked up off the ground by you): shown as "+N item". */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ItemPickupMixin {
    @Inject(method = "onItemPickupAnimation", at = @At("HEAD"))
    private void aotrpg$pickup(ItemPickupAnimationS2CPacket packet, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!mc.isOnThread() || mc.world == null || mc.player == null || packet.getCollectorEntityId() != mc.player.getId()) return;
        Entity e = mc.world.getEntityById(packet.getEntityId());
        if (e instanceof ItemEntity item && !item.getStack().isEmpty()) Toasts.pickedUp(item.getStack(), packet.getStackAmount());
    }
}
