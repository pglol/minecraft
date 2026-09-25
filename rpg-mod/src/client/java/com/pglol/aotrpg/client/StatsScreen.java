package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;

/** Your stats, the server's, and the leaderboards. */
public final class StatsScreen extends Screen {
    private static final String[] TABS = {"My stats", "Server", "Leaderboards"};
    private final Screen parent;
    private int tab;
    private int left, top, w, h;
    private boolean asked;

    public StatsScreen(Screen parent, int tab) {
        super(Text.literal("Stats"));
        this.parent = parent;
        this.tab = tab;
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
        if (!asked) {
            asked = true;
            ClientPlayNetworking.send(new Net.StatsRequest());
        }
        w = Math.min(520, width - 20);
        h = Math.min(300, height - 70);
        left = (width - w) / 2;
        top = Math.max(50, (height - h) / 2 + 10);
        for (int i = 0; i < TABS.length; i++) {
            int t = i;
            AotButton b = addDrawableChild(new AotButton(left + i * 104, top - 22, 100, 18, Text.literal(TABS[i]), () -> {
                tab = t;
                clearAndInit();
            }));
            if (tab == i) b.accent = Ui.GOLD;
        }
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 18, Text.literal("✕"), this::close));
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.text(c, Ui.title(tab == 2 ? "LEADERBOARDS" : tab == 1 ? "SERVER STATS" : "PERSONAL STATS"), width / 2f, top - 44, 1.3f, Ui.GOLD, true);
        Ui.panel(c, left, top, w, h);
        Net.StatsView v = ClientState.stats;
        if (v == null) {
            Ui.text(c, Text.literal("Loading..."), width / 2f, top + h / 2f, 0.9f, Ui.MUTED, true);
            return;
        }
        if (tab < 2) {
            lines(c, tab == 0 ? v.mine() : v.server(), left + 14, top + 12, w - 28);
            return;
        }
        // Leaderboards: a grid of small tables.
        int cols = w >= 480 ? 3 : 2;
        int cw = (w - 20 - (cols - 1) * 8) / cols;
        int ch = (h - 20 - 8) / 2;
        List<Net.StatBoard> boards = v.boards();
        String me = ClientState.profile == null ? "" : ClientState.profile.name();
        for (int i = 0; i < boards.size() && i < cols * 2; i++) {
            Net.StatBoard b = boards.get(i);
            int x = left + 10 + (i % cols) * (cw + 8), y = top + 10 + (i / cols) * (ch + 8);
            c.fill(x, y, x + cw, y + ch, 0x30000000);
            c.drawBorder(x, y, cw, ch, 0x40E0B96A);
            Ui.text(c, Ui.heading(b.title()), x + 6, y + 5, 0.85f, Ui.GOLD, false);
            int yy = y + 19;
            int rank = 1;
            for (Net.StatLine r : b.rows()) {
                if (yy > y + ch - 10) break;
                boolean mine = r.label().equals(me);
                int col = rank == 1 ? 0xFFF2C14E : rank == 2 ? 0xFFD0D4DA : rank == 3 ? 0xFFCD8B55 : Ui.CREAM;
                Ui.text(c, Text.literal(rank + ". " + trim(r.label(), cw - 70)), x + 6, yy, 0.65f, mine ? 0xFF7FE0FF : col, false);
                Ui.text(c, Text.literal(r.value()), x + cw - 6 - Ui.font().getWidth(r.value()) * 0.65f, yy, 0.65f, Ui.CREAM, false);
                yy += 11;
                rank++;
            }
            if (b.rows().isEmpty()) Ui.text(c, Text.literal("Nobody yet"), x + 6, yy, 0.65f, Ui.DIM, false);
        }
    }

    private String trim(String s, int px) {
        return textRenderer.trimToWidth(s, (int) (px / 0.65f));
    }

    private void lines(DrawContext c, List<Net.StatLine> lines, int x, int y, int width) {
        if (lines.isEmpty()) {
            Ui.text(c, Text.literal("Create a character to start tracking your stats."), x, y, 0.8f, Ui.MUTED, false);
            return;
        }
        // Two columns when there is room.
        int cols = lines.size() > 10 && width > 380 ? 2 : 1;
        int per = (lines.size() + cols - 1) / cols;
        int colW = (width - (cols - 1) * 16) / cols;
        for (int i = 0; i < lines.size(); i++) {
            Net.StatLine l = lines.get(i);
            int cx = x + (i / per) * (colW + 16), cy = y + (i % per) * 26;
            c.fill(cx - 4, cy - 3, cx + colW, cy + 21, (i & 1) == 0 ? 0x24000000 : 0x12000000);
            Ui.text(c, Text.literal(l.label()), cx, cy, 0.6f, Ui.MUTED, false);
            Ui.text(c, Text.literal(textRenderer.trimToWidth(l.value(), (int) ((colW - 4) / 0.8f))), cx, cy + 9, 0.8f, Ui.CREAM, false);
        }
    }
}
