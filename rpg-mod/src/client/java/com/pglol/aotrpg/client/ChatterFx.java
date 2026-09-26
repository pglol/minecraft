package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What townspeople say, floating over their heads: their name small above, the words beneath on a
 * soft dark card that rises a little and fades out. Readable from a few steps away, gone at a distance.
 */
public final class ChatterFx {
    private ChatterFx() {}

    private record Line(String text, long at) { }

    private static final Map<Integer, Line> lines = new HashMap<>();

    public static void onChatter(Net.Chatter c) {
        lines.put(c.entity(), new Line(c.text(), Util.getMeasuringTimeMs()));
    }

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || lines.isEmpty()) return;
        MatrixStack ms = ctx.matrixStack();
        VertexConsumerProvider vc = ctx.consumers();
        if (ms == null || vc == null) return;
        long now = Util.getMeasuringTimeMs();
        Vec3d cam = ctx.camera().getPos();
        float td = ctx.tickCounter().getTickDelta(true);
        TextRenderer tr = mc.textRenderer;
        for (var it = lines.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            Line l = en.getValue();
            long life = 3200 + l.text().length() * 55L, age = now - l.at();
            Entity e = mc.world.getEntityById(en.getKey());
            if (age > life || e == null || !e.isAlive()) {
                it.remove();
                continue;
            }
            double dist = e.getPos().distanceTo(cam);
            if (dist > 20) continue;
            float in = Math.min(1, age / 200f), out = age > life - 500 ? (life - age) / 500f : 1;
            float near = dist < 14 ? 1 : (float) (1 - (dist - 14) / 6);
            int alpha = (int) (255 * Math.max(0, Math.min(in, Math.min(out, near))));
            if (alpha < 8) continue;
            Vec3d pos = e.getLerpedPos(td);
            float rise = Math.min(1, age / 600f) * 0.15f;
            List<OrderedText> wrapped = tr.wrapLines(Text.literal(l.text()), 140);
            String name = e instanceof net.minecraft.entity.passive.VillagerEntity v && !v.hasCustomName() ? com.pglol.aotrpg.Townsfolk.name(e)
                : e.hasCustomName() ? e.getCustomName().getString() : "";

            ms.push();
            ms.translate(pos.x - cam.x, pos.y - cam.y + e.getHeight() + 0.55 + rise, pos.z - cam.z);
            ms.multiply(mc.getEntityRenderDispatcher().getRotation());
            ms.scale(0.022f, -0.022f, 0.022f);
            Matrix4f m = ms.peek().getPositionMatrix();
            int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
            int w = 0;
            for (OrderedText o : wrapped) w = Math.max(w, tr.getWidth(o));
            int h = wrapped.size() * 10;
            float x0 = -w / 2f - 5, x1 = w / 2f + 5, y1 = 0, y0 = -h - 6;
            VertexConsumer bg = vc.getBuffer(RenderLayer.getTextBackground());
            quad(bg, m, x0, y0, x1, y1, -1.2f, (alpha * 150 / 255) << 24 | 0x0D0F0D, light);
            // A small tail pointing down at the speaker.
            quad(bg, m, -2, y1, 2, y1 + 3, -1.2f, (alpha * 150 / 255) << 24 | 0x0D0F0D, light);
            float y = y0 + 3;
            for (OrderedText o : wrapped) {
                tr.draw(o, -tr.getWidth(o) / 2f, y, alpha << 24 | 0xEDE3C8, false, m, vc, TextRenderer.TextLayerType.POLYGON_OFFSET, 0, light);
                y += 10;
            }
            if (!name.isEmpty()) {
                ms.push();
                ms.translate(0, y0 - 9, 0);
                ms.scale(0.75f, 0.75f, 1);
                tr.draw(name, -tr.getWidth(name) / 2f, 0, alpha << 24 | 0xB8955A, false, ms.peek().getPositionMatrix(), vc,
                    TextRenderer.TextLayerType.POLYGON_OFFSET, 0, light);
                ms.pop();
            }
            ms.pop();
        }
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float x1, float y1, float x2, float y2, float z, int argb, int light) {
        vc.vertex(m, x1, y1, z).color(argb).light(light);
        vc.vertex(m, x1, y2, z).color(argb).light(light);
        vc.vertex(m, x2, y2, z).color(argb).light(light);
        vc.vertex(m, x2, y1, z).color(argb).light(light);
        vc.vertex(m, x2, y1, z).color(argb).light(light);
        vc.vertex(m, x2, y2, z).color(argb).light(light);
        vc.vertex(m, x1, y2, z).color(argb).light(light);
        vc.vertex(m, x1, y1, z).color(argb).light(light);
    }
}
