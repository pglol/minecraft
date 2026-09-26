package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Being downed, seen from the ground: a pulsing blood-red vignette, a heartbeat that quickens as
 * you bleed out, a draining timer, and drops of blood. Everyone near sees a marker over you; a
 * comrade crouched beside you sees the revive fill; and being brought back floods the screen with
 * golden light.
 */
public final class DownedFx {
    private DownedFx() {}

    private static final Map<Integer, Net.DownedEntry> downed = new HashMap<>();
    private static long revivedAt = -99999, lastBeat;
    private static float myLastRevive;

    public static void onView(Net.DownedView v) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int me = mc.player == null ? -1 : mc.player.getId();
        boolean wasDown = downed.containsKey(me);
        downed.clear();
        for (Net.DownedEntry e : v.list()) downed.put(e.entity(), e);
        if (wasDown && !downed.containsKey(me) && myLastRevive > 0.5f && mc.player != null && mc.player.isAlive()) {
            revivedAt = Util.getMeasuringTimeMs();
        }
        Net.DownedEntry mine = downed.get(me);
        myLastRevive = mine == null ? myLastRevive : mine.revive();
        if (!wasDown && mine != null) myLastRevive = 0;
    }

    public static boolean meDown() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && downed.containsKey(mc.player.getId());
    }

    /** Keeps the downed crawling, and bleeds a little on the client too. */
    public static void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) {
            downed.clear();
            return;
        }
        for (Net.DownedEntry d : downed.values()) {
            Entity e = mc.world.getEntityById(d.entity());
            if (!(e instanceof PlayerEntity pl)) continue;
            pl.setPose(EntityPose.SWIMMING);
            if (pl == mc.player) pl.setSprinting(false);
            if (mc.world.random.nextFloat() < (d.pressing() ? 0.08f : 0.3f)) {
                mc.world.addParticle(new DustParticleEffect(new Vector3f(0.5f, 0f, 0f), 0.9f),
                    pl.getX() + (mc.world.random.nextDouble() - 0.5) * 0.6, pl.getY() + 0.25, pl.getZ() + (mc.world.random.nextDouble() - 0.5) * 0.6, 0, -0.05, 0);
            }
        }
        // The heartbeat: slow while you've time, racing at the end.
        Net.DownedEntry mine = downed.get(mc.player.getId());
        if (mine != null) {
            long now = Util.getMeasuringTimeMs();
            float frac = mine.left() / Math.max(1, mine.max());
            long gap = (long) (420 + 900 * MathHelper.clamp(frac, 0, 1));
            if (now - lastBeat > gap) {
                lastBeat = now;
                mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value(), 0.9f, 0.55f);
            }
        }
    }

    /** "✚ DOWNED 18s" over each downed player, with a revive bar once someone kneels by them. */
    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null || downed.isEmpty()) return;
        MatrixStack ms = ctx.matrixStack();
        if (ms == null) return;
        VertexConsumerProvider.Immediate vc = mc.getBufferBuilders().getEntityVertexConsumers();
        Vec3d cam = ctx.camera().getPos();
        TextRenderer tr = mc.textRenderer;
        float delta = ctx.tickCounter().getTickDelta(true);
        long now = Util.getMeasuringTimeMs();
        for (Net.DownedEntry d : downed.values()) {
            if (d.entity() == mc.player.getId()) continue;
            Entity e = mc.world.getEntityById(d.entity());
            if (e == null) continue;
            Vec3d pos = e.getLerpedPos(delta).add(0, 1.3, 0);
            double dist = pos.distanceTo(cam);
            if (dist > 80) continue;
            float s = (float) (0.022 + dist * 0.0014);
            ms.push();
            ms.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
            ms.multiply(mc.getEntityRenderDispatcher().getRotation());
            ms.scale(s, -s, s);
            boolean pulse = (now / 400) % 2 == 0;
            String top = "✚ DOWNED  " + (int) Math.ceil(d.left()) + "s";
            int col = d.left() < 8 ? (pulse ? 0xFFFF3A2A : 0xFFB01A10) : 0xFFFF5A4A;
            tr.draw(top, -tr.getWidth(top) / 2f, 0, col, false, ms.peek().getPositionMatrix(), vc,
                TextRenderer.TextLayerType.SEE_THROUGH, 0x90000000, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            String sub;
            int subCol;
            if (d.revive() > 0) {
                int n = 16, on = Math.round(d.revive() * n);
                sub = "█".repeat(on) + "░".repeat(n - on);
                subCol = 0xFFFFD76A;
            } else if (dist < 12) {
                sub = "Crouch beside to revive";
                subCol = 0xFFEDE3C8;
            } else {
                sub = (int) dist + "m";
                subCol = 0xFFB0A890;
            }
            tr.draw(sub, -tr.getWidth(sub) / 2f, 11, subCol, false, ms.peek().getPositionMatrix(), vc,
                TextRenderer.TextLayerType.SEE_THROUGH, 0x60000000, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            ms.pop();
        }
        vc.draw();
    }

    public static void renderHud(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        long now = Util.getMeasuringTimeMs();
        Net.DownedEntry mine = downed.get(mc.player.getId());
        if (mine != null) bleeding(c, mc, mine, w, h, now);
        else rescuing(c, mc, w, h);
        // Revived: a golden bloom from the centre that fades out.
        long r = now - revivedAt;
        if (r < 2200) {
            float t = r / 2200f;
            int a = (int) (200 * (1 - t) * (1 - t));
            c.fill(0, 0, w, h, (a / 2) << 24 | 0xFFE9A0);
            vignette(c, w, h, 0xFFD76A, (int) (220 * (1 - t)));
            var m = c.getMatrices();
            m.push();
            m.translate(w / 2f, h / 2f - 30, 0);
            float sc = 2.6f + (t < 0.12f ? (0.12f - t) * 10 : 0);
            m.scale(sc, sc, 1);
            Text txt = Ui.title("REVIVED");
            int ta = Math.max(8, (int) (255 * (t < 0.7f ? 1 : (1 - t) / 0.3f)));
            c.drawText(mc.textRenderer, txt, -mc.textRenderer.getWidth(txt) / 2, -4, ta << 24 | 0xFFE7A0, true);
            m.pop();
        }
    }

    /** Your own last seconds: the red closing in, the timer, and what you can do. */
    private static void bleeding(DrawContext c, MinecraftClient mc, Net.DownedEntry d, int w, int h, long now) {
        float frac = MathHelper.clamp(d.left() / Math.max(1, d.max()), 0, 1);
        float beat = (float) Math.pow(Math.max(0, Math.sin((now - lastBeat) / 180.0 * Math.PI)), 2);
        int strength = (int) (140 + 90 * (1 - frac) + 25 * beat);
        c.fill(0, 0, w, h, ((int) (40 + 60 * (1 - frac))) << 24 | 0x2A0000);
        vignette(c, w, h, 0x8A0000, Math.min(255, strength));
        // Title and timer.
        var m = c.getMatrices();
        m.push();
        m.translate(w / 2f, h / 2f - 46, 0);
        float sc = 2.4f + 0.12f * beat;
        m.scale(sc, sc, 1);
        Text title = Ui.title("DOWNED");
        c.drawText(mc.textRenderer, title, -mc.textRenderer.getWidth(title) / 2, -4, 0xFFFF4A3A, true);
        m.pop();
        int bw = 160, bx = w / 2 - bw / 2, by = h / 2 - 22;
        c.fill(bx - 1, by - 1, bx + bw + 1, by + 6, 0xC0000000);
        c.fill(bx, by, bx + Math.round(bw * frac), by + 5, d.pressing() ? 0xFFD04030 : 0xFFA01010);
        String secs = (int) Math.ceil(d.left()) + "s";
        Ui.text(c, Text.literal(secs), w / 2f, by + 9, 1f, 0xFFEDE3C8, true);
        String hint = d.pressing() ? "Pressing your wounds — bleeding slowed" : "Hold SNEAK to press your wounds";
        Ui.text(c, Text.literal(hint), w / 2f, by + 22, 1f, d.pressing() ? 0xFFFFB0A0 : 0xFFD8CFC0, true);
        if (d.revive() > 0) {
            String who = d.reviver().isEmpty() ? "A comrade" : d.reviver();
            String t = who + " is bringing you back…";
            Ui.text(c, Text.literal(t), w / 2f, h / 2 + 22, 1f, 0xFFFFD76A, true);
            int rx = w / 2 - 60, ry = h / 2 + 34;
            c.fill(rx - 1, ry - 1, rx + 121, ry + 5, 0xC0000000);
            c.fill(rx, ry, rx + Math.round(120 * d.revive()), ry + 4, 0xFFFFD76A);
        } else {
            String t = "Call for help — a comrade can crouch beside you to revive";
            Ui.text(c, Text.literal(t), w / 2f, h / 2 + 22, 0.85f, 0xFFB0A890, true);
        }
    }

    /** Kneeling by someone: their revive fills under your crosshair. */
    private static void rescuing(DrawContext c, MinecraftClient mc, int w, int h) {
        if (mc.world == null) return;
        for (Net.DownedEntry d : downed.values()) {
            Entity e = mc.world.getEntityById(d.entity());
            if (e == null || e.squaredDistanceTo(mc.player) > 3.2 * 3.2) continue;
            String name = e.getName().getString();
            if (d.revive() > 0 && mc.player.isSneaking()) {
                String t = "Reviving " + name;
                Ui.text(c, Ui.heading(t), w / 2f, h / 2 + 18, 1f, 0xFFFFD76A, true);
                int rx = w / 2 - 50, ry = h / 2 + 30;
                c.fill(rx - 1, ry - 1, rx + 101, ry + 5, 0xC0000000);
                c.fill(rx, ry, rx + Math.round(100 * d.revive()), ry + 4, 0xFFFFD76A);
            } else {
                String t = "Hold SNEAK to revive " + name;
                Ui.text(c, Text.literal(t), w / 2f, h / 2 + 18, 1f, 0xFFEDE3C8, true);
            }
            return;
        }
    }

    /** Colour closing in from every edge. */
    private static void vignette(DrawContext c, int w, int h, int rgb, int alpha) {
        int bands = 12, depth = Math.max(w, h) / 5;
        for (int i = 0; i < bands; i++) {
            int a = alpha * (bands - i) / bands / 3;
            int col = a << 24 | rgb;
            int in = depth * i / bands, next = depth * (i + 1) / bands;
            c.fill(0, in, w, next, col);
            c.fill(0, h - next, w, h - in, col);
            c.fill(in, next, next, h - next, col);
            c.fill(w - next, next, w - in, h - next, col);
        }
    }
}
