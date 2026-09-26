package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * ODM double jumps on your side. Press jump again in the air with the gear on: just after leaving
 * the ground it fires you straight up (or flips you the way you're pressing), later it dashes you
 * where you look, leaned by the keys you hold. You move at once; the server checks and spends the
 * gas and shows everyone else the move. Also keeps each player's flip or lean for drawing.
 */
public final class OdmMoves {
    private OdmMoves() {}

    public static final int DASH = 0, UP = 1, FLIP = 2;
    private static final long COOLDOWN_MS = 1000, EARLY_MS = 350, FLIP_MS = 560, LEAN_MS = 460;

    public static boolean gear;
    public static float gas = -1;
    private static long cooldownUntil, leftGround;
    private static int airUsed;
    private static boolean jumpWas, wasOnGround = true, releasedInAir;

    /** A move being played out on someone: what kind, which way (world yaw), when it began. */
    public record Anim(int kind, float yaw, long at, float viewYaw) { }

    private static final Map<Integer, Anim> anims = new HashMap<>();

    public static void onState(Net.OdmState s) {
        gear = s.gear();
        gas = s.gas();
    }

    public static void onMove(Net.OdmMove m) {
        anims.put(m.entity(), new Anim(m.kind(), m.yaw(), Util.getMeasuringTimeMs(), m.yaw()));
    }

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity pl = mc.player;
        if (pl == null) {
            anims.clear();
            return;
        }
        long now = Util.getMeasuringTimeMs();
        anims.values().removeIf(a -> now - a.at() > 1000);
        boolean onGround = pl.isOnGround();
        if (onGround || pl.isTouchingWater() || pl.hasVehicle() || pl.isClimbing()) {
            airUsed = 0;
            releasedInAir = false;
        }
        boolean airborneBefore = !wasOnGround;
        if (wasOnGround && !onGround) leftGround = now;
        wasOnGround = onGround;
        boolean jump = mc.options.jumpKey.isPressed();
        boolean press = jump && !jumpWas;
        jumpWas = jump;
        // The press that makes the ordinary jump doesn't count: you must already have been in the
        // air last tick, and have let go of jump since leaving the ground.
        if (!jump && !onGround) releasedInAir = true;
        if (!press || !airborneBefore || !releasedInAir || mc.currentScreen != null || onGround) return;
        if (pl.getAbilities().allowFlying || pl.hasVehicle() || pl.isTouchingWater() || pl.isClimbing() || pl.isFallFlying()
            || TitanState.grabbed() || TitanState.shifted() || !gear) return;
        if (gas == 0) {
            pl.sendMessage(Text.literal("Out of gas.").formatted(Formatting.RED), true);
            return;
        }
        if (now < cooldownUntil || airUsed >= 2) return;

        // Which way the keys point, in the world.
        float fwd = pl.input.movementForward, side = pl.input.movementSideways;
        double yaw = Math.toRadians(pl.getYaw());
        Vec3d f = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw)), left = new Vec3d(Math.cos(yaw), 0, Math.sin(yaw));
        Vec3d wish = f.multiply(fwd).add(left.multiply(side));
        boolean moving = wish.lengthSquared() > 0.01;
        if (moving) wish = wish.normalize();
        Vec3d v = pl.getVelocity();
        boolean early = now - leftGround < EARLY_MS;
        int kind;
        Vec3d nv;
        float dirYaw;
        if (early && !moving) {
            // Straight up off the ground, carrying a little of what you had.
            kind = UP;
            nv = new Vec3d(v.x * 0.8, 1.05, v.z * 0.8);
            dirYaw = pl.getYaw();
        } else if (early) {
            // A flip the way you're pressing: up and over, landing moving that way.
            kind = FLIP;
            nv = new Vec3d(wish.x * 0.95 + v.x * 0.3, 0.8, wish.z * 0.95 + v.z * 0.3);
            dirYaw = (float) Math.toDegrees(Math.atan2(-wish.x, wish.z));
        } else {
            // A dash where you look, leaned by the keys; keeps some momentum so it flows.
            kind = DASH;
            Vec3d look = pl.getRotationVec(1f);
            Vec3d dir = moving ? look.add(wish.multiply(0.6)).normalize() : look;
            nv = v.multiply(0.3).add(dir.multiply(1.3));
            nv = new Vec3d(nv.x, Math.max(nv.y, dir.y * 1.3 + 0.3), nv.z);
            if (nv.length() > 2.0) nv = nv.normalize().multiply(2.0);
            dirYaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        }
        pl.setVelocity(nv);
        cooldownUntil = now + COOLDOWN_MS;
        airUsed++;
        ClientPlayNetworking.send(new Net.OdmJump(kind, dirYaw));
        anims.put(pl.getId(), new Anim(kind, dirYaw, now, pl.getYaw()));
    }

    /** The move playing on an entity, or null. */
    public static Anim anim(int entityId) {
        Anim a = anims.get(entityId);
        if (a == null) return null;
        long age = Util.getMeasuringTimeMs() - a.at();
        return age > (a.kind() == FLIP ? FLIP_MS : LEAN_MS) ? null : a;
    }

    /** 0..1 through the move, eased. */
    public static float progress(Anim a) {
        float t = (Util.getMeasuringTimeMs() - a.at()) / (float) (a.kind() == FLIP ? FLIP_MS : LEAN_MS);
        t = MathHelper.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** How far the body is turned about its tumble axis right now, in degrees. */
    public static float bodyAngle(Anim a) {
        float e = progress(a);
        return switch (a.kind()) {
            case FLIP -> 360 * e;
            case DASH -> (float) Math.sin(Math.PI * e) * 55;
            default -> 0;
        };
    }

    /** Two small marks under the crosshair while airborne with the gear: jumps left (dim while cooling down). */
    public static void renderHud(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity pl = mc.player;
        if (pl == null || !gear || pl.isOnGround() || mc.options.hudHidden || mc.currentScreen != null || pl.getAbilities().allowFlying) return;
        int cx = c.getScaledWindowWidth() / 2, cy = c.getScaledWindowHeight() / 2 + 10;
        boolean cooling = Util.getMeasuringTimeMs() < cooldownUntil;
        for (int i = 0; i < 2; i++) {
            int x = cx - 5 + i * 7;
            boolean left = i >= airUsed && gas != 0;
            int col = !left ? 0x50FFFFFF : cooling ? 0x90B8955A : 0xE0E0B96A;
            c.fill(x, cy, x + 4, cy + 2, col);
        }
    }
}
