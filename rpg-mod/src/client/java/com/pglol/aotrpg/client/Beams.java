package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BeaconBlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

/** Thin light beams at quest targets and map marks, so you can see them across the land. */
public final class Beams {
    private Beams() {}

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || ClientState.markers.isEmpty()) return;
        MatrixStack ms = ctx.matrixStack();
        VertexConsumerProvider vc = ctx.consumers();
        if (ms == null || vc == null) return;
        Vec3d cam = ctx.camera().getPos();
        float td = ctx.tickCounter().getTickDelta(true);
        long time = mc.world.getTime();
        for (Net.Marker m : ClientState.markers) {
            double dx = m.x() + 0.5 - cam.x, dz = m.z() + 0.5 - cam.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 10) continue; // you are there: do not blind the player
            // Fade in with distance so the beam is a guide, not a wall of light.
            int alpha = (int) Math.min(200, 60 + dist / 4);
            int color = (alpha << 24) | (m.color() & 0xFFFFFF);
            ms.push();
            ms.translate(m.x() - cam.x, m.y() - cam.y, m.z() - cam.z);
            BeaconBlockEntityRenderer.renderBeam(ms, vc, BeaconBlockEntityRenderer.BEAM_TEXTURE, td, 1f, time, 0, 320,
                color, 0.06f, 0.12f);
            ms.pop();
        }
    }
}
