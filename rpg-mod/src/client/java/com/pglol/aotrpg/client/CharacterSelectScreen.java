package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Discipline;
import com.pglol.aotrpg.Net;
import com.pglol.aotrpg.Origin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Choose a character: continue the last one, switch to another, start a new one or delete one.
 * Each character keeps its own inventory, satchel, stats, quests and Marks.
 */
public final class CharacterSelectScreen extends Screen {
    private int armed = -1; // slot waiting for a second click to delete
    private static final int CW = 150, CH = 186;

    public CharacterSelectScreen() {
        super(Text.literal("Characters"));
    }

    public void refresh() {
        clearAndInit();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** Leaving the screen continues as the current character. */
    @Override
    public void close() {
        Net.CharacterList l = ClientState.characters;
        if (l != null) ClientPlayNetworking.send(new Net.CharacterAction("play", l.active()));
        super.close();
    }

    private int cards() {
        Net.CharacterList l = ClientState.characters;
        if (l == null) return 0;
        return l.list().size() + (l.list().size() < l.max() ? 1 : 0);
    }

    private int cardX(int i) {
        int n = cards(), gap = 10;
        int total = n * CW + (n - 1) * gap;
        return (width - total) / 2 + i * (CW + gap);
    }

    private int top() {
        return Math.max(64, height / 2 - CH / 2 + 10);
    }

    @Override
    protected void init() {
        Net.CharacterList l = ClientState.characters;
        if (l == null) return;
        int y = top();
        int i = 0;
        for (Net.CharacterEntry e : l.list()) {
            int x = cardX(i++);
            boolean active = e.slot() == l.active();
            String play = active ? (e.created() ? "Continue" : "Create") : (e.created() ? "Play" : "Create");
            AotButton b = addDrawableChild(new AotButton(x + 10, y + CH - 50, CW - 20, 20, Ui.heading(play), () -> {
                ClientPlayNetworking.send(new Net.CharacterAction("play", e.slot()));
                super.close();
            }));
            b.selected(active);
            boolean armedHere = armed == e.slot();
            AotButton del = addDrawableChild(new AotButton(x + 10, y + CH - 26, CW - 20, 18,
                Text.literal(armedHere ? "Click again to delete" : "Delete"), () -> {
                    if (armed == e.slot()) {
                        ClientPlayNetworking.send(new Net.CharacterAction("delete", e.slot()));
                        armed = -1;
                    } else armed = e.slot();
                    clearAndInit();
                }));
            del.accent = Ui.RED;
            del.active = !active || l.list().size() == 1;
        }
        if (l.list().size() < l.max()) {
            int x = cardX(i);
            addDrawableChild(new AotButton(x + 10, y + CH / 2 - 12, CW - 20, 24, Ui.heading("+ New Character"), () -> {
                ClientPlayNetworking.send(new Net.CharacterAction("new", 0));
                super.close();
            }));
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.crest(c, width / 2 - 14, 6, 28, 0.9f);
        Ui.text(c, Ui.title("CHOOSE YOUR CHARACTER"), width / 2f, 38, 1.3f, Ui.GOLD, true);
        Net.CharacterList l = ClientState.characters;
        if (l == null) return;
        Ui.text(c, Text.literal(l.list().size() + " / " + l.max() + " characters  ·  each keeps its own gear, satchel, stats, quests and Marks"),
            width / 2f, 52, 0.75f, Ui.MUTED, true);
        int y = top();
        int i = 0;
        for (Net.CharacterEntry e : l.list()) {
            int x = cardX(i++);
            boolean active = e.slot() == l.active();
            Ui.panel(c, x, y, CW, CH);
            if (active) c.drawBorder(x - 1, y - 1, CW + 2, CH + 2, Ui.GOLD);
            if (!e.created()) {
                Ui.text(c, Ui.heading("Not created yet"), x + CW / 2f, y + 30, 1f, Ui.MUTED, true);
                continue;
            }
            Ui.crest(c, x + CW / 2 - 20, y + 8, 40, 0.18f);
            Ui.text(c, Ui.heading(e.name()), x + CW / 2f, y + 12, 1f, Ui.CREAM, true);
            Ui.text(c, Ui.title("Lv " + e.level()), x + CW / 2f, y + 26, 1.3f, Ui.GOLD, true);
            if (e.discipline() >= 0) {
                Ui.text(c, Text.literal(Discipline.values()[e.discipline()].title), x + CW / 2f, y + 46, 0.9f, Ui.disciplineColor(e.discipline()), true);
            }
            if (e.origin() >= 0) Ui.text(c, Text.literal(Origin.values()[e.origin()].title), x + CW / 2f, y + 58, 0.8f, Ui.MUTED, true);
            Ui.divider(c, x + 12, y + 72, CW - 24);
            row(c, x, y + 80, "Marks", String.format(Locale.ROOT, "%,d", e.marks()), Ui.GOLD);
            row(c, x, y + 92, "Bloodline rerolls", e.rerolls() + " / " + l.maxRerolls(), e.rerolls() >= l.maxRerolls() ? Ui.RED : Ui.CREAM);
            row(c, x, y + 104, "Last played", e.lastPlayed() == 0 ? "-" : ago(e.lastPlayed()), Ui.CREAM);
            if (active) Ui.text(c, Text.literal("Current character"), x + CW / 2f, y + 120, 0.7f, Ui.GOLD, true);
        }
        if (l.list().size() < l.max()) {
            int x = cardX(i);
            Ui.panel(c, x, y, CW, CH);
            Ui.text(c, Text.literal("Start a new life in Paradis"), x + CW / 2f, y + CH / 2f + 20, 0.7f, Ui.MUTED, true);
        }
        Ui.text(c, Text.literal("Esc: continue as your current character"), width / 2f, y + CH + 10, 0.7f, Ui.MUTED, true);
    }

    private void row(DrawContext c, int x, int y, String k, String v, int col) {
        Ui.text(c, Text.literal(k), x + 12, y, 0.75f, Ui.MUTED, false);
        Ui.text(c, Text.literal(v), x + CW - 12 - Ui.font().getWidth(v) * 0.75f, y, 0.75f, col, false);
    }

    private static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        if (m < 60 * 24) return m / 60 + " h ago";
        return m / 60 / 24 + " days ago";
    }
}
