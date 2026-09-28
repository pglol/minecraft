package com.pglol.aotrpg.client.story;

import com.pglol.aotrpg.Net;
import com.pglol.aotrpg.client.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Cutscenes. The camera lifts out of your eyes and glides into the first shot, flows from one
 * shot into the next (a true cut only when the next is far away, masked by a blink of black),
 * and at the end flies back into your eyes so control simply resumes. Shots can widen or narrow
 * the lens (a dolly zoom), tilt the horizon, orbit their subject, and ramp their speed.
 * Thin letterbox bars and a small title in the corner; the HUD and controls are set aside.
 */
public final class CutscenePlayer {
    private CutscenePlayer() {}

    public record Cam(double x, double y, double z, float yaw, float pitch) { }

    /** A full camera pose: where, which way, the lens (0 = the player's own) and the tilt. */
    private record Pose(Vec3d pos, float yaw, float pitch, float fov, float roll) { }

    private static final float IN = 0.55f, CUT = 0.45f, OUT = 0.6f, FAR = 40;

    private static List<Net.Shot> shots = List.of();
    private static long startedAt, endedAt;
    private static boolean fade, active, blendOut, closedBlack;
    private static float total;
    private static String title = "", sub = "";
    private static int color;
    /** What the current shot is looking at (to keep the camera out of walls). */
    private static Vec3d lastLook;

    /** Keeps the camera out of the terrain: pulled in toward what it's looking at if a wall is in the way. */
    private static Vec3d clear(Vec3d pos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || lastLook == null || mc.player == null) return pos;
        var hit = mc.world.raycast(new net.minecraft.world.RaycastContext(lastLook, pos, net.minecraft.world.RaycastContext.ShapeType.VISUAL,
            net.minecraft.world.RaycastContext.FluidHandling.NONE, mc.player));
        if (hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS) return pos;
        Vec3d back = pos.subtract(lastLook);
        if (back.lengthSquared() < 1e-4) return pos;
        return hit.getPos().subtract(back.normalize().multiply(0.35));
    }

    /** The lens and tilt right now (0 when not overriding). */
    private static float curFov, curRoll;

    public static void reset() {
        active = false;
        shots = List.of();
        curFov = curRoll = 0;
    }

    public static boolean active() {
        return active;
    }

    public static void play(Net.Cutscene c) {
        shots = c.shots();
        fade = c.fade();
        title = c.title();
        sub = c.sub();
        blendOut = c.blendOut();
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

    /** How long it runs in all, the glide home included. */
    private static float length() {
        return total + (blendOut ? OUT : 0);
    }

    private static double smooth(double t) {
        t = MathHelper.clamp(t, 0, 1);
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double ease(int kind, double t) {
        t = MathHelper.clamp(t, 0, 1);
        return switch (kind) {
            // Quick, slow through the middle, quick: the moment hangs.
            case 1 -> t + 0.72 * Math.sin(2 * Math.PI * t) / (2 * Math.PI);
            // A whoosh: most of the move at once, then a long settle.
            case 2 -> 1 - Math.pow(1 - t, 3);
            default -> smooth(t);
        };
    }

    private static Pose poseOf(Net.Shot s, double t) {
        double k = ease(s.ease(), t);
        Vec3d pos;
        if (s.path() == 1) {
            // Round the point it ends up looking at: angle, radius and height each glide.
            double cx = s.mx(), cz = s.mz();
            double a0 = Math.atan2(s.fz() - cz, s.fx() - cx), a1 = Math.atan2(s.tz() - cz, s.tx() - cx);
            double da = MathHelper.wrapDegrees(Math.toDegrees(a1 - a0));
            double a = a0 + Math.toRadians(da) * k;
            double r0 = Math.hypot(s.fx() - cx, s.fz() - cz), r1 = Math.hypot(s.tx() - cx, s.tz() - cz);
            double r = MathHelper.lerp(k, r0, r1);
            pos = new Vec3d(cx + Math.cos(a) * r, MathHelper.lerp(k, s.fy(), s.ty()), cz + Math.sin(a) * r);
        } else {
            pos = new Vec3d(MathHelper.lerp(k, s.fx(), s.tx()), MathHelper.lerp(k, s.fy(), s.ty()), MathHelper.lerp(k, s.fz(), s.tz()));
        }
        Vec3d look = new Vec3d(MathHelper.lerp(k, s.lx(), s.mx()), MathHelper.lerp(k, s.ly(), s.my()), MathHelper.lerp(k, s.lz(), s.mz()));
        lastLook = look;
        Vec3d d = look.subtract(pos);
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (MathHelper.atan2(d.z, d.x) * MathHelper.DEGREES_PER_RADIAN) - 90f;
        float pitch = (float) -(MathHelper.atan2(d.y, h) * MathHelper.DEGREES_PER_RADIAN);
        float fov = s.fov0() > 0 && s.fov1() > 0 ? (float) MathHelper.lerp(k, s.fov0(), s.fov1()) : 0;
        float roll = (float) MathHelper.lerp(k, s.roll0(), s.roll1());
        return new Pose(pos, yaw, pitch, fov, roll);
    }

    private static Pose player(float td) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return null;
        return new Pose(mc.player.getCameraPosVec(td), mc.player.getYaw(td), mc.player.getPitch(td), 0, 0);
    }

    private static Pose blend(Pose a, Pose b, double k) {
        float fa = a.fov() > 0 ? a.fov() : defaultFov(), fb = b.fov() > 0 ? b.fov() : defaultFov();
        return new Pose(a.pos().lerp(b.pos(), k), MathHelper.lerpAngleDegrees((float) k, a.yaw(), b.yaw()),
            MathHelper.lerp((float) k, a.pitch(), b.pitch()), (float) MathHelper.lerp(k, fa, fb), (float) MathHelper.lerp(k, a.roll(), b.roll()));
    }

    private static float defaultFov() {
        return MinecraftClient.getInstance().options.getFov().getValue();
    }

    /** Where the camera is right now, or null when no cutscene is playing. */
    public static Cam camera() {
        return camera(1f);
    }

    public static Cam camera(float td) {
        if (!active || shots.isEmpty()) {
            curFov = curRoll = 0;
            return null;
        }
        float t = elapsed();
        Pose p = null;
        if (t >= total) {
            // Home: from the last frame back into your eyes.
            Net.Shot last = shots.get(shots.size() - 1);
            Pose end = poseOf(last, 1), me = player(td);
            p = blendOut && me != null ? blend(end, me, smooth((t - total) / OUT)) : end;
        } else {
            float left = t;
            for (int i = 0; i < shots.size(); i++) {
                Net.Shot s = shots.get(i);
                if (left <= s.seconds() || i == shots.size() - 1) {
                    p = poseOf(s, left / Math.max(0.01f, s.seconds()));
                    // Flowing in from where the camera was: your eyes, or the last shot's end.
                    Pose from = null;
                    float win = i == 0 ? IN : CUT;
                    if (left < win) {
                        from = i == 0 ? player(td) : poseOf(shots.get(i - 1), 1);
                        if (from != null && from.pos().distanceTo(p.pos()) > FAR) from = null;
                    }
                    if (from != null) p = blend(from, p, smooth(left / win));
                    break;
                }
                left -= s.seconds();
            }
        }
        if (p == null) return null;
        // Out of the walls (not in the last glide home, which ends in your own eyes).
        if (t < total) p = new Pose(clear(p.pos()), p.yaw(), p.pitch(), p.fov(), p.roll());
        curFov = p.fov();
        curRoll = p.roll();
        return new Cam(p.pos().x, p.pos().y, p.pos().z, p.yaw(), p.pitch());
    }

    /** The lens a cutscene wants, or 0. */
    public static float fov() {
        return active ? curFov : 0;
    }

    /** Tilts the view for a shot's roll. */
    public static void roll(MatrixStack m) {
        if (active && Math.abs(curRoll) > 0.01f) m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(curRoll));
    }

    /** Controls are held while it plays; the camera comes back when it ends. */
    public static void tick(MinecraftClient mc) {
        if (!active) return;
        if (mc.player == null || elapsed() > length()) {
            active = false;
            curFov = curRoll = 0;
            closedBlack = !blendOut;
            endedAt = Util.getMeasuringTimeMs();
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

    /** True where the next shot is far from the last: a real cut, masked by a blink. */
    private static float cutDip(float t) {
        float at = 0, a = 0;
        for (int i = 0; i < shots.size() - 1; i++) {
            at += shots.get(i).seconds();
            if (poseOf(shots.get(i), 1).pos().distanceTo(poseOf(shots.get(i + 1), 0).pos()) <= FAR) continue;
            float d = Math.abs(t - at);
            if (d < 0.14f) a = Math.max(a, 1 - d / 0.14f);
        }
        return a;
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        if (!active) {
            // After a cut away (a drop, a lift-off): open back up from black.
            if (closedBlack) {
                float since = (Util.getMeasuringTimeMs() - endedAt) / 1000f;
                if (since > 0.45f) closedBlack = false;
                else c.fill(0, 0, w, h, (int) (255 * (1 - since / 0.45f)) << 24);
            }
            return;
        }
        c.getMatrices().push();
        c.getMatrices().translate(0, 0, 900);
        drawBars(c, w, h);
        c.getMatrices().pop();
    }

    private static void drawBars(DrawContext c, int w, int h) {
        float t = elapsed(), len = length();
        float barK = (float) (smooth(t / 0.5f) * smooth((len - t) / 0.5f));
        int bar = (int) (h * 0.075f * barK);
        c.fill(0, 0, w, bar, 0xFF000000);
        c.fill(0, h - bar, w, h, 0xFF000000);
        if (fade && t < 2.2f) {
            // Waking up: black, a slow blink, then the world.
            float a = t < 0.8f ? 1 : t < 1.2f ? 1 - (t - 0.8f) / 0.4f * 0.7f : t < 1.5f ? 0.3f + (t - 1.2f) / 0.3f * 0.5f : 0.8f * (1 - (t - 1.5f) / 0.7f);
            c.fill(0, 0, w, h, (int) (255 * MathHelper.clamp(a, 0, 1)) << 24);
        }
        float dip = cutDip(t);
        if (dip > 0) c.fill(0, 0, w, h, (int) (255 * dip) << 24);
        // Cutting away somewhere else: close on black.
        if (!blendOut && len - t < 0.3f) c.fill(0, 0, w, h, (int) (255 * MathHelper.clamp(1 - (len - t) / 0.3f, 0, 1)) << 24);
        if (!title.isEmpty()) {
            // A small title low in the corner: a short rule, the name, a line under it.
            float in = MathHelper.clamp((t - 0.35f) / 0.4f, 0, 1), out = MathHelper.clamp((total - t - 0.2f) / 0.5f, 0, 1);
            int a = (int) (255 * in * out);
            if (a > 8) {
                int x = 22 + (int) ((1 - in) * -10), y = h - bar - (sub.isEmpty() ? 26 : 34);
                c.fill(x, y + 2, x + 2, y + (sub.isEmpty() ? 12 : 22), (a << 24) | (color & 0xFFFFFF));
                Ui.text(c, Ui.heading(title.toUpperCase()), x + 8, y, 1.1f, (a << 24) | 0xF2EDE2, false);
                if (!sub.isEmpty()) Ui.text(c, net.minecraft.text.Text.literal(sub), x + 8, y + 13, 0.75f, ((a * 3 / 4) << 24) | 0xC8C2B4, false);
            }
        }
    }
}
