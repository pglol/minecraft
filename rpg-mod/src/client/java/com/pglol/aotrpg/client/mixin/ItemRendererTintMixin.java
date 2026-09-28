package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.RarityTint;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws rare-and-up Danny weapons in their rarity's colour, blade and all (see RarityTint). */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererTintMixin {
    @ModifyVariable(method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/json/ModelTransformationMode;ZLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IILnet/minecraft/client/render/model/BakedModel;)V",
        at = @At("HEAD"), argsOnly = true, require = 0)
    private VertexConsumerProvider aotrpg$tint(VertexConsumerProvider vc, ItemStack stack, ModelTransformationMode mode, boolean left,
                                              MatrixStack ms, VertexConsumerProvider same, int light, int overlay, BakedModel model) {
        return RarityTint.enter(stack, vc);
    }

    @Inject(method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/json/ModelTransformationMode;ZLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IILnet/minecraft/client/render/model/BakedModel;)V",
        at = @At("RETURN"), require = 0)
    private void aotrpg$done(CallbackInfo ci) {
        RarityTint.exit();
    }
}
