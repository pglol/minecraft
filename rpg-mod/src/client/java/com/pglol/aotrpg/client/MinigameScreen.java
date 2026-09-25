package com.pglol.aotrpg.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * Quick lifestyle minigames that decide the quality of the work (0..1):
 * STRIKE (forge, cooking): a marker sweeps the bar, press Space or click in the bright zone;
 * three strikes, each scored. REEL (fishing): hold Space or the mouse to lift your catch zone
 * and keep the fish inside it until the line is reeled in.
 */
public final class MinigameScreen extends Screen {
    public enum Kind { STRIKE, REEL }

    private final Kind kind;
    private final String title, hint;
    private final float difficulty;
    private final Consumer<Float> done;
    private final long start = Util.getMeasuringTimeMs();
    private boolean finished;
    // STRIKE
    private int strikes;
    private float score;
    private final float zone;
    private String lastGrade = "";
    private long lastAt;
    // REEL
    private float fish = 0.5f, fishVel, bar = 0.3f, barVel, progress = 0.3f;
    private boolean holding;
    private long lastTick = Util.getMeasuringTimeMs();

    private net.minecraft.sound.SoundEvent strikeSound;

    /** The sound a strike makes (the anvil by default). */
    public MinigameScreen sound(net.minecraft.sound.SoundEvent s) {
        strikeSound = s;
        return this;
    }

    public MinigameScreen(Kind kind, String title, String hint, float difficulty, Consumer<Float> done) {
        super(Text.literal(title));
        this.kind = kind;
        this.title = title;
        this.hint = hint;
        this.difficulty = Math.max(0, Math.min(1, difficulty));
        this.done = done;
        this.zone = 0.18f - 0.08f * this.difficulty;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private float marker() {
        float t = (Util.getMeasuringTimeMs() - start) / 1000f;
        float speed = 0.9f + difficulty * 1.1f + strikes * 0.25f;
        return (float) (0.5 + 0.5 * Math.sin(t * speed * Math.PI));
    }

    private void finish(float q) {
        if (finished) return;
        finished = true;
        close();
        done.accept(Math.max(0, Math.min(1, q)));
    }

    private void strike() {
        if (kind != Kind.STRIKE || finished) return;
        float d = Math.abs(marker() - 0.5f);
        float s;
        if (d < zone * 0.35f) {
            s = 1f;
            lastGrade = "Perfect!";
        } else if (d < zone) {
            s = 0.65f;
            lastGrade = "Good";
        } else {
            s = 0.15f;
            lastGrade = "Miss";
        }
        if (strikeSound != null) client.getSoundManager().play(PositionedSoundInstance.master(strikeSound, s >= 1 ? 1.4f : 1f, 0.8f));
        else client.getSoundManager().play(PositionedSoundInstance.master(s >= 1 ? SoundEvents.BLOCK_ANVIL_LAND : SoundEvents.BLOCK_ANVIL_HIT, s >= 1 ? 1.4f : 1f, 0.35f));
        score += s;
        strikes++;
        lastAt = Util.getMeasuringTimeMs();
        if (strikes >= 3) finish(score / 3f);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_SPACE) {
            if (kind == Kind.STRIKE) strike();
            else holding = true;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            finish(kind == Kind.REEL ? 0 : score / 3f);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_SPACE) holding = false;
        return super.keyReleased(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (kind == Kind.STRIKE) strike();
        else holding = true;
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        holding = false;
        return true;
    }

    private void tickReel() {
        long now = Util.getMeasuringTimeMs();
        float dt = Math.min(0.05f, (now - lastTick) / 1000f);
        lastTick = now;
        // The fish darts about; harder fish dart more.
        if (Math.random() < dt * (1.5 + difficulty * 3)) fishVel = (float) ((Math.random() - 0.5) * (1.2 + difficulty * 1.6));
        fish = Math.max(0.03f, Math.min(0.97f, fish + fishVel * dt));
        if (fish <= 0.03f || fish >= 0.97f) fishVel = -fishVel;
        barVel += (holding ? 2.6f : -2.2f) * dt;
        barVel *= 0.92f;
        bar = Math.max(0, Math.min(1 - 0.28f, bar + barVel * dt));
        if (bar <= 0 || bar >= 1 - 0.28f) barVel = 0;
        boolean inside = fish >= bar && fish <= bar + 0.28f;
        progress += (inside ? 0.22f : -0.16f - difficulty * 0.08f) * dt;
        if (progress >= 1) finish(0.5f + 0.5f * (1 - Math.min(1, (now - start) / 20000f)));
        else if (progress <= 0) finish(0);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0x70000000, 0xA0000000);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        super.render(c, mouseX, mouseY, delta);
        int w = 220, x = width / 2 - w / 2, y = height / 2 - 30;
        Ui.panel(c, x - 12, y - 34, w + 24, 88);
        Ui.text(c, Ui.title(title.toUpperCase(java.util.Locale.ROOT)), width / 2f, y - 26, 1.1f, Ui.GOLD, true);
        Ui.text(c, Text.literal(hint), width / 2f, y - 12, 0.7f, Ui.MUTED, true);
        if (kind == Kind.STRIKE) {
            c.fill(x, y, x + w, y + 14, 0xFF1A1512);
            int zw = Math.round(w * zone * 2), zc = x + w / 2;
            c.fill(zc - zw / 2, y, zc + zw / 2, y + 14, 0xFF3F7A3F);
            int pw = Math.round(w * zone * 0.7f);
            c.fill(zc - pw / 2, y, zc + pw / 2, y + 14, 0xFFE0B96A);
            int mx = x + Math.round(marker() * w);
            c.fill(mx - 1, y - 3, mx + 2, y + 17, 0xFFFFFFFF);
            c.drawBorder(x - 1, y - 1, w + 2, 16, Ui.TRIM);
            for (int i = 0; i < 3; i++) c.fill(width / 2 - 16 + i * 12, y + 22, width / 2 - 8 + i * 12, y + 30, i < strikes ? Ui.GOLD : 0xFF3A3530);
            if (Util.getMeasuringTimeMs() - lastAt < 600 && !lastGrade.isEmpty()) {
                Ui.text(c, Ui.heading(lastGrade), width / 2f, y + 34, 1f, lastGrade.equals("Miss") ? Ui.RED : Ui.GOLD, true);
            }
        } else {
            tickReel();
            int h = 14;
            c.fill(x, y, x + w, y + h, 0xFF10202A);
            int bx = x + Math.round(bar * w), bw = Math.round(0.28f * w);
            c.fill(bx, y, bx + bw, y + h, holding ? 0xFF4FA05F : 0xFF3F7A4F);
            int fx = x + Math.round(fish * w);
            c.fill(fx - 3, y + 2, fx + 3, y + h - 2, 0xFFE8C070);
            c.drawBorder(x - 1, y - 1, w + 2, h + 2, Ui.TRIM);
            Ui.bar(c, x, y + 22, w, 6, progress, 0xFF5AA0E0);
        }
    }
}
