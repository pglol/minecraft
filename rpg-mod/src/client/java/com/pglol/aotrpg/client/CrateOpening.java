package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Opening a crate, gacha style. Three acts, and every one of them tells you how good it's going to be:
 *
 *  1. The tell. The crate shakes and light leaks from its seams, stepping up through the rarity
 *     colours (white, green, blue, purple, gold) with a rising chime at each step. It stops at what
 *     you got. Epic and up hold back on the last step before jumping; Legendary brings a beam of
 *     gold down onto the crate first; Mythic goes gold, then the light dies, the screen goes black,
 *     a heartbeat, and red cracks spread out from the crate.
 *  2. The reel. A strip of what the crate holds spins past a marker and slows onto the prize (with
 *     the next rarity up sitting just past it). Legendary creeps the last few tiles; Mythic's reel
 *     glitches red and black.
 *  3. The burst, in the prize's colour: sparks and rays for all, shockwaves from Epic, confetti and a
 *     fanfare from Legendary, and for Mythic a red flash, lightning, and the word itself shaking.
 *
 * Click to skip ahead; click on the result to close it.
 */
public final class CrateOpening {
    private CrateOpening() {}

    static final int[] COLORS = {0xFFB8B8B8, 0xFF5BD35B, 0xFF5A9AE0, 0xFFB06AE0, 0xFFF2C14E, 0xFFE02A2A};
    static final String[] NAMES = {"Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic"};
    private static final int[] TELL = {900, 1000, 1500, 2100, 3000, 4600};
    private static final int[] REEL = {1500, 1700, 2200, 2800, 3700, 3200};
    private static final int TILE = 50, GAP = 4, TARGET = 34;

    private static Net.CrateOpened got;
    private static int rar;
    private static long start;
    private static boolean done;
    private static final List<Tile> tiles = new ArrayList<>();
    private static final Set<String> fired = new HashSet<>();
    private static int lastTick = -1;
    private static long seed;

    private record Tile(int rarity, ItemStack icon) { }

    public static boolean active() {
        return got != null;
    }

    /** Starts the opening of a crate; loot is that crate's table ("rarity|permille|icon|label"). */
    public static void start(Net.CrateOpened o, List<String> loot) {
        got = o;
        rar = Math.max(0, Math.min(5, o.rarity()));
        start = Util.getMeasuringTimeMs();
        done = false;
        fired.clear();
        lastTick = -1;
        seed = start;
        Random r = new Random(seed);
        // The reel: tiles drawn from the crate's own odds, the prize at the stop, and the next
        // rarity up parked right after it, the classic near miss.
        List<int[]> weights = new ArrayList<>();
        List<ItemStack> icons = new ArrayList<>();
        for (String l : loot) {
            String[] f = l.split("\\|", 4);
            if (f.length < 4) continue;
            // Rare things show up a little more often on the reel than they really drop, to tease.
            weights.add(new int[] {Integer.parseInt(f[0]), Math.max(8, Integer.parseInt(f[1]))});
            icons.add(icon(f[2]));
        }
        tiles.clear();
        int total = 0;
        for (int[] w : weights) total += w[1];
        for (int i = 0; i < TARGET + 8; i++) {
            if (i == TARGET) {
                tiles.add(new Tile(rar, icon(o.icon())));
                continue;
            }
            if (i == TARGET + 1 && rar < 5) {
                int up = Math.min(5, rar + 1 + (r.nextInt(4) == 0 ? 1 : 0));
                tiles.add(new Tile(up, pickIcon(weights, icons, up)));
                continue;
            }
            if (total <= 0) {
                tiles.add(new Tile(r.nextInt(3), new ItemStack(Items.CHEST)));
                continue;
            }
            int x = r.nextInt(total), k = 0;
            while (k < weights.size() - 1 && x >= weights.get(k)[1]) x -= weights.get(k++)[1];
            tiles.add(new Tile(weights.get(k)[0], icons.get(k)));
        }
        sound(SoundEvents.BLOCK_CHEST_LOCKED, 1f, 0.7f);
    }

    private static ItemStack pickIcon(List<int[]> weights, List<ItemStack> icons, int rarity) {
        for (int i = 0; i < weights.size(); i++) if (weights.get(i)[0] == rarity) return icons.get(i);
        return new ItemStack(rarity >= 5 ? Items.NETHER_STAR : rarity >= 4 ? Items.DIAMOND_SWORD : Items.IRON_SWORD);
    }

    private static ItemStack icon(String id) {
        Identifier ident = Identifier.tryParse(id);
        var item = ident == null ? Items.CHEST : Registries.ITEM.get(ident);
        return new ItemStack(item == Items.AIR ? Items.CHEST : item);
    }

    private static long now() {
        return Util.getMeasuringTimeMs() - start;
    }

    private static int tell() { return TELL[rar]; }
    private static int reel() { return REEL[rar]; }
    private static int burst() { return rar == 5 ? 2600 : rar == 4 ? 2000 : 1300; }

    /** A click: skip to the result, or close it once shown. Returns true if it was used. */
    public static boolean click() {
        if (got == null) return false;
        long t = now(), end = tell() + reel() + burst();
        if (t < tell() + reel()) {
            // Skip the wait, not the payoff: straight to the burst.
            start = Util.getMeasuringTimeMs() - (tell() + reel());
            fired.add("reelsound");
        } else if (t < end) {
            start = Util.getMeasuringTimeMs() - end;
        } else {
            got = null;
        }
        return true;
    }

    private static void sound(SoundEvent e, float vol, float pitch) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.playSound(e, vol, pitch);
    }

    private static void once(String key, Runnable r) {
        if (fired.add(key)) r.run();
    }

    // ------------------------------------------------------------------ drawing

    public static void render(DrawContext c, int w, int h) {
        if (got == null) return;
        long t = now();
        int cx = w / 2, cy = h / 2;
        c.getMatrices().push();
        c.getMatrices().translate(0, 0, 500);
        c.fill(0, 0, w, h, 0xD8000000);
        if (t < tell()) drawTell(c, w, h, cx, cy, t);
        else if (t < tell() + reel()) drawReel(c, w, h, cx, cy, t - tell());
        else drawBurst(c, w, h, cx, cy, t - tell() - reel());
        c.getMatrices().pop();
    }

    /** Which rarity colour the crate's light has reached at this point of the tell. */
    private static int level(long t) {
        int top = rar == 5 ? 4 : rar;
        int lv = 0;
        for (int k = 1; k <= top; k++) {
            double at = rar == 5 ? tell() * (0.05 + 0.3 * k / 4.0) : tell() * (0.12 + 0.55 * k / Math.max(1, top));
            // The last step holds back (Epic and up): a pause where it could stop... then it jumps.
            if (k == top && rar >= 3 && rar < 5) at = tell() * 0.86;
            if (t >= at) {
                lv = k;
                int kk = k;
                once("step" + k, () -> {
                    sound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.9f, 0.6f + 0.18f * kk);
                    if (kk >= 3) sound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 0.8f + 0.1f * kk);
                });
            }
        }
        return lv;
    }

    private static void drawTell(DrawContext c, int w, int h, int cx, int cy, long t) {
        float p = t / (float) tell();
        int lv = level(t);
        int col = COLORS[lv];
        float shake = (float) Math.min(1, p * 1.3) * (2 + rar * 1.5f);
        boolean mythicDark = rar == 5 && p > 0.4f;
        if (rar >= 4 && !mythicDark) {
            // A beam of light comes down onto the crate.
            float bp = Math.min(1, Math.max(0, (p - 0.08f) / 0.15f));
            once("beam", () -> sound(SoundEvents.BLOCK_BEACON_ACTIVATE, 1f, 1.2f));
            int bw = (int) (22 * bp + 6 * Math.sin(t * 0.02));
            int a = (int) (140 * bp);
            c.fillGradient(cx - bw, 0, cx + bw, cy, 0x00FFFFFF & 0xFFE08A, (a << 24) | 0xFFE08A);
            c.fillGradient(cx - bw / 3, 0, cx + bw / 3, cy, 0x00FFFFFF, (Math.min(255, a + 80) << 24) | 0xFFFFFF);
        }
        if (mythicDark) {
            // Mythic: the light goes out. Black, a heartbeat, and the crate starts to crack in red.
            float dp = Math.min(1, (p - 0.4f) / 0.15f);
            c.fill(0, 0, w, h, ((int) (255 * dp) << 24));
            once("hb1", () -> sound(SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.2f, 0.8f));
            if (p > 0.62f) once("hb2", () -> sound(SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.3f, 0.75f));
            if (p > 0.8f) once("hb3", () -> sound(SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.5f, 0.7f));
            if (p > 0.93f) once("crack", () -> sound(SoundEvents.BLOCK_GLASS_BREAK, 1f, 0.5f));
            float cp = Math.max(0, (p - 0.5f) / 0.5f);
            cracks(c, cx, cy, cp, w, h);
            col = COLORS[5];
            shake = 1.5f + 5 * cp;
        }
        int sx = (int) (Math.sin(t * 0.07) * shake), sy = (int) (Math.cos(t * 0.11) * shake * 0.6);
        // Rays behind the crate, more and longer the higher it has climbed.
        if (lv >= 2 || mythicDark) {
            int n = 6 + lv * 3;
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n + t * 0.0006 * (i % 2 == 0 ? 1 : -1);
                int len = 50 + lv * 14;
                for (int d = 36; d < len; d += 3) {
                    int al = (int) (120 * (1 - (d - 36) / (float) (len - 36)));
                    int x = cx + sx + (int) (Math.cos(a) * d), y = cy + sy + (int) (Math.sin(a) * d);
                    c.fill(x - 1, y - 1, x + 1, y + 1, (al << 24) | (col & 0xFFFFFF));
                }
            }
        }
        // A ring pulses out at every step up.
        for (int k = 1; k <= lv; k++) {
            if (!fired.contains("step" + k)) continue;
            double at = rar == 5 ? tell() * (0.05 + 0.3 * k / 4.0) : tell() * (0.12 + 0.55 * k / Math.max(1, rar));
            if (k == rar && rar >= 3 && rar < 5) at = tell() * 0.86;
            float rp = (float) ((t - at) / 450.0);
            if (rp >= 0 && rp < 1) ring(c, cx, cy, (int) (40 + rp * 90), COLORS[k], 1 - rp);
        }
        crate(c, cx + sx, cy + sy, 36, col, Math.min(1, 0.25f + lv * 0.18f), false);
        String hint = rar == 5 && mythicDark ? "..." : "Opening the " + got.crate();
        Ui.text(c, Text.literal(hint), cx, cy + 58, 0.9f, mythicDark ? 0xFFE02A2A : Ui.CREAM, true);
        Ui.text(c, Text.literal("click to skip"), cx, h - 16, 0.6f, 0x80FFFFFF, true);
    }

    /** Red cracks spreading out from the crate (Mythic). */
    private static void cracks(DrawContext c, int cx, int cy, float p, int w, int h) {
        Random r = new Random(seed ^ 0x5EED);
        for (int k = 0; k < 9; k++) {
            double a = k * Math.PI * 2 / 9 + r.nextDouble() * 0.5;
            double x = cx, y = cy;
            int steps = (int) (p * 22);
            for (int s = 0; s < steps; s++) {
                a += (r.nextDouble() - 0.5) * 0.9;
                double nx = x + Math.cos(a) * 9, ny = y + Math.sin(a) * 9;
                line(c, (int) x, (int) y, (int) nx, (int) ny, 0xFFE02A2A);
                line(c, (int) x + 1, (int) y, (int) nx + 1, (int) ny, 0x80FF6A4A);
                x = nx;
                y = ny;
            }
        }
    }

    private static void line(DrawContext c, int x0, int y0, int x1, int y1, int col) {
        int n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int i = 0; i <= n; i++) {
            int x = x0 + (x1 - x0) * i / Math.max(1, n), y = y0 + (y1 - y0) * i / Math.max(1, n);
            c.fill(x, y, x + 1, y + 1, col);
        }
    }

    private static void ring(DrawContext c, int cx, int cy, int r, int col, float alpha) {
        int a = (int) (Math.max(0, Math.min(1, alpha)) * 220);
        for (int k = 0; k < 64; k++) {
            double ang = k * Math.PI * 2 / 64;
            int x = cx + (int) (Math.cos(ang) * r), y = cy + (int) (Math.sin(ang) * r);
            c.fill(x - 1, y - 1, x + 1, y + 1, (a << 24) | (col & 0xFFFFFF));
        }
    }

    /** The crate: planks, iron bands, a lock plate in the light's colour, light leaking from the seams. */
    private static void crate(DrawContext c, int cx, int cy, int s, int col, float glow, boolean open) {
        c.fill(cx - s, cy - s, cx + s, cy + s, 0xFF6A4A2A);
        for (int k = -s; k < s; k += 9) c.fill(cx - s, cy + k, cx + s, cy + k + 1, 0xFF4A321A);
        c.fill(cx - s, cy - s / 2 - 2, cx + s, cy - s / 2 + 2, 0xFF2E2E2E);
        c.fill(cx - s, cy + s / 2 - 2, cx + s, cy + s / 2 + 2, 0xFF2E2E2E);
        c.drawBorder(cx - s, cy - s, s * 2, s * 2, 0xFF2A1A0A);
        int a = (int) (glow * 255);
        // Light through the lid seam.
        c.fill(cx - s, cy - s / 2 - 5, cx + s, cy - s / 2 - 3, (a << 24) | (col & 0xFFFFFF));
        c.fillGradient(cx - s - 6, cy - s / 2 - 16, cx + s + 6, cy - s / 2 - 4, 0x00000000, ((a / 2) << 24) | (col & 0xFFFFFF));
        c.fill(cx - 9, cy - 10, cx + 9, cy + 10, 0xFF000000 | (col & 0xFFFFFF));
        c.fill(cx - 3, cy - 3, cx + 3, cy + 5, 0xFF1A1A1A);
    }

    private static void drawReel(DrawContext c, int w, int h, int cx, int cy, long t) {
        float p = t / (float) reel();
        once("reelsound", () -> sound(SoundEvents.BLOCK_CHEST_OPEN, 1f, rar >= 4 ? 0.6f : 0.9f));
        double eased;
        if (rar == 4) {
            // Legendary: slows almost to a stop just short... then creeps onto it.
            eased = p < 0.8f ? (1 - Math.pow(1 - p / 0.8, 3)) * 0.985 : 0.985 + 0.015 * smooth((p - 0.8) / 0.2);
        } else {
            eased = 1 - Math.pow(1 - p, rar >= 3 ? 4 : 3);
        }
        int pitch = TILE + GAP;
        double dist = TARGET * pitch;
        double off = eased * dist;
        int stripY = cy - TILE / 2;
        boolean glitch = rar == 5;
        int jx = glitch ? (int) (Math.sin(t * 0.09) * 3) : 0;
        c.fill(0, stripY - 10, w, stripY + TILE + 10, 0xC0100E0C);
        for (int i = 0; i < tiles.size(); i++) {
            int x = (int) (cx - TILE / 2 + i * pitch - off) + jx;
            if (x < -TILE || x > w) continue;
            Tile tl = tiles.get(i);
            int r = tl.rarity();
            if (glitch && ((t / 70 + i) % 5 == 0)) r = (t / 70 + i) % 2 == 0 ? 5 : 0;
            tile(c, x, stripY, r, tl.icon(), glitch && i != TARGET && (t / 90 + i) % 7 == 0);
        }
        // The marker.
        int mc = rar >= 4 && p > 0.7f ? COLORS[rar] : 0xFFFFFFFF;
        c.fill(cx - 1, stripY - 14, cx + 1, stripY + TILE + 14, mc);
        c.fill(cx - 5, stripY - 14, cx + 5, stripY - 11, mc);
        c.fill(cx - 5, stripY + TILE + 11, cx + 5, stripY + TILE + 14, mc);
        // Edges fade into the dark.
        c.fillGradient(0, stripY - 10, 1, stripY + TILE + 10, 0, 0);
        for (int k = 0; k < 60; k++) {
            int a = (int) (220 * (1 - k / 60f));
            c.fill(k, stripY - 10, k + 1, stripY + TILE + 10, a << 24);
            c.fill(w - k - 1, stripY - 10, w - k, stripY + TILE + 10, a << 24);
        }
        // A tick as each tile passes the marker.
        int idx = (int) ((off + TILE / 2.0) / pitch);
        if (idx != lastTick) {
            lastTick = idx;
            sound(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 0.5f, 1.2f + (float) Math.min(0.8, p * 0.6));
        }
        Ui.text(c, Text.literal(got.crate()), cx, stripY - 30, 0.9f, Ui.CREAM, true);
    }

    private static double smooth(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    /** One reel tile: dark to the rarity's colour, a frame, the item. */
    private static void tile(DrawContext c, int x, int y, int r, ItemStack icon, boolean blank) {
        int col = COLORS[r];
        if (r == 5) {
            c.fill(x, y, x + TILE, y + TILE, 0xFF100404);
            c.fillGradient(x, y + TILE / 2, x + TILE, y + TILE, 0x00E02A2A, 0xC0E02A2A);
        } else {
            c.fill(x, y, x + TILE, y + TILE, 0xFF15130F);
            c.fillGradient(x, y + TILE / 3, x + TILE, y + TILE, 0x00000000, 0xA0000000 | (col & 0xFFFFFF));
        }
        c.fill(x, y + TILE - 3, x + TILE, y + TILE, col);
        c.drawBorder(x, y, TILE, TILE, (col & 0xFFFFFF) | 0x90000000);
        if (!blank) {
            c.getMatrices().push();
            c.getMatrices().translate(x + TILE / 2f - 12, y + TILE / 2f - 14, 0);
            c.getMatrices().scale(1.5f, 1.5f, 1);
            c.drawItem(icon, 0, 0);
            c.getMatrices().pop();
        }
    }

    private static void drawBurst(DrawContext c, int w, int h, int cx, int cy, long t) {
        int col = COLORS[rar];
        float p = Math.min(1, t / (float) burst());
        once("burst", () -> {
            switch (rar) {
                case 5 -> {
                    sound(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 0.8f);
                    sound(SoundEvents.ENTITY_WITHER_SPAWN, 0.5f, 1.3f);
                    sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.8f);
                }
                case 4 -> {
                    sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                    sound(SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1f, 1f);
                }
                case 3 -> {
                    sound(SoundEvents.ENTITY_PLAYER_LEVELUP, 0.9f, 1.1f);
                    sound(SoundEvents.BLOCK_AMETHYST_BLOCK_BREAK, 1f, 0.8f);
                }
                case 2 -> sound(SoundEvents.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
                default -> sound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 0.9f);
            }
        });
        int shx = 0, shy = 0;
        if (rar == 5 && t < 900) {
            // The flash, the lightning, the shake.
            int fa = (int) (230 * Math.max(0, 1 - t / 500f));
            c.fill(0, 0, w, h, (fa << 24) | 0xE02A2A);
            shx = (int) (Math.sin(t * 0.25) * 7 * (1 - t / 900f));
            shy = (int) (Math.cos(t * 0.31) * 5 * (1 - t / 900f));
            if (t < 350) bolt(c, cx, cy, h);
        }
        cx += shx;
        cy += shy;
        // Rays turning behind the prize.
        int n = 10 + rar * 5;
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n + t * 0.0005 * (rar == 5 ? -2 : 1);
            int len = (int) ((70 + rar * 22) * Math.min(1, t / 400f));
            for (int d = 20; d < len; d += 3) {
                int al = (int) (150 * (1 - d / (float) len));
                int x = cx + (int) (Math.cos(a) * d), y = cy - 10 + (int) (Math.sin(a) * d);
                c.fill(x - 1, y - 1, x + 1, y + 1, (al << 24) | (col & 0xFFFFFF));
            }
        }
        // Shockwaves (Epic up).
        if (rar >= 3) {
            for (int k = 0; k < rar - 1; k++) {
                float rp = (t - k * 180) / 700f;
                if (rp >= 0 && rp < 1) ring(c, cx, cy - 10, (int) (20 + rp * (140 + rar * 20)), col, 1 - rp);
            }
        }
        // Sparks for everything.
        Random r = new Random(seed);
        for (int i = 0; i < 30 + rar * 20; i++) {
            double a = r.nextDouble() * Math.PI * 2, sp = 60 + r.nextDouble() * (80 + rar * 30);
            float life = Math.min(1, t / 1100f);
            int x = cx + (int) (Math.cos(a) * sp * life), y = cy - 10 + (int) (Math.sin(a) * sp * life + life * life * 40);
            int al = (int) (255 * (1 - life));
            if (al > 8) c.fill(x, y, x + 2, y + 2, (al << 24) | (i % 3 == 0 ? 0xFFFFFF : col & 0xFFFFFF));
        }
        // Confetti (Legendary up), or rising embers (Mythic).
        if (rar >= 4) {
            int[] pal = rar == 5 ? new int[] {0xE02A2A, 0x7A0A0A, 0xFF6A4A, 0x1A0A0A} : new int[] {0xF2C14E, 0xFFFFFF, 0xE03A3A, 0x5A9AE0, 0x5BD35B};
            for (int i = 0; i < 90; i++) {
                double x0 = r.nextDouble() * w, sp = 40 + r.nextDouble() * 80, sway = r.nextDouble() * 6;
                double ts = t / 1000.0;
                int x = (int) (x0 + Math.sin(ts * 3 + i) * sway * 3);
                int y = rar == 5 ? (int) (h + 10 - sp * ts * 1.4 - r.nextDouble() * 60) : (int) (-10 + sp * ts * 1.6 - r.nextDouble() * 80);
                if (y < -10 || y > h + 10) continue;
                int s = rar == 5 ? 2 : 3;
                c.fill(x, y, x + s, y + (rar == 5 ? 2 : 2 + (i % 2)), 0xFF000000 | pal[i % pal.length]);
            }
        }
        // The prize, rising and growing.
        float k = (float) smooth(Math.min(1, t / 450f));
        float sc = 1.2f + 2.6f * k;
        c.getMatrices().push();
        c.getMatrices().translate(cx - 8 * sc, cy - 18 - 8 * sc - (1 - k) * 20, 0);
        c.getMatrices().scale(sc, sc, 1);
        c.drawItem(icon(got.icon()), 0, 0);
        c.getMatrices().pop();
        // The name of it.
        if (rar == 5) {
            int jx = t < 1400 ? (int) (Math.sin(t * 0.3) * 3) : 0;
            Ui.text(c, Ui.title("MYTHIC"), cx + jx, cy + 22, 2.4f, 0xFFE02A2A, true);
        } else {
            Ui.text(c, Text.literal(NAMES[rar].toUpperCase()), cx, cy + 26, 0.9f + rar * 0.12f, col, true);
        }
        float tp = Math.min(1, Math.max(0, (t - 300) / 400f));
        int ta = (int) (255 * tp);
        if (ta > 8) {
            Ui.text(c, Text.literal(got.got()), cx, cy + (rar == 5 ? 48 : 42), 1.25f, (ta << 24) | 0xEDE3C8, true);
            Ui.text(c, Text.literal("from the " + got.crate()), cx, cy + (rar == 5 ? 64 : 58), 0.65f, (ta << 24) | 0x8F8A7A, true);
        }
        if (p >= 1) Ui.text(c, Text.literal("click to continue"), cx, h - 16, 0.65f, 0xA0FFFFFF, true);
    }

    /** A jagged bolt from the top of the screen down to the prize. */
    private static void bolt(DrawContext c, int cx, int cy, int h) {
        Random r = new Random(Util.getMeasuringTimeMs() / 60);
        for (int b = 0; b < 3; b++) {
            int x = cx + (r.nextInt(120) - 60), y = 0;
            while (y < cy - 20) {
                int nx = x + r.nextInt(21) - 10 + (cx - x) / 6, ny = y + 8 + r.nextInt(10);
                line(c, x, y, nx, ny, 0xFFFFFFFF);
                line(c, x + 1, y, nx + 1, ny, 0xC0FF6A6A);
                x = nx;
                y = ny;
            }
        }
    }
}
