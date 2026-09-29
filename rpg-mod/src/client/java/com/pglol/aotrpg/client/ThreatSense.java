package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * A sixth sense for who's coming for you: soft arcs round the crosshair pointing at each hostile
 * that has you marked. Faint while they're only watching, warm while they aim or wind up, and a
 * hot pulsing red with a tingle when the blow or the volley is about to land (so you know which
 * way to dodge or guard, even from behind).
 */
public final class ThreatSense {
    private ThreatSense() {}

    private static Net.Threats now;
    private static long at;
    private static int hotBefore;

    public static void on(Net.Threats t) {
        int hot = 0;
        for (byte l : t.level()) if (l >= 3) hot++;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (hot > hotBefore && mc.player != null) mc.player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.9f);
        hotBefore = hot;
        now = t;
        at = Util.getMeasuringTimeMs();
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (now == null || mc.player == null || mc.options.hudHidden || mc.currentScreen != null) return;
        long t = Util.getMeasuringTimeMs();
        float fade = 1 - MathHelper.clamp((t - at - 250) / 350f, 0, 1);
        if (fade <= 0) {
            now = null;
            return;
        }
        Camera cam = mc.gameRenderer.getCamera();
        Vec3d eye = cam.getPos();
        float yaw = cam.getYaw();
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        float cx = w / 2f, cy = h / 2f;
        float base = Math.min(w, h) * 0.2f;
        float pulse = 0.5f + 0.5f * (float) Math.sin(t / 70.0);
        for (int i = 0; i < now.level().length; i++) {
            int lvl = now.level()[i];
            double dx = now.x()[i] - eye.x, dz = now.z()[i] - eye.z;
            float to = (float) (MathHelper.atan2(dz, dx) * MathHelper.DEGREES_PER_RADIAN) - 90f;
            // 0 = straight ahead (top of the ring), clockwise.
            float rel = MathHelper.wrapDegrees(to - yaw);
            double dist = Math.sqrt(dx * dx + dz * dz);
            // Closer threats sit tighter round the crosshair and spread wider.
            float r = base + (float) MathHelper.clamp(dist * 0.6, 0, 26);
            float span = lvl >= 3 ? 26 : lvl == 2 ? 20 : 14;
            span *= (float) MathHelper.clamp(1.4 - dist / 60, 0.8, 1.4);
            int rgb = lvl >= 3 ? 0xFF2A2A : lvl == 2 ? 0xFF9A3A : 0xF2EEE6;
            float alpha = (lvl >= 3 ? 0.55f + 0.45f * pulse : lvl == 2 ? 0.55f : 0.22f) * fade;
            int thick = lvl >= 3 ? 3 : 2;
            arc(c, cx, cy, r, rel, span, thick, rgb, alpha);
            // A soft glow ring just outside.
            arc(c, cx, cy, r + thick + 1, rel, span * 0.8f, 1, rgb, alpha * 0.35f);
        }
    }

    /** An arc centred on `angle` degrees (0 up, clockwise), fading toward its ends. */
    private static void arc(DrawContext c, float cx, float cy, float r, float angle, float span, int thick, int rgb, float alpha) {
        int steps = Math.max(6, (int) (span * 1.2f));
        for (int s = 0; s <= steps; s++) {
            float f = s / (float) steps;
            float a = angle - span / 2 + span * f;
            float edge = 1 - Math.abs(f - 0.5f) * 2;
            int al = (int) (255 * alpha * (0.25f + 0.75f * edge * edge));
            if (al <= 3) continue;
            double rad = Math.toRadians(a);
            int x = Math.round(cx + (float) Math.sin(rad) * r), y = Math.round(cy - (float) Math.cos(rad) * r);
            c.fill(x - thick / 2, y - thick / 2, x - thick / 2 + thick, y - thick / 2 + thick, (al << 24) | rgb);
        }
    }
}
