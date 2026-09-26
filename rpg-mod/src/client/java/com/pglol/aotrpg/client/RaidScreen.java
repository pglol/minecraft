package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** The Raid Commander: pick a shifter to hunt and how hard, then lead your party out. */
public final class RaidScreen extends Screen {
    private static final String[] DIFFS = {"Normal", "Hard", "Nightmare"};
    private static final int[] DIFF_COL = {0xFF5BD35B, 0xFFE0A040, 0xFFE04A3A};
    private static final String[] DIFF_TXT = {"6 nape strikes · 2 waves · Rare gear", "10 strikes · 3 waves · +10 levels · Epic gear",
        "15 strikes · 4 waves · +20 levels · Legendary gear"};
    private static int pick, diff;
    private int left, top, w, h;

    public RaidScreen() {
        super(Text.literal("Raids"));
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
        w = Math.min(520, width - 20);
        h = Math.min(300, height - 60);
        left = (width - w) / 2;
        top = Math.max(44, (height - h) / 2 + 10);
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        Net.RaidView v = ClientState.raids;
        if (v == null) return;
        int lw = w / 2;
        int y = top + 28;
        for (int i = 0; i < v.bosses().size(); i++) {
            Net.RaidBoss b = v.bosses().get(i);
            int k = i;
            AotButton btn = addDrawableChild(new AotButton(left + 8, y, lw - 16, 26, Ui.heading(b.name()), () -> {
                pick = k;
                refresh();
            }));
            btn.sub(Text.literal(b.available() ? "Recommended level " + b.level() : "Not in this world"));
            btn.selected = pick == i;
            btn.active = b.available();
            btn.accent = 0xFFC0263A;
            y += 29;
            if (y > top + h - 30) break;
        }
        int rx = left + lw + 8, rw = w - lw - 16;
        for (int i = 0; i < 3; i++) {
            int d = i;
            AotButton b = addDrawableChild(new AotButton(rx + i * (rw / 3), top + 40, rw / 3 - 4, 20, Text.literal(DIFFS[i]), () -> {
                diff = d;
                refresh();
            }));
            b.selected = diff == i;
            b.accent = DIFF_COL[i];
        }
        AotButton go = addDrawableChild(new AotButton(rx, top + h - 34, rw, 24, Ui.heading(v.active().isEmpty() ? "Lead the raid" : "Raid in progress"), () -> {
            if (pick < v.bosses().size()) ClientPlayNetworking.send(new Net.RaidAction("start", v.bosses().get(pick).id(), diff));
            close();
        }));
        go.accent = 0xFFC0263A;
        go.active = v.active().isEmpty() && pick < v.bosses().size() && v.bosses().get(pick).available();
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.text(c, Ui.title("BOSS RAIDS"), width / 2f, top - 40, 1.3f, 0xFFE04A3A, true);
        Ui.panel(c, left, top, w, h);
        Net.RaidView v = ClientState.raids;
        if (v == null) return;
        int lw = w / 2;
        Ui.text(c, Ui.heading("Shifters"), left + 10, top + 10, 0.9f, Ui.GOLD, false);
        c.fill(left + lw, top + 8, left + lw + 1, top + h - 8, 0x607A6139);
        int rx = left + lw + 8, rw = w - lw - 16;
        Ui.text(c, Ui.heading("Difficulty"), rx, top + 10, 0.9f, Ui.GOLD, false);
        Ui.text(c, Text.literal(DIFF_TXT[diff]), rx, top + 66, 0.62f, DIFF_COL[diff], false);
        int y = top + 84;
        Ui.text(c, Ui.heading("Your raid party"), rx, y, 0.85f, Ui.GOLD, false);
        y += 12;
        for (String s : v.party()) {
            Ui.text(c, Text.literal("• " + s), rx, y, 0.7f, Ui.CREAM, false);
            y += 10;
        }
        Ui.text(c, Text.literal("Party members within 24 blocks come along (up to 5)."), rx, y + 4, 0.55f, Ui.MUTED, false);
        y += 22;
        for (String line : new String[] {
            "You're taken beyond the walls. Clear the waves,",
            "then the shifter comes: cut its nape again and again.",
            "15 minutes. Stray too far or fall and you're out.",
            "Win: Marks, pass and regiment XP, fine gear."}) {
            Ui.text(c, Text.literal(line), rx, y, 0.58f, Ui.MUTED, false);
            y += 9;
        }
        if (!v.active().isEmpty()) Ui.text(c, Text.literal("Active: " + v.active()), rx, top + h - 46, 0.65f, 0xFFE04A3A, false);
    }
}
