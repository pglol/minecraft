package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * Who's watching you in town. Over each aware person, a mark that fills as they grow suspicious:
 * a grey eye, a yellow "?", a red "!". While sneaking, a small eye on the HUD says whether you're
 * hidden or seen, and the minimap shows each watcher's 90 degree sight cone.
 */
public final class WitnessFx {
    private WitnessFx() {}

    private static final Map<Integer, Net.Watcher> watchers = new HashMap<>();
    public static long bounty;

    public static void onView(Net.Watchers v) {
        watchers.clear();
        for (Net.Watcher w : v.list()) watchers.put(w.entity(), w);
        bounty = v.bounty();
    }

    private static int color(Net.Watcher w) {
        if ((w.flags() & 2) != 0) return 0xFFE04A3A;
        if (w.level() >= 0.3f) return 0xFFF2C14E;
        return 0xFFB8B2A2;
    }

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || watchers.isEmpty()) return;
        MatrixStack ms = ctx.matrixStack();
        if (ms == null) return;
        VertexConsumerProvider.Immediate vc = mc.getBufferBuilders().getEntityVertexConsumers();
        Vec3d cam = ctx.camera().getPos();
        TextRenderer tr = mc.textRenderer;
        float delta = ctx.tickCounter().getTickDelta(true);
        for (Net.Watcher w : watchers.values()) {
            if (w.level() < 0.05f) continue;
            Entity e = mc.world.getEntityById(w.entity());
            if (e == null) continue;
            Vec3d pos = e.getLerpedPos(delta).add(0, e.getHeight() + 0.55, 0);
            ms.push();
            ms.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
            ms.multiply(mc.getEntityRenderDispatcher().getRotation());
            ms.scale(0.035f, -0.035f, 0.035f);
            boolean alert = (w.flags() & 2) != 0;
            String mark = alert ? "!" : w.level() >= 0.3f ? "?" : "◉";
            int col = color(w);
            // The mark fills from the bottom as awareness grows.
            int h = 12, filled = Math.round(h * Math.min(1, w.level()));
            var m = ms.peek().getPositionMatrix();
            tr.draw(mark, -tr.getWidth(mark) / 2f, 0, 0x60FFFFFF, false, m, vc, TextRenderer.TextLayerType.SEE_THROUGH, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            if (filled > 0) {
                tr.draw(mark, -tr.getWidth(mark) / 2f, 0, col, false, m, vc, TextRenderer.TextLayerType.SEE_THROUGH, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            }
            ms.pop();
        }
        vc.draw();
    }

    /** Sneaking in town: hidden or seen, and how many are looking. */
    public static void renderHud(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden || mc.currentScreen != null) return;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        if (bounty > 0) Ui.text(c, Text.literal("Wanted · " + bounty + " Marks"), w / 2f, h - 60, 0.7f, 0xFFC0463A, true);
        if (!mc.player.isSneaking() || watchers.isEmpty()) return;
        int seeing = 0;
        boolean alert = false;
        for (Net.Watcher x : watchers.values()) {
            if ((x.flags() & 1) != 0) seeing++;
            if ((x.flags() & 2) != 0) alert = true;
        }
        String t = seeing == 0 ? "HIDDEN" : alert ? "DISCOVERED" : "SEEN · " + seeing;
        int col = seeing == 0 ? 0xFF9AB8D8 : alert ? 0xFFE04A3A : 0xFFF2C14E;
        float pulse = seeing > 0 ? (float) (0.85 + 0.15 * Math.sin(Util.getMeasuringTimeMs() / 150.0)) : 1f;
        Ui.text(c, Text.literal("◉"), w / 2f, h / 2f + 14, 1.2f * pulse, col, true);
        Ui.text(c, Ui.heading(t), w / 2f, h / 2f + 27, 0.7f, col, true);
    }

    /** Sight cones on the minimap while sneaking (1 block per pixel, north up). */
    public static void minimap(DrawContext c, int x, int y, int s, double ox, double oz) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || !mc.player.isSneaking() || watchers.isEmpty()) return;
        for (Net.Watcher w : watchers.values()) {
            if (!(mc.world.getEntityById(w.entity()) instanceof LivingEntity e)) continue;
            double ex = e.getX() - ox, ez = e.getZ() - oz;
            if (ex < 0 || ez < 0 || ex > s || ez > s) continue;
            int col = color(w) & 0xFFFFFF;
            float yaw = e.getHeadYaw() * MathHelper.RADIANS_PER_DEGREE;
            int range = 8;
            for (int deg = -45; deg <= 45; deg += 6) {
                float a = yaw + deg * MathHelper.RADIANS_PER_DEGREE;
                double dx = -MathHelper.sin(a), dz = MathHelper.cos(a);
                for (int r = 2; r <= range; r += 2) {
                    int px = x + (int) Math.round(ex + dx * r), py = y + (int) Math.round(ez + dz * r);
                    if (px < x + 1 || py < y + 1 || px > x + s - 2 || py > y + s - 2) continue;
                    int alpha = (int) (110 * (1 - r / (float) (range + 2)));
                    c.fill(px, py, px + 1, py + 1, alpha << 24 | col);
                }
            }
            c.fill(x + (int) ex - 1, y + (int) ez - 1, x + (int) ex + 2, y + (int) ez + 2, 0xFF000000 | col);
        }
    }
}
