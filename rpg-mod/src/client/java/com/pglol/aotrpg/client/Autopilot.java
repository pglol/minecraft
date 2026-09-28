package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import com.pglol.aotrpg.client.story.CutscenePlayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * The character walking on its own (to a seat, in a cutscene): the player's controls are held and
 * the character is steered to the spot. Also the input lock itself: while a cutscene plays or the
 * character is being walked, nothing the player presses moves it.
 */
public final class Autopilot {
    private Autopilot() {}

    private static Net.Autopilot target;
    private static long startedAt;

    public static void on(Net.Autopilot a) {
        target = a;
        startedAt = Util.getMeasuringTimeMs();
    }

    public static boolean active() {
        if (target == null) return false;
        var mc = MinecraftClient.getInstance();
        // Done once seated, or after a generous while.
        if (mc.player == null || mc.player.hasVehicle() || Util.getMeasuringTimeMs() - startedAt > target.delayMs() + 9000) {
            target = null;
            return false;
        }
        return true;
    }

    /** Controls held: in a cutscene, while being walked, or seated in the balloon's lobby. */
    public static boolean locked() {
        return CutscenePlayer.active() || active();
    }

    /** Called after the keyboard is read: clears it when locked, then steers if walking. */
    public static void steer(Input in) {
        if (!locked()) return;
        in.movementForward = 0;
        in.movementSideways = 0;
        in.pressingForward = in.pressingBack = in.pressingLeft = in.pressingRight = false;
        in.jumping = false;
        in.sneaking = false;
        var mc = MinecraftClient.getInstance();
        if (!active() || mc.player == null || Util.getMeasuringTimeMs() - startedAt < target.delayMs()) return;
        double dx = target.x() - mc.player.getX(), dz = target.z() - mc.player.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 0.35) return;
        float want = (float) (MathHelper.atan2(dz, dx) * MathHelper.DEGREES_PER_RADIAN) - 90f;
        float yaw = mc.player.getYaw() + MathHelper.wrapDegrees(want - mc.player.getYaw()) * 0.35f;
        mc.player.setYaw(yaw);
        mc.player.setHeadYaw(yaw);
        mc.player.setBodyYaw(yaw);
        in.movementForward = (float) Math.min(1, d);
        in.pressingForward = true;
    }
}
