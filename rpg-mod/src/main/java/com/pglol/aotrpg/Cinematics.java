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
 * Intros for the moments that deserve one: a raid, a boss arriving, a town under attack, a duel.
 * The camera sweeps in over the place, cuts to each fighter (players and titans) up close with the
 * event's title across the screen, then pulls back to you, and it begins. Everyone watching is
 * kept safe while it plays, and the titans in it hold still until it's over.
 */
public final class Cinematics {
    private Cinematics() {}

    /** Plays an intro to the viewers. Returns how long it runs, in ticks. */
    public static int intro(List<ServerPlayerEntity> viewers, Vec3d focus, List<? extends Entity> subjects, String title, String sub, int color) {
        if (viewers.isEmpty()) return 0;
        List<Net.Shot> shots = new ArrayList<>();
        double a0 = Math.random() * Math.PI * 2;
        // 1. Sweeping in high over the place.
        Vec3d f = focus.add(0, 1.5, 0);
        shots.add(shot(polar(focus, a0, 26, 16), polar(focus, a0 + 0.9, 20, 11), f.add(0, 3, 0), f, 3.5f));
        // 2. Each fighter up close (up to four), titans shot from low down, looking up.
        int n = 0;
        for (Entity e : subjects) {
            if (e == null || !e.isAlive() || n >= 4) continue;
            n++;
            double h = e.getHeight();
            boolean big = h > 3;
            double yaw = Math.toRadians(e.getYaw());
            Vec3d front = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw)), side = new Vec3d(Math.cos(yaw), 0, Math.sin(yaw));
            Vec3d head = e.getPos().add(0, h * (big ? 0.85 : 0.9), 0);
            double d = big ? Math.max(6, h * 0.9) : 2.8;
            Vec3d from = e.getPos().add(front.multiply(d)).add(side.multiply(big ? 3 : 1.2)).add(0, big ? 1.6 : h * 0.8, 0);
            Vec3d to = from.add(side.multiply(big ? -2.5 : -1.4)).add(0, big ? 0.6 : 0.1, 0);
            shots.add(shot(from, to, head.add(0, big ? -0.5 : 0, 0), head, big ? 2.4f : 1.9f));
        }
        // 3. Back to the viewers: dropping in behind the first one.
        ServerPlayerEntity v0 = viewers.get(0);
        double vy = Math.toRadians(v0.getYaw());
        Vec3d behind = v0.getPos().add(Math.sin(vy) * 5, 4, -Math.cos(vy) * 5);
        Vec3d shoulder = v0.getPos().add(Math.sin(vy) * 2.5, 2.2, -Math.cos(vy) * 2.5);
        shots.add(shot(behind, shoulder, v0.getEyePos().add(0, -0.2, 0), v0.getEyePos().add(-Math.sin(vy) * 6, 0, Math.cos(vy) * 6), 1.8f));
        float secs = 0;
        for (Net.Shot s : shots) secs += s.seconds();
        int ticks = (int) (secs * 20) + 10;
        Net.Cutscene c = new Net.Cutscene(shots, false, title, sub, color);
        for (ServerPlayerEntity p : viewers) {
            if (ServerPlayNetworking.canSend(p, Net.Cutscene.ID)) ServerPlayNetworking.send(p, c);
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, ticks + 20, 4, false, false, false));
        }
        // The titans in it (and near it) hold still until it's over.
        ServerWorld w = v0.getServerWorld();
        for (Entity e : w.getOtherEntities(null, new Box(focus, focus).expand(64), AotRpg::isTitan)) {
            if (e instanceof LivingEntity le) {
                le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, ticks, 9, false, false, false));
                le.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, ticks, 9, false, false, false));
            }
        }
        return ticks;
    }

    /** The players within r of a spot. */
    public static List<ServerPlayerEntity> near(ServerWorld w, Vec3d at, double r) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity p : w.getPlayers()) if (p.getPos().squaredDistanceTo(at) < r * r && !p.isSpectator()) out.add(p);
        return out;
    }

    private static Vec3d polar(Vec3d c, double a, double r, double h) {
        return c.add(Math.cos(a) * r, h, Math.sin(a) * r);
    }

    private static Net.Shot shot(Vec3d from, Vec3d to, Vec3d look, Vec3d lookTo, float secs) {
        return new Net.Shot(from.x, from.y, from.z, to.x, to.y, to.z, look.x, look.y, look.z, lookTo.x, lookTo.y, lookTo.z, secs);
    }
}
