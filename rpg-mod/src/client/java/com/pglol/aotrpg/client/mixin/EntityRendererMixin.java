package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.NamePlates;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces the vanilla username tag over players with the AoT name plate. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Shadow @Final protected EntityRenderDispatcher dispatcher;

    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"), cancellable = true)
    private void aotrpg$namePlate(Entity entity, Text text, MatrixStack matrices, VertexConsumerProvider consumers,
                                  int light, float tickDelta, CallbackInfo ci) {
        if (entity instanceof PlayerEntity player && NamePlates.render(player, dispatcher, matrices, consumers, tickDelta)) ci.cancel();
    }
}
