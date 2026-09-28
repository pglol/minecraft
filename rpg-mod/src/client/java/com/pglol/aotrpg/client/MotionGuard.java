package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;

/**
 * Crouching in mid-air used to freeze you in place for a moment and kill all your momentum (the
 * AoT mod treats it as a brake). A fall you're in stays a fall: if your motion dies right as you
 * crouch in the air, it carries on as it would have (gravity and air drag), until you land, let
 * go of crouch, or something else really moves you (a hook pulling you, say).
 */
public final class MotionGuard {
    private MotionGuard() {}

    private static Vec3d lastPos, lastVel = Vec3d.ZERO, carry;
    private static boolean sneakWas;
    private static int sinceSneak = 99;

    public static void tick(MinecraftClient mc) {
        var p = mc.player;
        if (p == null || p.hasVehicle() || p.getAbilities().flying || p.isSpectator()) {
            lastPos = null;
            carry = null;
            return;
        }
        boolean sneak = mc.options.sneakKey.isPressed();
        sinceSneak = sneak && !sneakWas ? 0 : sinceSneak + 1;
        sneakWas = sneak;
        Vec3d pos = p.getPos();
        if (lastPos != null) {
            double moved = pos.distanceTo(lastPos);
            if (carry != null) {
                // Keep carrying while crouched in the air and nothing else is moving us.
                if (p.isOnGround() || !sneak || p.isTouchingWater() || moved > carry.length() * 0.6) {
                    carry = null;
                } else {
                    carry = new Vec3d(carry.x * 0.91, (carry.y - 0.08) * 0.98, carry.z * 0.91);
                    p.move(MovementType.SELF, carry);
                    p.setVelocity(carry);
                    p.fallDistance += (float) Math.max(0, -carry.y);
                }
            } else if (sneak && sinceSneak <= 4 && !p.isOnGround() && !p.isTouchingWater()
                && lastVel.length() > 0.35 && moved < 0.05) {
                // Just crouched in the air and came to a dead stop: pick the motion back up.
                carry = lastVel;
            }
        }
        lastPos = p.getPos();
        lastVel = carry != null ? carry : p.getVelocity();
    }
}
