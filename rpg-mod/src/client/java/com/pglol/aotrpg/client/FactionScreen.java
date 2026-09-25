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
    /** Right panel: 0 work orders, 1 standings, 2 Call to Arms. */
    private static int tab;
    private int ticks;

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
        String[] tabs = {"Work orders", "Standings", v.event().isEmpty() ? "Call to Arms" : "⚔ Call to Arms"};
        for (int i = 0; i < 3; i++) {
            int t = i;
            AotButton b = addDrawableChild(new AotButton(left + i * 104, top - 22, 100, 18, Text.literal(tabs[i]), () -> {
                tab = t;
                scroll = 0;
                clearAndInit();
            }));
            if (tab == i) b.accent = Ui.GOLD;
            else if (i == 2 && !v.event().isEmpty()) b.accent = 0xFFE04A3A;
        }
        if (tab != 0) return;
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
    public void tick() {
        // Keep the event timer and kill board live.
        if (++ticks % 60 == 0 && ClientState.factions != null && ClientState.factions.faction() >= 0) act("view", "");
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
        int held = v.held().length > v.faction() ? v.held()[v.faction()] : 0;
        float mul = v.mult().length > v.faction() ? v.mult()[v.faction()] : 1;
        Ui.text(c, Text.literal("You hold " + held + " sector" + (held == 1 ? "" : "s") + " · faction rewards +" + Math.round((mul - 1) * 100) + "%"
            + (v.leader() == v.faction() ? " · LEADING" : "")), left + 10, y + 14, 0.65f, v.leader() == v.faction() ? Ui.GOLD : Ui.CREAM, false);
        int ox = left + 250;
        if (tab == 1) {
            standings(c, v, ox);
            return;
        }
        if (tab == 2) {
            callToArms(c, v, ox);
            return;
        }
        // Orders
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
            String reward = Math.round(o.marks() * mul) + " Marks · +" + Math.round(o.rep() * mul) + " rep" + (o.progress() >= 0 && o.text().startsWith("Slay") ? "  ·  " + o.progress() + "/" + o.qty() : "")
                + (o.progress() == -1 ? "  ·  done" : "");
            Ui.text(c, Text.literal(reward), ox, oy + 13, 0.6f, o.progress() == -1 ? Ui.DIM : Ui.GOLD, false);
            oy += 26;
        }
    }

    private void standings(DrawContext c, Net.FactionView v, int ox) {
        int right = left + w - 10;
        Ui.text(c, Ui.heading("The war for the walls"), ox, top + 8, 1f, Ui.GOLD, false);
        Ui.text(c, Text.literal("Each sector you control: +8% Marks and reputation from orders and Calls to Arms."), ox, top + 22, 0.6f, Ui.MUTED, false);
        Ui.text(c, Text.literal("Most sectors: +10% more, and +10% Marks from everything."), ox, top + 31, 0.6f, Ui.MUTED, false);
        int y = top + 46;
        Integer[] order = {0, 1, 2};
        java.util.Arrays.sort(order, (a, b) -> (v.held().length > b ? v.held()[b] : 0) - (v.held().length > a ? v.held()[a] : 0));
        int rank = 1;
        for (int f : order) {
            boolean lead = v.leader() == f, mine = v.faction() == f;
            c.fill(ox - 4, y, right, y + 44, mine ? 0x26FFFFFF : 0x22000000);
            c.fill(ox - 4, y, ox - 1, y + 44, COLORS[f]);
            Ui.text(c, Ui.heading("#" + rank++ + "  " + NAMES[f]), ox + 4, y + 4, 0.95f, COLORS[f], false);
            if (lead) Ui.text(c, Ui.heading("♛ LEADING"), right - 6 - Ui.font().getWidth("♛ LEADING") * 0.75f, y + 5, 0.75f, Ui.GOLD, false);
            int held = v.held().length > f ? v.held()[f] : 0;
            float mul = v.mult().length > f ? v.mult()[f] : 1;
            long tr = v.treasury().length > f ? v.treasury()[f] : 0;
            Ui.text(c, Text.literal(held + " sector" + (held == 1 ? "" : "s") + " held · rewards +" + Math.round((mul - 1) * 100) + "%"
                + String.format(Locale.ROOT, " · treasury %,d", tr)), ox + 4, y + 18, 0.65f, Ui.CREAM, false);
            StringBuilder names = new StringBuilder();
            for (Net.SectorInfo si : v.sectors()) if (si.controller() == f) names.append(names.length() == 0 ? "" : ", ").append(si.title());
            Ui.text(c, Text.literal(names.length() == 0 ? "No sectors" : names.toString()), ox + 4, y + 30, 0.6f, Ui.MUTED, false);
            y += 50;
        }
        Ui.text(c, Text.literal("Control shifts with work orders, titan kills and Calls to Arms. See it on the world map [M]."),
            ox, y + 4, 0.6f, Ui.DIM, false);
    }

    private void callToArms(DrawContext c, Net.FactionView v, int ox) {
        int right = left + w - 10;
        Ui.text(c, Ui.heading("Call to Arms"), ox, top + 8, 1f, 0xFFE04A3A, false);
        if (v.event().isEmpty()) {
            long m = v.nextEvent() / 60;
            Ui.text(c, Text.literal("All quiet. The next titan march is expected in about " + Math.max(1, m) + " min."), ox, top + 24, 0.7f, Ui.CREAM, false);
            int y = top + 44;
            for (String line : new String[] {
                "When titans march on a town, every soldier online is called to defend it.",
                "It shows on your map and compass as a red marker.",
                "Each kill pays Marks, reputation and pass XP, scaled by your faction's sectors.",
                "Your faction gains ground in that sector with every kill.",
                "The MVP (most kills) wins special gear, and sometimes a rare cosmetic.",
                "Hold the town (clear every wave) and the top faction gains much more."}) {
                Ui.text(c, Text.literal("• " + line), ox, y, 0.62f, Ui.MUTED, false);
                y += 13;
            }
            return;
        }
        long s = v.eventLeft();
        Ui.text(c, Text.literal("Defend " + v.event()), ox, top + 24, 0.8f, Ui.CREAM, false);
        Ui.text(c, Text.literal(String.format(Locale.ROOT, "%d:%02d left · your kills: %d · marked on your map", s / 60, s % 60, v.myKills())),
            ox, top + 36, 0.65f, Ui.GOLD, false);
        int y = top + 54;
        Ui.text(c, Ui.heading("Top defenders"), ox, y, 0.85f, Ui.GOLD, false);
        y += 14;
        if (v.eventTop().isEmpty()) Ui.text(c, Text.literal("No kills yet. Be the first!"), ox, y, 0.65f, Ui.MUTED, false);
        int i = 0;
        for (String e : v.eventTop()) {
            String[] parts = e.split("\u0000");
            if ((i & 1) == 0) c.fill(ox - 4, y - 2, right, y + 12, 0x22000000);
            Ui.text(c, Text.literal((i == 0 ? "♛ " : (i + 1) + ". ") + parts[0]), ox, y, 0.7f, i == 0 ? Ui.GOLD : Ui.CREAM, false);
            String k = (parts.length > 1 ? parts[1] : "0") + " kills";
            Ui.text(c, Text.literal(k), right - 4 - Ui.font().getWidth(k) * 0.7f, y, 0.7f, Ui.CREAM, false);
            y += 14;
            i++;
        }
        Ui.text(c, Text.literal("MVP spoils: Rare gear (6+ kills Epic, 12+ Legendary), 35% chance of a rare cosmetic."), ox, top + h - 22, 0.6f, Ui.DIM, false);
    }
}
