package com.pglol.aotrpg.client.story;

import com.pglol.aotrpg.Net;
import com.pglol.aotrpg.client.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Cutscenes: the camera leaves the player and glides through a sequence of shots, each easing
 * from one spot to another while turning toward a point. Letterbox bars close in, the HUD and the
 * controls are set aside, it can open from black, and it hands the camera back at the end.
 */
public final class CutscenePlayer {
    private CutscenePlayer() {}

    public record Cam(double x, double y, double z, float yaw, float pitch) { }

    private static List<Net.Shot> shots = List.of();
    private static long startedAt;
    private static boolean fade, active, hudWas;
    private static float total;
    /** An intro's title card (empty for story cutscenes). */
    private static String title = "", sub = "";
    private static int color;

    public static boolean active() {
        return active;
    }

    public static void play(Net.Cutscene c) {
        MinecraftClient mc = MinecraftClient.getInstance();
        shots = c.shots();
        fade = c.fade();
        title = c.title();
        sub = c.sub();
        color = c.color() == 0 ? 0xE0B96A : c.color();
        total = 0;
        for (Net.Shot s : shots) total += s.seconds();
        startedAt = Util.getMeasuringTimeMs();
        active = !shots.isEmpty();
        if (active) com.pglol.aotrpg.client.HudHide.hide("cutscene");
    }

    private static float elapsed() {
        return (Util.getMeasuringTimeMs() - startedAt) / 1000f;
    }

    private static double ease(double t) {
        t = MathHelper.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Where the camera is right now, or null when no cutscene is playing. */
    public static Cam camera() {
        if (!active) return null;
        float t = elapsed();
        for (Net.Shot s : shots) {
            if (t <= s.seconds()) {
                double k = ease(t / Math.max(0.01f, s.seconds()));
                double x = MathHelper.lerp(k, s.fx(), s.tx()), y = MathHelper.lerp(k, s.fy(), s.ty()), z = MathHelper.lerp(k, s.fz(), s.tz());
                double lx = MathHelper.lerp(k, s.lx(), s.mx()), ly = MathHelper.lerp(k, s.ly(), s.my()), lz = MathHelper.lerp(k, s.lz(), s.mz());
                Vec3d d = new Vec3d(lx - x, ly - y, lz - z);
                double h = Math.sqrt(d.x * d.x + d.z * d.z);
                float yaw = (float) (MathHelper.atan2(d.z, d.x) * MathHelper.DEGREES_PER_RADIAN) - 90f;
                float pitch = (float) -(MathHelper.atan2(d.y, h) * MathHelper.DEGREES_PER_RADIAN);
                return new Cam(x, y, z, yaw, pitch);
            }
            t -= s.seconds();
        }
        return null;
    }

    /** Controls are held while it plays; the camera comes back when it ends. */
    public static void tick(MinecraftClient mc) {
        if (!active) return;
        if (mc.player == null || elapsed() > total) {
            active = false;
            com.pglol.aotrpg.client.HudHide.show("cutscene");
            return;
        }
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.attackKey.setPressed(false);
        mc.options.useKey.setPressed(false);
        mc.player.setVelocity(0, Math.min(0, mc.player.getVelocity().y), 0);
    }

    /** Letterbox, the opening fade, and a fade out of the last half second. */
    public static void render(DrawContext c, RenderTickCounter tick) {
        if (!active) return;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        float t = elapsed();
        int bar = (int) (h * 0.11f * Math.min(1, t / 0.6f) * Math.min(1, (total - t) / 0.6f));
        c.fill(0, 0, w, bar, 0xFF000000);
        c.fill(0, h - bar, w, h, 0xFF000000);
        if (fade && t < 2.2f) {
            // Waking up: black, a slow blink, then the world.
            float a = t < 0.8f ? 1 : t < 1.2f ? 1 - (t - 0.8f) / 0.4f * 0.7f : t < 1.5f ? 0.3f + (t - 1.2f) / 0.3f * 0.5f : 0.8f * (1 - (t - 1.5f) / 0.7f);
            c.fill(0, 0, w, h, (int) (255 * MathHelper.clamp(a, 0, 1)) << 24);
        }
        float left = total - t;
        if (left < 0.5f) c.fill(0, 0, w, h, (int) (200 * (1 - left / 0.5f)) << 24);
        if (!title.isEmpty()) {
            // The title card: slides in over the lower bar, holds, fades before the end.
            float in = MathHelper.clamp((t - 0.6f) / 0.5f, 0, 1), out = MathHelper.clamp((total - t - 0.4f) / 0.6f, 0, 1);
            float k = in * out;
            int a = (int) (255 * k);
            if (a > 8) {
                int cy = h - bar - 42;
                int slide = (int) ((1 - in) * 30);
                c.fillGradient(0, cy - 10, w, cy + 34, 0x00000000, (a * 3 / 5) << 24);
                c.fill(w / 2 - 90 + slide, cy + 19, w / 2 + 90 - slide, cy + 20, (a << 24) | (color & 0xFFFFFF));
                Ui.text(c, com.pglol.aotrpg.client.Ui.title(title), w / 2f - slide, cy - 4, 2.2f, (a << 24) | (color & 0xFFFFFF), true);
                if (!sub.isEmpty()) Ui.text(c, net.minecraft.text.Text.literal(sub), w / 2f + slide, cy + 24, 1f, (a << 24) | 0xEDE3C8, true);
            }
        }
        Ui.text(c, net.minecraft.text.Text.literal("▸ cutscene"), w - 50, h - bar + 3, 0.5f, 0x60FFFFFF, false);
    }
}
