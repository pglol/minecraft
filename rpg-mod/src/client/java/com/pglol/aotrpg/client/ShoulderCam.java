package com.pglol.aotrpg.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Util;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Over-the-shoulder combat camera. It comes in when you lock on to someone or something (and,
 * if you like, whenever you're in third person), sitting off one shoulder so your target and your
 * swing are both in view; Caps Lock swaps shoulders. It rides on a critically damped spring so it
 * never snaps, trails a touch behind your speed so flying on your gear feels fast, widens the
 * lens as you pick up speed, banks into hard turns, and pulls in off walls instead of clipping.
 */
public final class ShoulderCam {
    private ShoulderCam() {}

    /** The settings (Camera in the pause menu). */
    public static final class Settings {
        public boolean onLockOn = true;
        public boolean inThirdPerson = false;
        public float distance = 3.4f, side = 0.8f, height = 0.2f;
        /** 0 snappy .. 1 floaty. */
        public float smoothing = 0.45f;
        /** How far the camera trails your speed (0 none .. 1 a lot). */
        public float lag = 0.5f;
        /** Extra field of view at full speed, in degrees. */
        public float fovKick = 12f;
        /** Banking into turns (0 none .. 2 a lot). */
        public float bank = 1f;
    }

    public static Settings cfg = new Settings();
    public static KeyBinding swapKey;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("aot_rpg_camera.json");
    }

    public static void load() {
        try {
            if (Files.exists(file())) {
                Settings s = GSON.fromJson(Files.readString(file()), Settings.class);
                if (s != null) cfg = s;
            }
        } catch (Exception ignored) { }
    }

    public static void save() {
        try {
            Files.writeString(file(), GSON.toJson(cfg));
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ state

    private static float sideNow = 1, sideWant = 1;
    /** How far in the shoulder view is (0 your eyes .. 1 fully out), eased. */
    private static float weight;
    private static Vec3d offset, offsetVel = Vec3d.ZERO;
    private static float roll, fovNow, lastYaw;
    private static long lastFrame;
    private static boolean wanted;

    /** Whether the shoulder view should be up right now. */
    private static boolean want(MinecraftClient mc) {
        if (mc.player == null || com.pglol.aotrpg.client.story.CutscenePlayer.active() || mc.player.isSpectator()) return false;
        if (mc.player.isSleeping()) return false;
        Perspective per = mc.options.getPerspective();
        if (cfg.onLockOn && LockOn.locked() && per != Perspective.THIRD_PERSON_FRONT) return true;
        return cfg.inThirdPerson && per == Perspective.THIRD_PERSON_BACK;
    }

    /** Up (even partly, while easing in or out). */
    public static boolean active() {
        return weight > 0.001f;
    }

    public static void tick(MinecraftClient mc) {
        if (swapKey == null) return;
        while (swapKey.wasPressed()) {
            if (!active()) continue;
            sideWant = -sideWant;
            if (mc.player != null) mc.player.playSound(net.minecraft.sound.SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(), 0.25f, 1.6f);
        }
    }

    /** The shoulder camera for this frame, or null to leave the camera as it is. */
    public static com.pglol.aotrpg.client.story.CutscenePlayer.Cam camera(float td) {
        MinecraftClient mc = MinecraftClient.getInstance();
        long now = Util.getMeasuringTimeMs();
        float dt = lastFrame == 0 ? 0.016f : MathHelper.clamp((now - lastFrame) / 1000f, 0.001f, 0.1f);
        lastFrame = now;
        wanted = want(mc);
        // Ease in and out (about a third of a second each way).
        weight = MathHelper.clamp(weight + (wanted ? dt : -dt) / 0.32f, 0, 1);
        if (weight <= 0.001f || mc.player == null || mc.world == null) {
            offset = null;
            roll *= 0.8f;
            fovNow *= 0.8f;
            return null;
        }
        var p = mc.player;
        Vec3d eye = p.getCameraPosVec(td);
        float yaw = p.getYaw(td), pitch = p.getPitch(td);
        Vec3d fwd = Vec3d.fromPolar(pitch, yaw);
        float yr = yaw * MathHelper.RADIANS_PER_DEGREE;
        Vec3d right = new Vec3d(-MathHelper.cos(yr), 0, -MathHelper.sin(yr));
        Vec3d up = right.crossProduct(fwd).normalize();
        // Shoulders swap with a smooth slide across.
        sideNow += (sideWant - sideNow) * (1 - (float) Math.exp(-dt * 10));
        Vec3d want = up.multiply(cfg.height).add(right.multiply(cfg.side * sideNow)).subtract(fwd.multiply(cfg.distance));
        // Trailing your speed: flying on your gear pulls the camera back a little.
        Vec3d vel = p.getVelocity();
        Vec3d trail = vel.multiply(-cfg.lag * 2.2);
        if (trail.length() > 1.6) trail = trail.normalize().multiply(1.6);
        want = want.add(trail);
        // A critically damped spring toward where it wants to be (relative to your eyes, so it
        // keeps up at any speed and only the feel of it lags).
        if (offset == null) {
            offset = want;
            offsetVel = Vec3d.ZERO;
        }
        float omega = MathHelper.lerp(cfg.smoothing, 28f, 7f);
        Vec3d diff = offset.subtract(want);
        Vec3d acc = diff.multiply(-omega * omega).subtract(offsetVel.multiply(2 * omega));
        offsetVel = offsetVel.add(acc.multiply(dt));
        offset = offset.add(offsetVel.multiply(dt));
        // Off walls: pull in along the line from your head to the camera.
        Vec3d pivot = eye.add(up.multiply(Math.max(0, cfg.height) * 0.5));
        Vec3d target = eye.add(offset);
        var hit = mc.world.raycast(new RaycastContext(pivot, target, RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, p));
        if (hit.getType() != HitResult.Type.MISS) {
            Vec3d d = target.subtract(pivot);
            double len = Math.max(0.01, d.length());
            double ok = Math.max(0.2, hit.getPos().distanceTo(pivot) - 0.25);
            target = pivot.add(d.multiply(ok / len));
        }
        // Banking: lean into hard turns and fast sideways moves.
        float yawRate = MathHelper.wrapDegrees(yaw - lastYaw) / dt;
        lastYaw = yaw;
        double lateral = vel.dotProduct(right);
        float bankWant = MathHelper.clamp(-yawRate * 0.012f + (float) lateral * -6f, -9f, 9f) * cfg.bank;
        roll += (bankWant - roll) * (1 - (float) Math.exp(-dt * 6));
        // The lens widens with speed.
        double speed = vel.length();
        float kick = (float) MathHelper.clamp((speed - 0.25) / 1.4, 0, 1) * cfg.fovKick;
        fovNow += (kick - fovNow) * (1 - (float) Math.exp(-dt * 4));
        // In from (and back out to) your own eyes.
        float k = weight * weight * (3 - 2 * weight);
        Vec3d pos = eye.lerp(target, k);
        // Looking at exactly what you're aiming at, so the middle of the screen is where your
        // strike or grapple goes (not a line beside it).
        var aimHit = p.raycast(96, td, false);
        Vec3d aim = aimHit.getType() == HitResult.Type.MISS ? eye.add(fwd.multiply(96)) : aimHit.getPos();
        if (mc.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult eh && eh.getPos().squaredDistanceTo(eye) < aim.squaredDistanceTo(eye)) {
            aim = eh.getPos();
        }
        Vec3d to = aim.subtract(pos);
        float camYaw = yaw, camPitch = pitch;
        if (to.lengthSquared() > 4) {
            double hz = Math.sqrt(to.x * to.x + to.z * to.z);
            float ay = (float) (MathHelper.atan2(to.z, to.x) * MathHelper.DEGREES_PER_RADIAN) - 90f;
            float ap = (float) -(MathHelper.atan2(to.y, hz) * MathHelper.DEGREES_PER_RADIAN);
            camYaw = MathHelper.lerpAngleDegrees(k, yaw, ay);
            camPitch = MathHelper.lerp(k, pitch, ap);
        }
        return new com.pglol.aotrpg.client.story.CutscenePlayer.Cam(pos.x, pos.y, pos.z, camYaw, camPitch);
    }

    /** The bank, applied to the view. */
    public static void roll(MatrixStack m) {
        if (active() && Math.abs(roll) > 0.02f) m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(roll * weight));
    }

    /** A crosshair when the view isn't first person (vanilla draws none then). */
    public static void crosshair(net.minecraft.client.gui.DrawContext c, net.minecraft.client.render.RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!active() || mc.options.hudHidden || mc.options.getPerspective().isFirstPerson() || mc.currentScreen != null) return;
        int x = c.getScaledWindowWidth() / 2, y = c.getScaledWindowHeight() / 2;
        int a = (int) (220 * weight) << 24;
        c.fill(x - 4, y, x - 1, y + 1, a | 0xFFFFFF);
        c.fill(x + 2, y, x + 5, y + 1, a | 0xFFFFFF);
        c.fill(x, y - 4, x + 1, y - 1, a | 0xFFFFFF);
        c.fill(x, y + 2, x + 1, y + 5, a | 0xFFFFFF);
        c.fill(x, y, x + 1, y + 1, a | 0xFFFFFF);
    }

    /** Degrees to add to the field of view. */
    public static float fovKick() {
        return active() ? fovNow * weight : 0;
    }
}
