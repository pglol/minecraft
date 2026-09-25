package com.pglol.aotrpg.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Lock-on. Press the key to lock onto what you are looking at; the camera follows it. Drag the
 * mouse towards another target (or, on an ordinary titan, towards its nape or eyes) and the lock
 * leans over to it. Shifters (and anything, while you are shifted yourself) are locked as a whole
 * body. Press the key again, or lose the target, to unlock.
 */
public final class LockOn {
    private LockOn() {}

    public static KeyBinding key;
    private static final String[] SHIFTERS = {"attack", "armored", "armoured", "colossal", "female", "beast", "cart", "jaw", "warhammer",
        "founding", "shifter", "ogre", "triple"};
    private static final double RANGE = 56;

    public enum Part { BODY, NAPE, EYE }

    private static Entity target;
    private static Part part = Part.BODY;
    private static long lastFrame;

    public static boolean locked() {
        return target != null;
    }

    private static String path(Entity e) {
        return Registries.ENTITY_TYPE.getId(e.getType()).getPath();
    }

    static boolean titan(Entity e) {
        return path(e).contains("titan");
    }

    static boolean titanPart(Entity e) {
        String p = path(e);
        return p.contains("nape") || p.contains("eye") || p.contains("grab") || p.contains("hand") || p.contains("leg");
    }

    static boolean shifter(Entity e) {
        String p = path(e);
        for (String s : SHIFTERS) if (p.contains(s)) return true;
        return false;
    }

    private static boolean candidate(MinecraftClient mc, Entity e) {
        if (e == mc.player || !e.isAlive() || !(e instanceof LivingEntity) || e.isInvisible()) return false;
        if (titanPart(e)) return false;
        return e instanceof PlayerEntity || e instanceof Monster || titan(e);
    }

    public static void pressed(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        if (target != null) {
            target = null;
            return;
        }
        Vec3d eye = mc.player.getEyePos(), look = mc.player.getRotationVec(1f);
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : mc.world.getEntities()) {
            if (!candidate(mc, e)) continue;
            Vec3d c = e.getBoundingBox().getCenter();
            double d = c.distanceTo(eye);
            if (d > RANGE + e.getWidth()) continue;
            Vec3d dir = c.subtract(eye).normalize();
            double dot = dir.dotProduct(look);
            if (dot < 0.82) continue; // within about 35 degrees of the crosshair
            double score = (1 - dot) * 60 + d * 0.15;
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        target = best;
        part = best != null && titan(best) && !wholeBody(best) ? Part.NAPE : Part.BODY;
        if (best != null) {
            lastYaw = mc.player.getYaw();
            lastPitch = mc.player.getPitch();
            dragYaw = dragPitch = 0;
        }
    }

    private static float lastYaw, lastPitch, dragYaw, dragPitch;

    private record Aim(Entity entity, Part part) { }

    /** Yaw/pitch from the eye to a point. */
    private static float[] angles(Vec3d eye, Vec3d at) {
        Vec3d to = at.subtract(eye);
        float yaw = (float) (MathHelper.atan2(to.z, to.x) * 180 / Math.PI) - 90;
        float pitch = (float) -(MathHelper.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)) * 180 / Math.PI);
        return new float[] {yaw, pitch};
    }

    private static Vec3d aimOf(MinecraftClient mc, Aim a, float td) {
        Entity keepT = target;
        Part keepP = part;
        target = a.entity();
        part = a.part();
        Vec3d v = aim(mc, td);
        target = keepT;
        part = keepP;
        return v;
    }

    /** The mouse was dragged: lean the lock to the nearest target in that direction. */
    private static void lean(MinecraftClient mc, float dYaw, float dPitch, float td) {
        Vec3d eye = mc.player.getCameraPosVec(td);
        float[] cur = angles(eye, aim(mc, td));
        double dl = Math.sqrt(dYaw * dYaw + dPitch * dPitch);
        java.util.List<Aim> options = new java.util.ArrayList<>();
        if (titan(target) && !wholeBody(target)) options.add(new Aim(target, part == Part.NAPE ? Part.EYE : Part.NAPE));
        for (Entity e : mc.world.getEntities()) {
            if (e == target || !candidate(mc, e) || e.squaredDistanceTo(mc.player) > RANGE * RANGE) continue;
            if (titan(e) && !wholeBody(e)) {
                options.add(new Aim(e, Part.NAPE));
                options.add(new Aim(e, Part.EYE));
            } else {
                options.add(new Aim(e, Part.BODY));
            }
        }
        Aim best = null;
        double bestScore = Double.MAX_VALUE;
        for (Aim a : options) {
            float[] ang = angles(eye, aimOf(mc, a, td));
            float oy = MathHelper.wrapDegrees(ang[0] - cur[0]), op = ang[1] - cur[1];
            double ol = Math.sqrt(oy * oy + op * op);
            if (ol < 0.5 || ol > 90) continue;
            double cos = (oy * dYaw + op * dPitch) / (ol * dl);
            if (cos < 0.55) continue;
            double score = ol * (1.6 - cos);
            if (score < bestScore) {
                bestScore = score;
                best = a;
            }
        }
        if (best != null) {
            target = best.entity();
            part = best.part();
        }
    }

    private static boolean wholeBody(Entity e) {
        return !titan(e) || shifter(e) || TitanState.shifted();
    }

    /** A weak-point entity of the titan (Danny's nape / eye hitboxes), if there is one near it. */
    private static Entity partEntity(MinecraftClient mc, Entity body, String kind) {
        Entity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : mc.world.getOtherEntities(body, body.getBoundingBox().expand(2.5))) {
            if (!path(e).contains(kind)) continue;
            double d = e.squaredDistanceTo(body);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    /** Where the lock points, this frame. */
    static Vec3d aim(MinecraftClient mc, float td) {
        Entity e = target;
        Vec3d pos = e.getLerpedPos(td);
        if (part == Part.BODY || wholeBody(e)) {
            double h = e instanceof PlayerEntity ? e.getStandingEyeHeight() * 0.85 : e.getHeight() * 0.6;
            return pos.add(0, h, 0);
        }
        Entity pe = partEntity(mc, e, part == Part.NAPE ? "nape" : "eye");
        if (pe != null) return pe.getLerpedPos(td).add(0, pe.getHeight() / 2, 0);
        // No hitbox entity: estimate from the body (nape at the back of the neck, eyes at the front of the face).
        float yaw = MathHelper.lerpAngleDegrees(td, e.prevYaw, e.getYaw());
        Vec3d fwd = Vec3d.fromPolar(0, yaw);
        return part == Part.NAPE ? pos.add(0, e.getHeight() * 0.86, 0).subtract(fwd.multiply(e.getWidth() * 0.35))
            : pos.add(0, e.getHeight() * 0.92, 0).add(fwd.multiply(e.getWidth() * 0.4));
    }

    /** Every frame: follow the target with the camera. */
    public static void frame(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        long now = Util.getMeasuringTimeMs();
        float dt = Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        if (target == null || mc.player == null) return;
        if (!target.isAlive() || target.isRemoved() || target.getWorld() != mc.player.getWorld()
            || target.squaredDistanceTo(mc.player) > (RANGE + 16) * (RANGE + 16)) {
            target = null;
            return;
        }
        if (mc.currentScreen != null) return;
        float td0 = ctx.tickCounter().getTickDelta(true);
        // How far the mouse moved the camera since the lock last set it: that is a drag.
        float my = MathHelper.wrapDegrees(mc.player.getYaw() - lastYaw), mp = mc.player.getPitch() - lastPitch;
        dragYaw = dragYaw * 0.9f + my;
        dragPitch = dragPitch * 0.9f + mp;
        if (Math.sqrt(dragYaw * dragYaw + dragPitch * dragPitch) > 9) {
            lean(mc, dragYaw, dragPitch, td0);
            dragYaw = dragPitch = 0;
        }
        Vec3d to = aim(mc, td0).subtract(mc.player.getCameraPosVec(td0));
        float yaw = (float) (MathHelper.atan2(to.z, to.x) * 180 / Math.PI) - 90;
        float pitch = (float) -(MathHelper.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)) * 180 / Math.PI);
        float k = Math.min(1, dt * 12);
        float ny = mc.player.getYaw() + MathHelper.wrapDegrees(yaw - mc.player.getYaw()) * k;
        float np = MathHelper.clamp(mc.player.getPitch() + (pitch - mc.player.getPitch()) * k, -90, 90);
        mc.player.setYaw(ny);
        mc.player.setPitch(np);
        mc.player.prevYaw = ny;
        mc.player.prevPitch = np;
        mc.player.setHeadYaw(ny);
        lastYaw = ny;
        lastPitch = np;
    }

    /** The reticle over the locked point: turning brackets, red on the nape, amber on the eyes. */
    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (target == null || mc.player == null) return;
        MatrixStack ms = ctx.matrixStack();
        if (ms == null) return;
        float td = ctx.tickCounter().getTickDelta(true);
        Vec3d at = aim(mc, td), cam = ctx.camera().getPos();
        Vec3d rel = at.subtract(cam);
        double dist = rel.length();
        double shown = Math.min(dist, 24);
        Vec3d p = rel.multiply(shown / Math.max(0.001, dist));
        float s = (float) (0.0045 * shown);
        boolean body = part == Part.BODY || wholeBody(target);
        int rgb = body ? 0xF4F0E6 : part == Part.NAPE ? 0xFF4A3A : 0xFFB03A;
        long now = Util.getMeasuringTimeMs();
        VertexConsumerProvider.Immediate vc = mc.getBufferBuilders().getEntityVertexConsumers();
        ms.push();
        ms.translate(p.x, p.y, p.z);
        ms.multiply(mc.getEntityRenderDispatcher().getRotation());
        ms.scale(s, -s, s);
        Matrix4f m = ms.peek().getPositionMatrix();
        VertexConsumer bg = vc.getBuffer(RenderLayer.getTextBackgroundSeeThrough());
        float spin = (now % 3000) / 3000f * 360f, pulse = 1 + 0.08f * (float) Math.sin(now / 120.0);
        float r = 13 * pulse;
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(spin + i * 90);
            float cx = (float) (Math.cos(a) * r), cy = (float) (Math.sin(a) * r);
            float tx = (float) -Math.sin(a) * 4, ty = (float) Math.cos(a) * 4;
            quad(bg, m, cx - tx - 1, cy - ty - 1, cx + tx + 1, cy + ty + 1, 0xE0000000 | rgb);
        }
        quad(bg, m, -1.5f, -1.5f, 1.5f, 1.5f, 0xF0000000 | rgb);
        String label = body ? (target instanceof PlayerEntity pl ? pl.getName().getString() : "") : part == Part.NAPE ? "NAPE" : "EYES";
        if (!label.isEmpty()) {
            TextRenderer tr = mc.textRenderer;
            Text t = Ui.heading(label);
            tr.draw(t, -tr.getWidth(t) / 2f, r + 5, 0xE0000000 | rgb, false, m, vc, TextRenderer.TextLayerType.SEE_THROUGH, 0,
                LightmapTextureManager.MAX_LIGHT_COORDINATE);
        }
        ms.pop();
        vc.draw();
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float x1, float y1, float x2, float y2, int argb) {
        int l = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        vc.vertex(m, x1, y1, 0).color(argb).light(l);
        vc.vertex(m, x1, y2, 0).color(argb).light(l);
        vc.vertex(m, x2, y2, 0).color(argb).light(l);
        vc.vertex(m, x2, y1, 0).color(argb).light(l);
        vc.vertex(m, x2, y1, 0).color(argb).light(l);
        vc.vertex(m, x2, y2, 0).color(argb).light(l);
        vc.vertex(m, x1, y2, 0).color(argb).light(l);
        vc.vertex(m, x1, y1, 0).color(argb).light(l);
    }
}
