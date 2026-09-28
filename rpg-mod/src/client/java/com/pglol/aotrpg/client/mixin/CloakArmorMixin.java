package com.pglol.aotrpg.client.mixin;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A cloak or hood worn on the head drapes over the body: the chest piece under it isn't drawn
 * (it would poke through). For the moment a cloaked player is drawn, their chest slot reads as
 * empty to every renderer (Danny's own armor renderer too), and is put straight back after.
 * Only the look changes; the chest piece still counts for everything else.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class CloakArmorMixin {
    @Unique private static final int CHEST = 2;
    @Unique private ItemStack aotrpg$hidden = ItemStack.EMPTY;
    @Unique private AbstractClientPlayerEntity aotrpg$who;

    @Inject(method = "render(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At("HEAD"))
    private void aotrpg$hideChest(AbstractClientPlayerEntity p, float yaw, float delta, MatrixStack ms, VertexConsumerProvider vc, int light, CallbackInfo ci) {
        aotrpg$who = null;
        ItemStack head = p.getInventory().armor.get(3);
        if (head.isEmpty()) return;
        String path = Registries.ITEM.getId(head.getItem()).getPath();
        if (!path.contains("cloak") && !path.contains("hood") && !path.contains("cape")) return;
        ItemStack chest = p.getInventory().armor.get(CHEST);
        if (chest.isEmpty()) return;
        aotrpg$hidden = chest;
        aotrpg$who = p;
        p.getInventory().armor.set(CHEST, ItemStack.EMPTY);
    }

    @Inject(method = "render(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At("RETURN"))
    private void aotrpg$restoreChest(AbstractClientPlayerEntity p, float yaw, float delta, MatrixStack ms, VertexConsumerProvider vc, int light, CallbackInfo ci) {
        if (aotrpg$who == p && p.getInventory().armor.get(CHEST).isEmpty()) p.getInventory().armor.set(CHEST, aotrpg$hidden);
        aotrpg$who = null;
        aotrpg$hidden = ItemStack.EMPTY;
    }
}
