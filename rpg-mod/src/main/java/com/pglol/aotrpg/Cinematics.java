package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Short intros for the moments that deserve one: a raid, a boss arriving, a town under attack, a
 * duel. Kept quick. The camera lifts out of your eyes into one bold opening move (a dolly zoom that
 * warps the world behind the threat, a crane up over your shoulder, or a tilted orbit), cuts to at
 * most two fighters shot for scale, then flies straight back into your eyes and it begins.
 * Everyone watching is kept safe while it plays, and the titans in it hold still.
 */
public final class Cinematics {
    private Cinematics() {}

    private static final int SMOOTH = 0, RAMP = 1, WHOOSH = 2, LINE = 0, ORBIT = 1;

    /** Plays an intro to the viewers. Returns how long it runs, in ticks. */
    public static int intro(List<ServerPlayerEntity> viewers, Vec3d focus, List<? extends Entity> subjects, String title, String sub, int color) {
        if (viewers.isEmpty()) return 0;
        List<Net.Shot> shots = new ArrayList<>();
        ServerPlayerEntity v0 = viewers.get(0);
        Vec3d eye = v0.getEyePos();
        Vec3d f = focus.add(0, 2, 0);
        Vec3d toward = f.subtract(eye).multiply(1, 0, 1);
        Vec3d dir = toward.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : toward.normalize();
        Vec3d side = new Vec3d(-dir.z, 0, dir.x);
        double dist = Math.max(8, Math.sqrt(toward.lengthSquared()));
        int style = (int) (Math.random() * 3);
        if (style == 0) {
            // The dolly zoom: pushing in on the threat while the lens widens, so it holds its size
            // and the world behind it stretches away.
            Vec3d a = eye.add(side.multiply(1.2)).add(0, 0.6, 0), b = eye.add(dir.multiply(Math.min(dist * 0.55, 30))).add(0, 1.5, 0);
            shots.add(shot(a, b, f, f, 2.6f, 32, 88, 0, 0, LINE, SMOOTH));
        } else if (style == 1) {
            // The crane: off your shoulder, up and over, the horizon levelling out as the threat appears.
            Vec3d a = eye.add(dir.multiply(-2.2)).add(side.multiply(0.9)).add(0, 0.3, 0);
            Vec3d b = eye.add(dir.multiply(dist * 0.25)).add(0, 12, 0);
            shots.add(shot(a, b, eye.add(dir.multiply(6)), f, 2.8f, 70, 60, -9, 0, LINE, WHOOSH));
        } else {
            // The orbit: round the threat, low and tilted, a quarter turn.
            double a0 = Math.atan2(eye.z - f.z, eye.x - f.x);
            Vec3d a = polar(f, a0, 18, 5), b = polar(f, a0 + 1.7, 14, 2);
            shots.add(shot(a, b, f, f, 3.0f, 62, 55, 8, -3, ORBIT, SMOOTH));
        }
        // Up to two fighters, shot for scale: titans from their feet looking up, players in a quick arc.
        int n = 0;
        for (Entity e : subjects) {
            if (e == null || !e.isAlive() || n >= 2) continue;
            n++;
            double h = e.getHeight();
            boolean big = h > 3;
            double yaw = Math.toRadians(e.getYaw());
            Vec3d front = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw)), sd = new Vec3d(Math.cos(yaw), 0, Math.sin(yaw));
            Vec3d head = e.getPos().add(0, h * 0.88, 0);
            if (big) {
                Vec3d a = e.getPos().add(front.multiply(Math.max(5, h * 0.7))).add(sd.multiply(2.5)).add(0, 0.6, 0);
                Vec3d b = a.add(front.multiply(-1.5)).add(sd.multiply(-2));
                shots.add(shot(a, b, head.add(0, -h * 0.25, 0), head, 1.8f, 58, 44, 5, 0, LINE, RAMP));
            } else {
                double a0 = Math.atan2(front.z, front.x);
                shots.add(shot(polar(e.getPos(), a0 - 0.5, 2.8, h * 0.8), polar(e.getPos(), a0 + 0.6, 2.4, h * 0.9), head, head, 1.5f, 55, 50, 0, 0, ORBIT, SMOOTH));
            }
        }
        int ticks = send(viewers, shots, title, sub, color, true);
        // The titans in it (and near it) hold still until it's over.
        ServerWorld w = v0.getServerWorld();
        for (Entity e : w.getOtherEntities(null, new Box(focus, focus).expand(64), AotRpg::isTitan)) {
            if (e instanceof LivingEntity le) le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, ticks, 9, false, false, false));
        }
        return ticks;
    }

    /** Plays authored shots that end back in the viewer's eyes. Returns how long it runs, in ticks. */
    public static int play(List<ServerPlayerEntity> viewers, List<Net.Shot> shots, String title, String sub, int color) {
        return send(viewers, shots, title, sub, color, true);
    }

    /** Plays shots that end somewhere else (a drop, a lift-off): they close on a quick dip to black. */
    public static int cut(List<ServerPlayerEntity> viewers, List<Net.Shot> shots, String title, String sub, int color) {
        return send(viewers, shots, title, sub, color, false);
    }

    private static int send(List<ServerPlayerEntity> viewers, List<Net.Shot> shots, String title, String sub, int color, boolean blendOut) {
        float secs = 0;
        for (Net.Shot s : shots) secs += s.seconds();
        // The glide back into your eyes takes a moment more.
        int ticks = (int) (secs * 20) + (blendOut ? 14 : 4);
        Net.Cutscene c = new Net.Cutscene(shots, false, title, sub, color, blendOut);
        for (ServerPlayerEntity p : viewers) {
            if (ServerPlayNetworking.canSend(p, Net.Cutscene.ID)) ServerPlayNetworking.send(p, c);
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, ticks + 20, 4, false, false, false));
        }
        return ticks;
    }

    /**
     * The drop. Over the rail: the lens stretches as the camera tips forward over the edge, then it
     * goes over, spinning down through the air after you.
     */
    public static List<Net.Shot> drop(Vec3d o) {
        List<Net.Shot> shots = new ArrayList<>();
        shots.add(shot(o.add(0, 2.4, -3.8), o.add(0, 2.2, -6.2), o.add(0, 0, -12), o.add(0, -40, -12), 1.5f, 68, 104, 0, -12, LINE, WHOOSH));
        shots.add(shot(o.add(0.5, -3, -7), o.add(0.5, -46, -9), o.add(0, -30, -10), o.add(0, -90, -11), 1.2f, 104, 92, -12, -70, LINE, SMOOTH));
        return shots;
    }

    /** Extracting: a rising spiral off the flare as you're lifted out. */
    public static List<Net.Shot> extract(Vec3d at) {
        List<Net.Shot> shots = new ArrayList<>();
        Vec3d c = at.add(0, 1.4, 0);
        shots.add(shot(polar(at, 0.4, 3.5, 1.2), polar(at, 2.6, 9, 22), c, c.add(0, 8, 0), 2.2f, 60, 85, 0, 6, ORBIT, WHOOSH));
        return shots;
    }

    /** Fallen: a slow tilted spiral up and away from where you lie. */
    public static List<Net.Shot> fallen(Vec3d spot) {
        List<Net.Shot> shots = new ArrayList<>();
        double a = Math.random() * Math.PI * 2;
        shots.add(shot(polar(spot, a, 2.5, 1.8), polar(spot, a + 1.3, 14, 24), spot.add(0, 0.4, 0), spot, 3.2f, 50, 75, 12, 0, ORBIT, SMOOTH));
        return shots;
    }

    /** The players within r of a spot. */
    public static List<ServerPlayerEntity> near(ServerWorld w, Vec3d at, double r) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity p : new java.util.ArrayList<>(w.getPlayers())) if (p.getPos().squaredDistanceTo(at) < r * r && !p.isSpectator()) out.add(p);
        return out;
    }

    private static Vec3d polar(Vec3d c, double a, double r, double h) {
        return c.add(Math.cos(a) * r, h, Math.sin(a) * r);
    }

    private static Net.Shot shot(Vec3d from, Vec3d to, Vec3d look, Vec3d lookTo, float secs, float fov0, float fov1, float roll0, float roll1, int path, int ease) {
        return new Net.Shot(from.x, from.y, from.z, to.x, to.y, to.z, look.x, look.y, look.z, lookTo.x, lookTo.y, lookTo.z, secs,
            fov0, fov1, roll0, roll1, path, ease);
    }
}
