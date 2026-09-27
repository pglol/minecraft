package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The duel ring, drawn only for the two fighters: a wall of gold light that fades up into the air,
 * with bands rising through it and brighter pillars at intervals. It glows harder the closer you
 * get, and red at the spot you're nearest, so you can feel where the edge is mid-fight.
 */
public final class DuelRing {
    private DuelRing() {}

    private static Vec3d center;
    private static float radius;
    private static long since;

    public static void on(Net.DuelRing msg) {
        if (msg.r() <= 0) {
            center = null;
            return;
        }
        center = new Vec3d(msg.x(), msg.y(), msg.z());
        radius = msg.r();
        since = System.currentTimeMillis();
    }

    public static void clear() {
        center = null;
    }

    public static void tick(MinecraftClient mc) {
        if (center == null || mc.world == null || mc.player == null || mc.world.getTime() % 3 != 0) return;
        // Motes drifting up the wall near you.
        double a = Math.atan2(mc.player.getZ() - center.z, mc.player.getX() - center.x) + (Math.random() - 0.5) * 1.2;
        mc.world.addParticle(new DustParticleEffect(new Vector3f(1f, 0.8f, 0.35f), 0.8f), center.x + Math.cos(a) * radius,
            center.y + Math.random() * 3, center.z + Math.sin(a) * radius, 0, 0.05, 0);
    }

    public static void render(WorldRenderContext ctx) {
        if (center == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        MatrixStack ms = ctx.matrixStack();
        VertexConsumerProvider vcp = ctx.consumers();
        if (ms == null || vcp == null) return;
        Vec3d cam = ctx.camera().getPos();
        float t = (System.currentTimeMillis() - since) / 1000f;
        // Rises out of the ground over the first second.
        float grow = Math.min(1, t / 1.2f);
        double pd = Math.sqrt(Math.pow(mc.player.getX() - center.x, 2) + Math.pow(mc.player.getZ() - center.z, 2));
        float near = (float) Math.max(0, Math.min(1, 1 - (radius - pd) / 8));
        double pa = Math.atan2(mc.player.getZ() - center.z, mc.player.getX() - center.x);
        ms.push();
        ms.translate(center.x - cam.x, center.y - cam.y, center.z - cam.z);
        Matrix4f m = ms.peek().getPositionMatrix();
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getLightning());
        int seg = 96;
        float h = 9 * grow, base = -2;
        for (int i = 0; i < seg; i++) {
            double a0 = i * Math.PI * 2 / seg, a1 = (i + 1) * Math.PI * 2 / seg;
            float x0 = (float) Math.cos(a0) * radius, z0 = (float) Math.sin(a0) * radius;
            float x1 = (float) Math.cos(a1) * radius, z1 = (float) Math.sin(a1) * radius;
            // How close this panel is to where you stand (it burns red there when you're at the edge).
            double da = Math.abs(Math.atan2(Math.sin(a0 - pa), Math.cos(a0 - pa)));
            float hot = (float) Math.max(0, 1 - da / 0.6) * near;
            boolean pillar = i % 8 == 0;
            float glow = 0.18f + 0.35f * near + (pillar ? 0.25f : 0) + 0.4f * hot;
            int r = 255, g = (int) (200 - 150 * hot), b = (int) (90 - 60 * hot);
            int bottom = argb(Math.min(1, glow), r, g, b), top = argb(0, r, g, b);
            quad(vc, m, x0, base, z0, x1, base, z1, x1, base + h, z1, x0, base + h, z0, bottom, bottom, top, top);
            // Bands of light climbing the wall.
            for (int k = 0; k < 2; k++) {
                float y = base + ((t * 1.3f + k * 0.5f + i * 0.004f) % 1f) * h;
                float fade = 1 - (y - base) / Math.max(0.1f, h);
                int band = argb(Math.min(1, (0.35f + 0.5f * hot) * fade), 255, 230, 160);
                quad(vc, m, x0, y, z0, x1, y, z1, x1, y + 0.12f, z1, x0, y + 0.12f, z0, band, band, band, band);
            }
            if (pillar) {
                int pc = argb(Math.min(1, 0.5f + 0.4f * near), 255, 220, 120), pt = argb(0, 255, 220, 120);
                float w = 0.12f;
                float px = x0 - (float) Math.sin(a0) * w, pz = z0 + (float) Math.cos(a0) * w;
                float qx = x0 + (float) Math.sin(a0) * w, qz = z0 - (float) Math.cos(a0) * w;
                quad(vc, m, px, base, pz, qx, base, qz, qx, base + h * 1.3f, qz, px, base + h * 1.3f, pz, pc, pc, pt, pt);
            }
        }
        // A ring of light on the ground.
        for (int i = 0; i < seg; i++) {
            double a0 = i * Math.PI * 2 / seg, a1 = (i + 1) * Math.PI * 2 / seg;
            float ri = radius - 0.25f, ro = radius + 0.05f;
            int c = argb(0.7f, 255, 210, 110);
            float y = 0.05f;
            quad(vc, m, (float) Math.cos(a0) * ri, y, (float) Math.sin(a0) * ri, (float) Math.cos(a0) * ro, y, (float) Math.sin(a0) * ro,
                (float) Math.cos(a1) * ro, y, (float) Math.sin(a1) * ro, (float) Math.cos(a1) * ri, y, (float) Math.sin(a1) * ri, c, c, c, c);
        }
        ms.pop();
    }

    private static int argb(float a, int r, int g, int b) {
        return ((int) (Math.max(0, Math.min(1, a)) * 255) << 24) | (Math.max(0, Math.min(255, r)) << 16) | (Math.max(0, Math.min(255, g)) << 8) | Math.max(0, Math.min(255, b));
    }

    /** Both faces, so it shows from inside and out. */
    private static void quad(VertexConsumer vc, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, int ca, int cb, int cc, int cd) {
        vc.vertex(m, ax, ay, az).color(ca);
        vc.vertex(m, bx, by, bz).color(cb);
        vc.vertex(m, cx, cy, cz).color(cc);
        vc.vertex(m, dx, dy, dz).color(cd);
        vc.vertex(m, dx, dy, dz).color(cd);
        vc.vertex(m, cx, cy, cz).color(cc);
        vc.vertex(m, bx, by, bz).color(cb);
        vc.vertex(m, ax, ay, az).color(ca);
    }
}
