package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BeaconBlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Waypoints in the world: a bold light beam at every quest target and map mark, plus a
 * semi-transparent floating icon (seen through terrain) with its name and distance.
 */
public final class Beams {
    private Beams() {}

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null || (ClientState.markers.isEmpty() && ClientState.party.isEmpty())) return;
        MatrixStack ms = ctx.matrixStack();
        VertexConsumerProvider vc = ctx.consumers();
        if (ms == null || vc == null) return;
        Vec3d cam = ctx.camera().getPos();
        float td = ctx.tickCounter().getTickDelta(true);
        long time = mc.world.getTime();
        for (Net.Marker m : ClientState.markers) {
            if (m.kind().equals("home")) continue; // homes are on the map, not beams
            double dx = m.x() + 0.5 - cam.x, dz = m.z() + 0.5 - cam.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 3) continue; // standing in it: do not blind the player
            int alpha = (int) Math.min(235, 150 + dist / 3);
            int color = (alpha << 24) | (m.color() & 0xFFFFFF);
            ms.push();
            ms.translate(m.x() - cam.x, m.y() - cam.y, m.z() - cam.z);
            BeaconBlockEntityRenderer.renderBeam(ms, vc, BeaconBlockEntityRenderer.BEAM_TEXTURE, td, 1f, time, 0, 320,
                color, 0.22f, 0.34f);
            ms.pop();
        }

        // Icons go through their own buffer so they draw on top, through walls.
        VertexConsumerProvider.Immediate icons = mc.getBufferBuilders().getEntityVertexConsumers();
        for (Net.Marker m : ClientState.markers) if (!m.kind().equals("home")) icon(mc, ms, icons, cam, m);
        float td2 = ctx.tickCounter().getTickDelta(true);
        for (Net.PartyMember pm : ClientState.party) partyIcon(mc, ms, icons, cam, pm, td2);
        icons.draw();
    }

    private static void icon(MinecraftClient mc, MatrixStack ms, VertexConsumerProvider.Immediate vc, Vec3d cam, Net.Marker m) {
        Vec3d target = new Vec3d(m.x() + 0.5, m.y() + 2.2, m.z() + 0.5);
        Vec3d rel = target.subtract(cam);
        double dist = rel.length();
        if (dist < 4) return;
        // Far waypoints are drawn closer in, scaled to keep a steady size on screen.
        double shown = Math.min(dist, 40);
        Vec3d at = rel.multiply(shown / dist);
        float s = (float) (0.0055 * shown);

        TextRenderer tr = mc.textRenderer;
        int rgb = m.color() & 0xFFFFFF;
        // Closer means fainter, so the icon never covers what you are walking up to.
        float fade = (float) Math.min(1, (dist - 4) / 12);
        int a = (int) (190 * fade);
        if (a < 8) return;
        Text label = Text.literal(m.label().isEmpty() ? "Waypoint" : m.label());
        Text meters = Text.literal(dist >= 1000 ? String.format("%.1fkm", dist / 1000) : (int) dist + "m");

        ms.push();
        ms.translate(at.x, at.y, at.z);
        ms.multiply(mc.getEntityRenderDispatcher().getRotation());
        ms.scale(s, -s, s);
        Matrix4f mat = ms.peek().getPositionMatrix();
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;

        // Diamond pin with a dark rim and a soft glow.
        VertexConsumer bg = vc.getBuffer(RenderLayer.getTextBackgroundSeeThrough());
        diamond(bg, mat, 0, -14, 10, (a * 2 / 3 << 24), light);
        diamond(bg, mat, 0, -14, 8, (a << 24) | rgb, light);
        diamond(bg, mat, 0, -14, 3.5f, (a << 24) | 0xFFFFFF, light);

        int tw = tr.getWidth(label), mw = tr.getWidth(meters);
        int plate = Math.max(tw, mw) + 8;
        quad(bg, mat, -plate / 2f, 0, plate / 2f, 21, (int) (a * 0.55f) << 24 | 0x0B0F0C, light);
        quad(bg, mat, -plate / 2f, 0, plate / 2f, 1, (a << 24) | rgb, light);
        int ta = Math.max(40, a + 40);
        tr.draw(label, -tw / 2f, 3, (ta << 24) | 0xEDE3C8, false, mat, vc, TextRenderer.TextLayerType.SEE_THROUGH, 0, light);
        tr.draw(meters, -mw / 2f, 12, (ta << 24) | 0xE0B96A, false, mat, vc, TextRenderer.TextLayerType.SEE_THROUGH, 0, light);
        ms.pop();
    }

    /**
     * A small, soft marker over a party member who is too far for their name plate: a diamond in
     * party green (gold for the leader) with the name and distance, visible through terrain.
     */
    private static void partyIcon(MinecraftClient mc, MatrixStack ms, VertexConsumerProvider.Immediate vc, Vec3d cam,
                                  Net.PartyMember pm, float td) {
        if (!pm.online() || !pm.sameWorld() || pm.id().equals(mc.player.getUuid())) return;
        var ent = mc.world.getPlayerByUuid(pm.id());
        Vec3d pos = ent != null ? ent.getLerpedPos(td).add(0, ent.getHeight() + 0.9, 0) : new Vec3d(pm.x(), pm.y() + 2.7, pm.z());
        Vec3d rel = pos.subtract(cam);
        double dist = rel.length();
        if (dist < 22) return; // the name plate covers it up close
        float fade = (float) Math.min(1, (dist - 22) / 10);
        int a = (int) (170 * fade);
        if (a < 8) return;
        double shown = Math.min(dist, 48);
        Vec3d at = rel.multiply(shown / dist);
        float s = (float) (0.0036 * shown);
        int rgb = pm.leader() ? 0xF2C14E : 0x5BD35B;
        TextRenderer tr = mc.textRenderer;
        Text name = Text.literal(pm.name());
        Text meters = Text.literal((int) dist + "m");

        ms.push();
        ms.translate(at.x, at.y, at.z);
        ms.multiply(mc.getEntityRenderDispatcher().getRotation());
        ms.scale(s, -s, s);
        Matrix4f mat = ms.peek().getPositionMatrix();
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        VertexConsumer bg = vc.getBuffer(RenderLayer.getTextBackgroundSeeThrough());
        diamond(bg, mat, 0, -8, 5.5f, (a * 2 / 3 << 24), light);
        diamond(bg, mat, 0, -8, 4, (a << 24) | rgb, light);
        int tw = tr.getWidth(name);
        int ta = Math.max(40, a + 30);
        tr.draw(name, -tw / 2f, 0, (ta << 24) | rgb, false, mat, vc, TextRenderer.TextLayerType.SEE_THROUGH, (a / 3) << 24, light);
        tr.draw(meters, -tr.getWidth(meters) / 2f, 10, (ta << 24) | 0xEDE3C8, false, mat, vc, TextRenderer.TextLayerType.SEE_THROUGH, 0, light);
        ms.pop();
    }

    private static void diamond(VertexConsumer v, Matrix4f m, float cx, float cy, float r, int argb, int light) {
        v.vertex(m, cx, cy - r, 0).color(argb).light(light);
        v.vertex(m, cx - r, cy, 0).color(argb).light(light);
        v.vertex(m, cx, cy + r, 0).color(argb).light(light);
        v.vertex(m, cx + r, cy, 0).color(argb).light(light);
        // Reverse winding too, so culling never hides it.
        v.vertex(m, cx + r, cy, 0).color(argb).light(light);
        v.vertex(m, cx, cy + r, 0).color(argb).light(light);
        v.vertex(m, cx - r, cy, 0).color(argb).light(light);
        v.vertex(m, cx, cy - r, 0).color(argb).light(light);
    }

    private static void quad(VertexConsumer v, Matrix4f m, float x0, float y0, float x1, float y1, int argb, int light) {
        v.vertex(m, x0, y0, 0).color(argb).light(light);
        v.vertex(m, x0, y1, 0).color(argb).light(light);
        v.vertex(m, x1, y1, 0).color(argb).light(light);
        v.vertex(m, x1, y0, 0).color(argb).light(light);
        v.vertex(m, x1, y0, 0).color(argb).light(light);
        v.vertex(m, x1, y1, 0).color(argb).light(light);
        v.vertex(m, x0, y1, 0).color(argb).light(light);
        v.vertex(m, x0, y0, 0).color(argb).light(light);
    }
}
