package com.pglol.aotrpg.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * At home, a close evening haze outside: the copy of the street around your house fades into it,
 * so you never see where the copy ends (or anything beyond it).
 */
@Mixin(BackgroundRenderer.class)
public abstract class HomeFogMixin {
    @Inject(method = "applyFog", at = @At("TAIL"))
    private static void aotrpg$homeFog(Camera camera, BackgroundRenderer.FogType type, float viewDistance, boolean thick, float tickDelta, CallbackInfo ci) {
        var w = MinecraftClient.getInstance().world;
        if (w == null || !w.getRegistryKey().getValue().toString().equals("aot_rpg:homes")) return;
        // The Extraction balloons (far west in the same world) sail in open sky.
        if (camera.getPos().x < -399_744) return;
        RenderSystem.setShaderFogStart(type == BackgroundRenderer.FogType.FOG_SKY ? 0 : 14f);
        RenderSystem.setShaderFogEnd(type == BackgroundRenderer.FogType.FOG_SKY ? 30f : 52f);
    }
}
