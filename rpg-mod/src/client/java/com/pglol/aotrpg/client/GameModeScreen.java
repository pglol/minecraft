package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Game modes: switch between the ones the story has unlocked for this character. */
public final class GameModeScreen extends Screen {
    private int left, top, w, h;

    public GameModeScreen() {
        super(Text.literal("Game mode"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        clearAndInit();
    }

    @Override
    protected void init() {
        w = Math.min(420, width - 20);
        Net.ModeView v = ClientState.modes;
        int n = v == null ? 0 : v.modes().size();
        h = 40 + n * 46;
        left = (width - w) / 2;
        top = Math.max(44, (height - h) / 2);
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        if (v == null) return;
        int y = top + 30;
        for (Net.ModeEntry m : v.modes()) {
            AotButton b = addDrawableChild(new AotButton(left + w - 110, y + 8, 100, 20,
                Ui.heading(m.active() ? "Playing" : m.unlocked() ? "Switch" : "Locked"), () -> ClientPlayNetworking.send(new Net.ModeAction(m.id()))));
            b.selected(m.active());
            b.active = m.unlocked() && !m.active();
            y += 46;
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.text(c, Ui.title("GAME MODE"), width / 2f, top - 40, 1.3f, Ui.GOLD, true);
        Ui.panel(c, left, top, w, h);
        Net.ModeView v = ClientState.modes;
        if (v == null) return;
        Ui.text(c, Text.literal("Story progress: Chapter " + v.chapter() + ". New modes unlock as the story goes on."), left + 10, top + 10, 0.7f, Ui.MUTED, false);
        int y = top + 30;
        for (Net.ModeEntry m : v.modes()) {
            c.fill(left + 6, y, left + w - 6, y + 40, m.active() ? 0x40E0B96A : 0x28000000);
            Ui.text(c, Ui.heading(m.title()), left + 12, y + 4, 1f, m.unlocked() ? Ui.CREAM : Ui.DIM, false);
            Ui.wrapped(c, Text.literal(m.desc()), left + 12, y + 16, w - 140, m.unlocked() ? Ui.MUTED : Ui.DIM);
            if (!m.requirement().isEmpty()) Ui.text(c, Text.literal(m.requirement()), left + w - 110, y + 30, 0.6f, Ui.RED, false);
            y += 46;
        }
    }
}
