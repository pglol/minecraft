package com.pglol.aotrpg.client.story;

import com.pglol.aotrpg.Net;
import com.pglol.aotrpg.client.AotButton;
import com.pglol.aotrpg.client.Ui;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/**
 * A conversation. The speaker's face and name, their words typed out, and your answers. In a
 * party scene the host answers; everyone else sees the same words and can suggest a reply (shown
 * beside it for the host). Number keys pick, Space continues.
 */
public final class DialogueScreen extends Screen {
    private long shownAt;
    private String lastText = "";
    private int left, w, panelTop, panelH;

    public DialogueScreen() {
        super(Text.literal("Dialogue"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        clearAndInit();
    }

    private boolean typing() {
        Net.DialogueView v = StoryClient.dialogue;
        return v != null && shown(v.text()) < v.text().length();
    }

    private int shown(String text) {
        return (int) Math.min(text.length(), (Util.getMeasuringTimeMs() - shownAt) / 22);
    }

    @Override
    protected void init() {
        Net.DialogueView v = StoryClient.dialogue;
        if (v == null) return;
        if (!v.text().equals(lastText)) {
            lastText = v.text();
            shownAt = Util.getMeasuringTimeMs();
        }
        w = Math.min(560, width - 24);
        left = (width - w) / 2;
        int rows = v.choices().isEmpty() ? 1 : v.choices().size();
        panelH = 78 + rows * 22;
        panelTop = height - panelH - 10;
        int y = panelTop + 72;
        if (v.choices().isEmpty()) {
            AotButton b = addDrawableChild(new AotButton(left + w - 150, y, 140, 18, Ui.heading(v.host() ? "Continue ▶" : v.hostName() + " continues…"), () -> pick(-1)));
            b.active = v.host();
            return;
        }
        int n = 1;
        for (Net.ChoiceView c : v.choices()) {
            String label = n + ". " + (c.tag().isEmpty() ? "" : "[" + c.tag() + "] ") + c.text();
            AotButton b = addDrawableChild(new AotButton(left + 90, y, w - 100, 20, Text.literal(label), () -> pick(c.index())));
            if (!c.suggestedBy().isEmpty()) b.sub(Text.literal("suggested by " + c.suggestedBy()).withColor(0xFFD070FF));
            b.active = c.enabled();
            b.accent = c.tag().isEmpty() ? Ui.GOLD : 0xFF9AB8D8;
            y += 22;
            n++;
        }
    }

    private void pick(int index) {
        if (typing()) {
            shownAt = 0;
            return;
        }
        ClientPlayNetworking.send(new Net.DialoguePick(index));
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        Net.DialogueView v = StoryClient.dialogue;
        if (v != null) {
            if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER) {
                if (typing()) shownAt = 0;
                else if (v.choices().isEmpty() && v.host()) pick(-1);
                return true;
            }
            if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
                int i = key - GLFW.GLFW_KEY_1;
                if (i < v.choices().size() && v.choices().get(i).enabled()) pick(v.choices().get(i).index());
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Net.DialogueView v = StoryClient.dialogue;
        if (v == null) return;
        // A soft darkening towards the bottom, like a film frame.
        c.fillGradient(0, height / 2, width, height, 0x00000000, 0xB0000000);
        c.fill(left, panelTop, left + w, panelTop + panelH, 0xE00D0F0C);
        c.fill(left, panelTop, left + w, panelTop + 1, 0xFF8C7248);
        c.fill(left, panelTop + panelH - 1, left + w, panelTop + panelH, 0xFF8C7248);
        // Portrait: the speaker's face (and hat layer) from their skin.
        int px = left + 10, py = panelTop + 10;
        c.fill(px - 1, py - 1, px + 67, py + 67, 0xFF8C7248);
        c.fill(px, py, px + 66, py + 66, 0xFF1A1812);
        if (!v.skin().isEmpty()) {
            var tex = StoryClient.skinTexture(v.skin());
            c.drawTexture(tex, px + 1, py + 1, 64, 64, 8, 8, 8, 8, 64, 64);
            c.drawTexture(tex, px + 1, py + 1, 64, 64, 40, 8, 8, 8, 64, 64);
        } else {
            Ui.text(c, Ui.title("?"), px + 33, py + 22, 2f, 0xFF8C7248, true);
        }
        int tx = left + 90;
        Ui.text(c, Ui.title(v.speaker().isEmpty() ? "" : v.speaker()), tx, panelTop + 8, 1.1f, 0xFFE3C07A, false);
        String text = v.text().substring(0, shown(v.text()));
        Ui.wrapped(c, Text.literal(text), tx, panelTop + 24, w - 100, 0xFFEEE4CB);
        if (!v.host()) {
            Ui.text(c, Text.literal(v.hostName() + " decides · click a reply to suggest it"), left + 10, panelTop + panelH - 12, 0.65f, 0xFFD070FF, false);
        }
    }

}
