package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

/** Factions: join one, watch the sectors' balance of power, and take work orders. */
public final class FactionScreen extends Screen {
    static final String[] NAMES = {"Survey Corps", "Garrison", "Military Police"};
    static final String[] IDS = {"SURVEY_CORPS", "GARRISON", "MILITARY_POLICE"};
    static final int[] COLORS = {0xFF3F8F4A, 0xFFB8473A, 0xFF4A78C0};
    static final String[] MOTTOS = {"Wings of Freedom. Beyond the walls.", "The walls and the towns within them.",
        "Order in the interior, and the King's favour."};
    private int left, top, w, h;
    private int scroll;

    public FactionScreen() {
        super(Text.literal("Factions"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        clearAndInit();
    }

    private static void act(String a, String arg) {
        ClientPlayNetworking.send(new Net.FactionAction(a, arg));
    }

    @Override
    protected void init() {
        w = Math.min(560, width - 20);
        h = Math.min(320, height - 60);
        left = (width - w) / 2;
        top = Math.max(44, (height - h) / 2 + 10);
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        Net.FactionView v = ClientState.factions;
        if (v == null) return;
        if (v.faction() < 0) {
            int cw = (w - 40) / 3;
            for (int i = 0; i < 3; i++) {
                int f = i;
                AotButton b = addDrawableChild(new AotButton(left + 10 + i * (cw + 10) + 10, top + h - 40, cw - 20, 22, Ui.heading("Join"),
                    () -> act("join", IDS[f])));
                b.accent = COLORS[i];
            }
            return;
        }
        addDrawableChild(new AotButton(left + 10, top + h - 26, 80, 18, Text.literal("Leave"), () -> act("leave", ""))).accent = Ui.RED;
        List<Net.OrderInfo> orders = v.orders();
        int ox = left + 250, y = top + 30;
        int rows = (h - 60) / 26;
        for (int i = scroll; i < Math.min(orders.size(), scroll + rows); i++) {
            Net.OrderInfo o = orders.get(i);
            int bx = left + w - 84;
            if (o.progress() == -2) {
                addDrawableChild(new AotButton(bx, y + 3, 74, 18, Text.literal("Accept"), () -> act("accept", o.id())));
            } else if (o.progress() >= 0) {
                addDrawableChild(new AotButton(bx, y + 3, 74, 18, Text.literal(o.text().startsWith("Slay") ? "Abandon" : "Turn in"),
                    () -> act(o.text().startsWith("Slay") ? "abandon" : "turnin", o.id())));
            }
            y += 26;
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        Net.FactionView v = ClientState.factions;
        if (v == null) return false;
        int rows = (h - 60) / 26;
        scroll = Math.max(0, Math.min(Math.max(0, v.orders().size() - rows), scroll - (int) Math.signum(vy)));
        clearAndInit();
        return true;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.text(c, Ui.title("FACTIONS"), width / 2f, top - 40, 1.3f, Ui.GOLD, true);
        Ui.panel(c, left, top, w, h);
        Net.FactionView v = ClientState.factions;
        if (v == null) return;
        if (v.faction() < 0) {
            Ui.text(c, Text.literal("Choose who you fight for. Work orders shift control of the sectors; the faction in control trades better there."),
                width / 2f, top + 10, 0.75f, Ui.MUTED, true);
            int cw = (w - 40) / 3;
            for (int i = 0; i < 3; i++) {
                int x = left + 10 + i * (cw + 10);
                c.fill(x, top + 30, x + cw, top + h - 10, 0x40000000);
                c.drawBorder(x, top + 30, cw, h - 40, COLORS[i]);
                Ui.crest(c, x + cw / 2 - 24, top + 44, 48, 0.5f);
                Ui.text(c, Ui.heading(NAMES[i]), x + cw / 2f, top + 100, 1.1f, COLORS[i], true);
                Ui.wrapped(c, Text.literal(MOTTOS[i]), x + 10, top + 120, cw - 20, Ui.CREAM);
            }
            return;
        }
        int col = COLORS[v.faction()];
        Ui.text(c, Ui.heading(NAMES[v.faction()]), left + 10, top + 8, 1.1f, col, false);
        Ui.text(c, Text.literal("Reputation " + v.rep()), left + 10, top + 20, 0.75f, Ui.CREAM, false);
        // Sectors: influence bars.
        int y = top + 38;
        Ui.text(c, Ui.heading("Sectors"), left + 10, y, 0.9f, Ui.GOLD, false);
        y += 14;
        for (int s = 0; s < v.sectors().size(); s++) {
            Net.SectorInfo si = v.sectors().get(s);
            boolean here = s == v.here();
            Ui.text(c, Text.literal(si.title() + (here ? "  (you are here)" : "")), left + 10, y, 0.75f, here ? Ui.GOLD : Ui.CREAM, false);
            int bx = left + 10, bw = 220, by = y + 10;
            float acc = 0;
            for (int f = 0; f < si.influence().length && f < 3; f++) {
                int x0 = bx + Math.round(bw * acc / 100f);
                acc += si.influence()[f];
                int x1 = bx + Math.round(bw * acc / 100f);
                c.fill(x0, by, x1, by + 6, COLORS[f]);
            }
            c.drawBorder(bx - 1, by - 1, bw + 2, 8, 0x80000000);
            String ctl = si.controller() < 0 ? "contested" : NAMES[si.controller()];
            Ui.text(c, Text.literal(ctl), bx + bw - Ui.font().getWidth(ctl) * 0.6f, y, 0.6f, si.controller() < 0 ? Ui.MUTED : COLORS[si.controller()], false);
            y += 24;
        }
        long t = v.treasury().length > v.faction() ? v.treasury()[v.faction()] : 0;
        Ui.text(c, Text.literal(String.format(Locale.ROOT, "Treasury: %,d Marks", t)), left + 10, y + 2, 0.7f, Ui.GOLD, false);
        // Orders
        int ox = left + 250;
        long m = v.cycleLeft() / 60;
        Ui.text(c, Ui.heading("Work orders"), ox, top + 8, 1f, Ui.GOLD, false);
        Ui.text(c, Text.literal("New orders in " + (m / 60) + "h " + (m % 60) + "m"), ox, top + 20, 0.65f, Ui.MUTED, false);
        int oy = top + 30;
        int rows = (h - 60) / 26;
        List<Net.OrderInfo> orders = v.orders();
        for (int i = scroll; i < Math.min(orders.size(), scroll + rows); i++) {
            Net.OrderInfo o = orders.get(i);
            if ((i & 1) == 0) c.fill(ox - 4, oy, left + w - 6, oy + 24, 0x22000000);
            int tc = o.progress() == -1 ? Ui.DIM : Ui.CREAM;
            Ui.text(c, Text.literal(o.text()), ox, oy + 3, 0.62f, tc, false);
            String reward = o.marks() + " Marks · +" + o.rep() + " rep" + (o.progress() >= 0 && o.text().startsWith("Slay") ? "  ·  " + o.progress() + "/" + o.qty() : "")
                + (o.progress() == -1 ? "  ·  done" : "");
            Ui.text(c, Text.literal(reward), ox, oy + 13, 0.6f, o.progress() == -1 ? Ui.DIM : Ui.GOLD, false);
            oy += 26;
        }
    }
}
