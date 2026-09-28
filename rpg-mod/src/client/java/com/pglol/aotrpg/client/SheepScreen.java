package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Counting sheep: they hop a fence under the moon; press Space (or click) as each one clears it.
 * Some sheep are titans in a wool coat (don't count those), black sheep still count, and now and
 * then Sasha runs through with a potato. Fourteen jumpers, then you drift off.
 */
public final class SheepScreen extends Screen {
    private enum Kind { SHEEP, BLACK, TITAN, SASHA, LEVI }

    private static final class Hopper {
        Kind kind;
        long born;
        float dur;
        boolean counted, judged;
    }

    private static final int TOTAL = 14;
    private static final String[] DREAMS = {
        "You dream of the ocean beyond the walls.", "You dream of meat. So much meat.", "You dream you can fly. Oh wait, you can.",
        "You dream Levi says \"good job\". Unsettling.", "You dream of a warm bed inside Wall Sina.", "You dream the titans are just really tall sheep."};

    private final Random r;
    private final List<Hopper> hoppers = new ArrayList<>();
    private final long start = Util.getMeasuringTimeMs();
    private long nextAt, flashAt, endAt;
    private int spawned, counted, missed, titans;
    private boolean sasha, sent;
    private String flash = "";
    private int flashColor = Ui.CREAM;
    private final String dream;

    public SheepScreen(int seed) {
        super(Text.literal("Counting Sheep"));
        r = new Random(seed);
        nextAt = start + 1800;
        dream = DREAMS[r.nextInt(DREAMS.length)];
    }

    private Kind roll() {
        int x = r.nextInt(100);
        if (spawned < 2) return Kind.SHEEP;
        if (x < 13) return Kind.TITAN;
        if (x < 23) return Kind.BLACK;
        if (x < 26) return Kind.SASHA;
        if (x < 28) return Kind.LEVI;
        return Kind.SHEEP;
    }

    private int ground() { return height / 2 + 50; }
    private int fenceX() { return width / 2; }

    /** Where a hopper is at time t: x across the screen, y on its arc over the fence. */
    private float[] pos(Hopper h, long t) {
        float k = (t - h.born) / h.dur;
        float x = -40 + k * (width + 80);
        float over = 1 - Math.min(1, Math.abs(x - fenceX()) / 90f);
        float hop = (float) Math.sin(over * Math.PI / 2) * (h.kind == Kind.TITAN ? 38 : 52);
        return new float[] {x, ground() - hop, k};
    }

    private boolean overFence(Hopper h, long t) {
        return Math.abs(pos(h, t)[0] - fenceX()) < 26;
    }

    @Override
    public void tick() {
        long t = Util.getMeasuringTimeMs();
        if (endAt > 0) {
            if (!sent && t > endAt + 2600) finish();
            return;
        }
        if (spawned < TOTAL && t >= nextAt) {
            Hopper h = new Hopper();
            h.kind = roll();
            h.born = t;
            // A little quicker as you go; titans lumber, Sasha sprints.
            h.dur = (3400 - spawned * 90) * (h.kind == Kind.TITAN ? 1.25f : h.kind == Kind.SASHA ? 0.6f : 1f);
            hoppers.add(h);
            spawned++;
            nextAt = t + 1300 + r.nextInt(900) - spawned * 30;
        }
        for (Hopper h : hoppers) {
            if (h.judged || pos(h, t)[0] < fenceX() + 30) continue;
            h.judged = true;
            // A real sheep that went over uncounted is one you missed.
            if (!h.counted && (h.kind == Kind.SHEEP || h.kind == Kind.BLACK)) {
                missed++;
                say("Missed one", 0xFFBFB6A0);
            }
        }
        hoppers.removeIf(h -> pos(h, t)[2] > 1.05f);
        if (spawned >= TOTAL && hoppers.isEmpty()) endAt = t;
    }

    private void count() {
        if (endAt > 0) return;
        long t = Util.getMeasuringTimeMs();
        for (Hopper h : hoppers) {
            if (h.counted || !overFence(h, t)) continue;
            h.counted = true;
            switch (h.kind) {
                case SHEEP, BLACK -> {
                    counted++;
                    say(String.valueOf(counted), Ui.GOLD);
                    client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_SHEEP_AMBIENT, 1f + counted * 0.03f, 0.5f));
                }
                case TITAN -> {
                    titans++;
                    say("That was a titan.", 0xFFE0463A);
                    client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_RAVAGER_AMBIENT, 1.4f, 0.6f));
                }
                case SASHA -> {
                    sasha = true;
                    say("Sasha took a sheep for later.", 0xFFF2C14E);
                }
                case LEVI -> say("Levi is not a sheep. He's cleaning the fence.", 0xFF9AB8D8);
            }
            return;
        }
        missed++;
        say("Nothing there...", 0xFFBFB6A0);
    }

    private void say(String s, int color) {
        flash = s;
        flashColor = color;
        flashAt = Util.getMeasuringTimeMs();
    }

    private void finish() {
        if (sent) return;
        sent = true;
        ClientPlayNetworking.send(new Net.DreamDone(counted, missed, titans, sasha));
        close();
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_SPACE) {
            count();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        count();
        return true;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        long t = Util.getMeasuringTimeMs();
        // Night sky, stars, the moon.
        c.fillGradient(0, 0, width, height, 0xFF0A1024, 0xFF1C2A44);
        Random sr = new Random(7);
        for (int i = 0; i < 90; i++) {
            int x = sr.nextInt(Math.max(1, width)), y = sr.nextInt(Math.max(1, ground() - 40));
            int a = (int) (120 + 100 * Math.sin(t / 600.0 + i));
            c.fill(x, y, x + 1, y + 1, (Math.max(0, Math.min(255, a)) << 24) | 0xFFFFFF);
        }
        int mx = width - 90, my = 50;
        for (int rr = 26; rr > 0; rr -= 2) c.fill(mx - rr, my - rr, mx + rr, my + rr, ((int) (8 + (26 - rr) * 1.2) << 24) | 0xFFF4C0);
        c.fill(mx - 14, my - 14, mx + 14, my + 14, 0xFFF4EDC8);
        c.fill(mx - 6, my - 4, mx - 2, my, 0xFFD8D0A8);
        // The meadow and the fence.
        c.fill(0, ground() + 14, width, height, 0xFF1E3A1E);
        c.fill(0, ground() + 14, width, ground() + 16, 0xFF2E5A2A);
        int fx = fenceX();
        c.fill(fx - 3, ground() - 18, fx + 3, ground() + 16, 0xFF6A4A2A);
        c.fill(fx - 40, ground() - 12, fx + 40, ground() - 8, 0xFF7A5A34);
        c.fill(fx - 40, ground() - 1, fx + 40, ground() + 3, 0xFF7A5A34);
        for (Hopper h : hoppers) draw(c, h, t);
        // The count and what just happened.
        Ui.text(c, Ui.title("COUNTING SHEEP"), width / 2f, 16, 1.3f, Ui.GOLD, true);
        Ui.text(c, Ui.heading(counted + " sheep"), width / 2f, 36, 1f, Ui.CREAM, true);
        long since = t - flashAt;
        if (since < 1200 && !flash.isEmpty()) {
            int a = (int) (255 * (1 - since / 1200f));
            Ui.text(c, Text.literal(flash), width / 2f, ground() - 110 - since / 40f, 1.1f, (Math.max(8, a) << 24) | (flashColor & 0xFFFFFF), true);
        }
        if (t - start < 5000) Ui.text(c, Text.literal("Space as each one clears the fence"), width / 2f, height - 22, 0.8f, Ui.GOLD, true);
        // Drifting off.
        if (endAt > 0) {
            float k = Math.min(1, (t - endAt) / 1600f);
            c.fill(0, 0, width, height, ((int) (235 * k) << 24));
            if (k >= 1) {
                Ui.text(c, Ui.heading("Zzz..."), width / 2f, height / 2f - 16, 1.4f, Ui.GOLD, true);
                Ui.text(c, Text.literal(titans > 0 ? "Something with a very big smile is counting you now." : sasha ? "You dream of potatoes. Someone else does too." : dream),
                    width / 2f, height / 2f + 6, 0.9f, Ui.CREAM, true);
            }
        }
    }

    private void draw(DrawContext c, Hopper h, long t) {
        float[] p = pos(h, t);
        int x = (int) p[0], y = (int) p[1];
        switch (h.kind) {
            case SHEEP, BLACK -> {
                int wool = h.kind == Kind.BLACK ? 0xFF2A2A30 : 0xFFEDEDE6, face = h.kind == Kind.BLACK ? 0xFF111114 : 0xFF3A302A;
                legs(c, x - 9, y, 0xFF2A2420, t);
                legs(c, x + 5, y, 0xFF2A2420, t);
                c.fill(x - 13, y - 14, x + 11, y, wool);
                c.fill(x - 11, y - 16, x + 9, y - 13, wool);
                c.fill(x + 9, y - 16, x + 17, y - 7, face);
                c.fill(x + 14, y - 14, x + 15, y - 13, 0xFFFFFFFF);
            }
            case TITAN -> {
                // A titan in a wool coat: too tall, too pink, grinning.
                legs(c, x - 8, y, 0xFFD89A84, t);
                legs(c, x + 4, y, 0xFFD89A84, t);
                c.fill(x - 12, y - 16, x + 10, y, 0xFFEDEDE6);
                c.fill(x - 4, y - 42, x + 10, y - 16, 0xFFD89A84);
                c.fill(x - 6, y - 50, x + 12, y - 40, 0xFFEDEDE6);
                c.fill(x + 1, y - 36, x + 3, y - 34, 0xFF101010);
                c.fill(x + 6, y - 36, x + 8, y - 34, 0xFF101010);
                c.fill(x - 1, y - 29, x + 10, y - 27, 0xFFFFFFFF);
                c.fill(x - 1, y - 27, x + 10, y - 26, 0xFF8A2A2A);
            }
            case SASHA -> {
                legs(c, x - 3, y, 0xFF4A3A2A, t);
                c.fill(x - 5, y - 22, x + 5, y - 4, 0xFF6A5A3A);
                c.fill(x - 4, y - 30, x + 4, y - 22, 0xFFE8C8A0);
                c.fill(x - 5, y - 32, x + 5, y - 28, 0xFF6A4A2A);
                c.fill(x + 5, y - 18, x + 10, y - 13, 0xFFC9A15A);
            }
            case LEVI -> {
                legs(c, x - 3, y, 0xFF2A2A30, t);
                c.fill(x - 5, y - 22, x + 5, y - 4, 0xFF3A4A3A);
                c.fill(x - 4, y - 30, x + 4, y - 22, 0xFFE8D0B0);
                c.fill(x - 5, y - 32, x + 5, y - 28, 0xFF151515);
                c.fill(x + 5, y - 26, x + 7, y - 2, 0xFF8A6A3A);
                c.fill(x + 3, y - 4, x + 10, y, 0xFFC9B070);
            }
        }
    }

    private static void legs(DrawContext c, int x, int y, int color, long t) {
        int k = (int) (t / 120 % 2);
        c.fill(x, y, x + 3, y + 6 + k, color);
        c.fill(x + 4, y, x + 7, y + 7 - k, color);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        renderBackground(c, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
