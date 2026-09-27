package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * Reward reveals over the game (it keeps running underneath): a slash of light, the icon drops in
 * and bounces, rays turn behind it, the title types itself out, and it slides away. The rarity sets
 * how big it plays: longer, more rays, shockwaves from Epic, confetti and a fanfare from Legendary,
 * red flashes and a shake for Mythic. Several in a row queue up.
 */
public final class RewardFx {
    private RewardFx() {}

    private static final Deque<Net.RewardReveal> queue = new ArrayDeque<>();
    private static Net.RewardReveal now;
    private static long start;
    private static boolean sounded;

    public static void on(Net.RewardReveal r) {
        if (queue.size() < 8) queue.add(r);
    }

    private static int length(int rar) {
        return 2600 + rar * 450;
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        long t = Util.getMeasuringTimeMs();
        if (now != null && t - start > length(now.rarity())) now = null;
        if (now == null) {
            // Wait out cutscenes and crate openings, then play the next one.
            if (queue.isEmpty() || com.pglol.aotrpg.client.story.CutscenePlayer.active() || CrateOpening.active()) return;
            now = queue.poll();
            start = t;
            sounded = false;
        }
        int rar = Math.max(0, Math.min(5, now.rarity()));
        long e = t - start;
        int len = length(rar);
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        int col = CrateOpening.COLORS[rar];
        if (!sounded) {
            sounded = true;
            sound(SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 1.4f);
            sound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.8f, 1f + rar * 0.1f);
            if (rar >= 2) sound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);
            if (rar >= 3) sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, rar >= 4 ? 1f : 1.3f);
            if (rar >= 4) sound(SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.8f, 1f);
            if (rar == 5) sound(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f, 1.1f);
        }
        float out = e > len - 500 ? (len - e) / 500f : 1;
        int cx = w / 2, cy = (int) (h * 0.27f) - (int) ((1 - out) * 40);
        if (rar == 5 && e < 700) {
            cx += (int) (Math.sin(e * 0.3) * 4);
            cy += (int) (Math.cos(e * 0.37) * 3);
            int fa = (int) (120 * (1 - e / 700f));
            c.fill(0, 0, w, h, (fa << 24) | 0xE02A2A);
        }
        // Legendary up: the screen's edges catch the colour.
        if (rar >= 4) {
            int ea = (int) (90 * out * (0.6 + 0.4 * Math.sin(e * 0.008)));
            c.fillGradient(0, 0, w, 40, (ea << 24) | (col & 0xFFFFFF), 0);
            c.fillGradient(0, h - 40, w, h, 0, (ea << 24) | (col & 0xFFFFFF));
        }
        // 1. The slash of light across the middle.
        float s = Math.min(1, e / 300f);
        int half = (int) (w * 0.35f * s);
        int la = (int) (220 * out * (e < 600 ? 1 : 0.5f));
        c.fill(cx - half, cy + 26, cx + half, cy + 28, (la << 24) | (col & 0xFFFFFF));
        c.fillGradient(cx - half, cy + 6, cx + half, cy + 26, 0, ((la / 3) << 24) | (col & 0xFFFFFF));
        c.fillGradient(cx - half, cy + 28, cx + half, cy + 48, ((la / 3) << 24) | (col & 0xFFFFFF), 0);
        // 2. Rays behind the icon.
        if (e > 250) {
            int n = 8 + rar * 4;
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n + e * 0.0008 * (rar == 5 ? -1.5 : 1);
                int rl = (int) ((24 + rar * 9) * Math.min(1, (e - 250) / 300f));
                for (int d = 12; d < rl; d += 2) {
                    int al = (int) (140 * out * (1 - d / (float) rl));
                    int x = cx + (int) (Math.cos(a) * d), y = cy + (int) (Math.sin(a) * d);
                    c.fill(x, y, x + 1, y + 1, (al << 24) | (col & 0xFFFFFF));
                }
            }
        }
        // Shockwaves from Epic up.
        if (rar >= 3) {
            for (int k = 0; k < rar - 2; k++) {
                float rp = (e - 350 - k * 160) / 600f;
                if (rp < 0 || rp > 1) continue;
                int rr = (int) (14 + rp * (50 + rar * 12)), a = (int) (200 * (1 - rp) * out);
                for (int j = 0; j < 48; j++) {
                    double ang = j * Math.PI * 2 / 48;
                    int x = cx + (int) (Math.cos(ang) * rr), y = cy + (int) (Math.sin(ang) * rr * 0.6);
                    c.fill(x, y, x + 2, y + 1, (a << 24) | (col & 0xFFFFFF));
                }
            }
        }
        // 3. The icon drops in and bounces.
        float k = Math.min(1, e / 450f);
        double bounce = k < 1 ? 1 - Math.pow(1 - k, 3) * Math.cos(k * 9) : 1;
        float sc = 2f + rar * 0.15f;
        c.getMatrices().push();
        c.getMatrices().translate(cx - 8 * sc, cy - 8 * sc - (float) ((1 - bounce) * 40), 0);
        c.getMatrices().scale(sc, sc, 1);
        c.drawItem(icon(now.icon()), 0, 0);
        c.getMatrices().pop();
        // Sparkles orbiting.
        Random r = new Random(start);
        for (int i = 0; i < 6 + rar * 3; i++) {
            double a = r.nextDouble() * Math.PI * 2 + e * 0.002 * (1 + r.nextDouble());
            double d = 22 + r.nextDouble() * (14 + rar * 4);
            int x = cx + (int) (Math.cos(a) * d), y = cy + (int) (Math.sin(a) * d * 0.7);
            int al = (int) ((150 + 100 * Math.sin(e * 0.01 + i)) * out);
            c.fill(x, y, x + 1, y + 1, (Math.max(0, Math.min(255, al)) << 24) | (i % 2 == 0 ? 0xFFFFFF : col & 0xFFFFFF));
        }
        // Confetti from Legendary up (embers for Mythic).
        if (rar >= 4) {
            int[] pal = rar == 5 ? new int[] {0xE02A2A, 0xFF6A4A, 0x7A0A0A} : new int[] {0xF2C14E, 0xFFFFFF, 0xE03A3A, 0x5A9AE0, 0x5BD35B};
            for (int i = 0; i < 60; i++) {
                double x0 = r.nextDouble() * w, sp = 50 + r.nextDouble() * 70;
                double ts = e / 1000.0;
                int x = (int) (x0 + Math.sin(ts * 3 + i) * 8);
                int y = rar == 5 ? (int) (h * 0.6 - sp * ts - r.nextDouble() * 40) : (int) (-8 + sp * ts * 1.4 - r.nextDouble() * 60);
                if (y < -8 || y > h) continue;
                int a = (int) (255 * out);
                c.fill(x, y, x + 2, y + 3, (a << 24) | pal[i % pal.length]);
            }
        }
        // 4. The title types itself out; the line under it fades in.
        String title = now.title();
        int shown = (int) Math.min(title.length(), Math.max(0, (e - 300) / 35));
        int ta = (int) (255 * out);
        if (shown > 0 && ta > 8) {
            Ui.text(c, Ui.title(title.substring(0, shown)), cx, cy + 32, 1.5f + (rar >= 4 ? 0.3f : 0), (ta << 24) | (col & 0xFFFFFF), true);
        }
        float sa = Math.min(1, Math.max(0, (e - 300 - title.length() * 35) / 300f)) * out;
        if (sa > 0.03f && !now.sub().isEmpty()) {
            Ui.text(c, Text.literal(now.sub()), cx, cy + 50, 0.85f, ((int) (255 * sa) << 24) | 0xEDE3C8, true);
        }
        if (rar >= 2 && sa > 0.03f) {
            String name = CrateOpening.NAMES[rar].toUpperCase();
            Ui.text(c, Text.literal(name), cx, cy + 62, 0.6f, ((int) (200 * sa) << 24) | (col & 0xFFFFFF), true);
        }
    }

    private static void sound(SoundEvent e, float vol, float pitch) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.playSound(e, vol, pitch);
    }

    private static ItemStack icon(String id) {
        Identifier ident = Identifier.tryParse(id);
        var item = ident == null ? Items.NETHER_STAR : Registries.ITEM.get(ident);
        return new ItemStack(item == Items.AIR ? Items.NETHER_STAR : item);
    }

    public static void clear() {
        queue.clear();
        now = null;
    }
}
