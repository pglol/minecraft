package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Util;

/**
 * Hit feedback: a crisp sound, a crosshair hit marker (red for a kill) and, for blade hits, a
 * quick slash streak across the screen and a sweep at the target.
 */
public final class HitFx {
    private HitFx() {}

    private static long hitAt, slashAt;
    private static boolean kill, ranged;
    private static int slashDir = 1;

    public static void onHit(Net.HitMarker h) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ranged = (h.kind() & 1) != 0;
        kill = (h.kind() & 2) != 0;
        hitAt = Util.getMeasuringTimeMs();
        var sounds = mc.getSoundManager();
        if (ranged) {
            sounds.play(PositionedSoundInstance.master(SoundEvents.ENTITY_ARROW_HIT_PLAYER, kill ? 0.8f : 1.5f, 0.45f));
        } else {
            sounds.play(PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.25f, 0.35f));
            sounds.play(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 1.9f, 0.25f));
            slashAt = hitAt;
            slashDir = -slashDir;
        }
        if (kill) sounds.play(PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 0.7f, 0.5f));
        Entity e = mc.world == null ? null : mc.world.getEntityById(h.entity());
        if (e != null && mc.world != null) {
            double y = e.getY() + Math.min(e.getHeight() * 0.6, 3);
            if (!ranged) mc.world.addParticle(ParticleTypes.SWEEP_ATTACK, e.getX(), y, e.getZ(), 0, 0, 0);
            for (int i = 0; i < (kill ? 10 : 4); i++) {
                mc.world.addParticle(ParticleTypes.CRIT, e.getX(), y, e.getZ(),
                    (Math.random() - 0.5) * 0.6, Math.random() * 0.3, (Math.random() - 0.5) * 0.6);
            }
        }
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden) return;
        long now = Util.getMeasuringTimeMs();
        int cx = c.getScaledWindowWidth() / 2, cy = c.getScaledWindowHeight() / 2;
        // Crosshair hit marker: four short diagonal ticks.
        long t = now - hitAt;
        if (t < 320) {
            float f = 1 - t / 320f;
            int a = (int) (255 * f);
            int rgb = kill ? 0xFF3A2A : 0xFFFFFF;
            int gap = ranged ? 4 : 3, len = ranged ? 5 : 4;
            gap += (int) ((1 - f) * 2);
            for (int[] d : new int[][] {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}}) {
                for (int i = 0; i < len; i++) {
                    int x = cx + d[0] * (gap + i), y = cy + d[1] * (gap + i);
                    c.fill(x, y, x + 1, y + 1, (a << 24) | rgb);
                    if (kill || ranged) c.fill(x + d[0], y, x + d[0] + 1, y + 1, (a / 2 << 24) | rgb);
                }
            }
        }
        // Blade slash: a thin bright arc sweeping across the centre of the screen.
        long s = now - slashAt;
        if (s < 180) {
            float p = s / 180f;
            int w = c.getScaledWindowWidth();
            int segs = 36;
            for (int i = 0; i < segs; i++) {
                float u = i / (float) (segs - 1);
                if (u > p * 1.4f || u < p * 1.4f - 0.55f) continue; // the streak travels along the arc
                double ang = Math.toRadians(-35 + 70 * u) * slashDir;
                int r = (int) (w * 0.09);
                int x = cx + (int) (Math.sin(ang) * r * 1.6) * slashDir;
                int y = cy - (int) (Math.cos(ang) * r * 0.5) + (int) (r * 0.35);
                float fade = (1 - p) * (1 - Math.abs(u - p) * 1.2f);
                int a = (int) (170 * Math.max(0, fade));
                int thick = 2;
                c.fill(x, y, x + 3, y + thick, (a << 24) | 0xF4F0E6);
            }
        }
    }
}
