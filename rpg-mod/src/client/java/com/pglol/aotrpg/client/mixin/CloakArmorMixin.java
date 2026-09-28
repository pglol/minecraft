package com.pglol.aotrpg.client.mixin;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A cloak or hood worn on the head drapes over the body: the chest piece under it isn't drawn
 * (it would poke through). Only the look changes; it still counts for everything else.
 */
@Mixin(ArmorFeatureRenderer.class)
public abstract class CloakArmorMixin<T extends LivingEntity, M extends BipedEntityModel<T>, A extends BipedEntityModel<T>> {
    @Inject(method = "renderArmor", at = @At("HEAD"), cancellable = true)
    private void aotrpg$underCloak(MatrixStack matrices, VertexConsumerProvider vertexConsumers, T entity, EquipmentSlot slot, int light, A model,
                                   CallbackInfo ci) {
        if (slot != EquipmentSlot.CHEST) return;
        var head = entity.getEquippedStack(EquipmentSlot.HEAD);
        if (head.isEmpty()) return;
        String path = Registries.ITEM.getId(head.getItem()).getPath();
        if (path.contains("cloak") || path.contains("hood") || path.contains("cape")) ci.cancel();
    }
}
