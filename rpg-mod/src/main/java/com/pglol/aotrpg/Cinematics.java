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
        int style = (int) (Math.random() * 3);
        // 1. The opening, one of three: a high sweep in, a low dolly along the ground looking up at
        // what's coming, or straight down from above, turning as it sinks.
        Vec3d f = focus.add(0, 1.5, 0);
        if (style == 0) shots.add(shot(polar(focus, a0, 26, 16), polar(focus, a0 + 0.9, 20, 11), f.add(0, 3, 0), f, 3.5f));
        else if (style == 1) shots.add(shot(polar(focus, a0, 30, 0.8), polar(focus, a0 + 0.15, 14, 1.2), f.add(0, 8, 0), f.add(0, 2, 0), 3.2f));
        else shots.add(shot(focus.add(0.5, 42, 0.5), polar(focus, a0, 6, 18), polar(focus, a0 + 1.4, 3, 0), f, 3.4f));
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
        // 3. Back to the viewers, one of three: dropping in behind the first one, facing them, or a
        // wide turn round the whole group.
        ServerPlayerEntity v0 = viewers.get(0);
        double vy = Math.toRadians(v0.getYaw());
        Vec3d fwd = new Vec3d(-Math.sin(vy), 0, Math.cos(vy));
        int end = (int) (Math.random() * 3);
        if (end == 0) {
            Vec3d behind = v0.getPos().add(fwd.multiply(-5)).add(0, 4, 0);
            Vec3d shoulder = v0.getPos().add(fwd.multiply(-2.5)).add(0, 2.2, 0);
            shots.add(shot(behind, shoulder, v0.getEyePos().add(0, -0.2, 0), v0.getEyePos().add(fwd.multiply(6)), 1.8f));
        } else if (end == 1) {
            Vec3d front = v0.getEyePos().add(fwd.multiply(4)).add(0, 0.3, 0);
            shots.add(shot(front, v0.getEyePos().add(fwd.multiply(2.2)), v0.getEyePos(), v0.getEyePos(), 1.8f));
        } else {
            Vec3d mid = v0.getPos();
            shots.add(shot(polar(mid, a0 + 2, 12, 6), polar(mid, a0 + 3, 9, 3), mid.add(0, 1, 0), mid.add(0, 1.5, 0), 2.2f));
        }
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

    /** Plays authored shots to the viewers (kept safe meanwhile). Returns how long it runs, in ticks. */
    public static int play(List<ServerPlayerEntity> viewers, List<Net.Shot> shots, String title, String sub, int color) {
        float secs = 0;
        for (Net.Shot s : shots) secs += s.seconds();
        int ticks = (int) (secs * 20) + 10;
        Net.Cutscene c = new Net.Cutscene(shots, false, title, sub, color);
        for (ServerPlayerEntity p : viewers) {
            if (ServerPlayNetworking.canSend(p, Net.Cutscene.ID)) ServerPlayNetworking.send(p, c);
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, ticks + 20, 4, false, false, false));
        }
        return ticks;
    }

    /**
     * Boarding a balloon. The opening says where you are: rising out of the cloud sea to find the
     * balloon (first boarding), a far flyby with the sun behind it, or, back from a run, looking up
     * from under the basket as you climb aboard. Then the burner, then the camera settles over the
     * bench as your character takes a seat. o: the basket floor's middle; seat: the bench block.
     */
    public static List<Net.Shot> boarding(Vec3d o, Vec3d seat, Vec3d facing, boolean returning) {
        List<Net.Shot> shots = new ArrayList<>();
        double a = Math.random() * Math.PI * 2;
        Vec3d balloon = o.add(0, 14, 0);
        if (returning) {
            shots.add(shot(o.add(2, -14, 7), o.add(1, -4, 6), o.add(0, 1, 0), o.add(0, 6, 0), 2.6f));
        } else if (Math.random() < 0.5) {
            shots.add(shot(polar(o, a, 42, -28), polar(o, a + 0.35, 30, -8), balloon.add(0, -6, 0), balloon, 3.2f));
        } else {
            shots.add(shot(polar(o, a, 75, 4), polar(o, a + 0.25, 50, 10), balloon, balloon.add(0, -4, 0), 3.2f));
        }
        // The burner: up through the mouth of the envelope, the flame and the silk above it.
        shots.add(shot(o.add(2.2, 2.2, 1.5), o.add(-1.5, 2.8, 2), o.add(0, 4.5, 0), o.add(0, 12, 0), 2.2f));
        // Over the bench: the pan that watches you sit down.
        Vec3d side = new Vec3d(-facing.z, 0, facing.x);
        Vec3d head = seat.add(0, 1.2, 0);
        shots.add(shot(head.add(facing.multiply(4)).add(side.multiply(2.5)).add(0, 1.6, 0),
            head.add(facing.multiply(2.6)).add(side.multiply(-1.2)).add(0, 0.4, 0), head.add(side.multiply(1.5)), head, 3.4f));
        return shots;
    }

    /** The drop: the burner roars, the camera leans over the rail into the clouds, then pulls away wide as they go. */
    public static List<Net.Shot> drop(Vec3d o) {
        List<Net.Shot> shots = new ArrayList<>();
        double a = Math.random() * Math.PI * 2;
        shots.add(shot(o.add(1.6, 3.2, 1.4), o.add(-1, 4.4, 1), o.add(0, 5.5, 0), o.add(0, 10, 0), 1.8f));
        shots.add(shot(o.add(0, 2.6, -3), o.add(0, 3.2, -5.6), o.add(0, -8, -12), o.add(0, -40, -9), 2.2f));
        shots.add(shot(polar(o, a, 20, 3), polar(o, a + 0.5, 28, 9), o.add(0, 1, 0), o.add(0, 6, 0), 2.2f));
        return shots;
    }

    /** Landing: from the ground, looking up at the squad falling out of the sky toward it. */
    public static List<Net.Shot> landing(Vec3d ground, Vec3d diver) {
        List<Net.Shot> shots = new ArrayList<>();
        shots.add(shot(ground.add(7, 2, 5), ground.add(4, 5, 3), diver, diver.add(0, -14, 0), 2.8f));
        return shots;
    }

    /** Extracting: rising off the flare into the sky as the squad is lifted out. */
    public static List<Net.Shot> extract(Vec3d at) {
        List<Net.Shot> shots = new ArrayList<>();
        shots.add(shot(at.add(3.5, 1.2, 3), at.add(1.5, 16, 1), at.add(0, 1.4, 0), at.add(0, 30, 0), 2.4f));
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

    private static Net.Shot shot(Vec3d from, Vec3d to, Vec3d look, Vec3d lookTo, float secs) {
        return new Net.Shot(from.x, from.y, from.z, to.x, to.y, to.z, look.x, look.y, look.z, lookTo.x, lookTo.y, lookTo.z, secs);
    }
}
