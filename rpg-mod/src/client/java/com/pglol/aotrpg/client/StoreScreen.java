package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** The server store (test run, no website link yet): this week's shop for Gold and crates for Marks or Gold. */
public final class StoreScreen extends Screen {
    /** The last store view from the server. */
    public static Net.StoreView view;
    private final Screen parent;

    public StoreScreen(Screen parent) {
        super(Text.literal("Store"));
        this.parent = parent;
    }

    private int panelW() { return Math.min(width - 32, 420); }
    private int left() { return width / 2 - panelW() / 2; }
    private int top() { return Math.max(8, height / 2 - 130); }

    @Override
    protected void init() {
        if (view == null || !view.open()) ClientPlayNetworking.send(new Net.StoreAction("open", ""));
        build();
    }

    /** New data from the server: rebuild the buttons. */
    public void refresh() {
        clearChildren();
        build();
    }

    private void build() {
        int w = panelW(), x = left(), y = top();
        addDrawableChild(new AotButton(width / 2 - 50, y + 262, 100, 18, Text.literal("Back"), this::close));
        if (view == null) return;
        int cols = w >= 400 ? 3 : 2, gap = 6, cw = (w - 20 - gap * (cols - 1)) / cols;
        for (int i = 0; i < view.offers().size(); i++) {
            Net.StoreOffer o = view.offers().get(i);
            int cx = x + 10 + (i % cols) * (cw + gap), cy = y + 46 + (i / cols) * 50;
            AotButton b = new AotButton(cx + 4, cy + 30, cw - 8, 14,
                Text.literal(o.owned() ? "Owned" : o.price() + " Gold"), () -> ClientPlayNetworking.send(new Net.StoreAction("buy", o.id())));
            b.active = !o.owned() && ClientState.gold >= o.price();
            addDrawableChild(b);
        }
        int rows = (view.offers().size() + cols - 1) / cols;
        int cy = y + 46 + rows * 50 + 22, n = Math.max(1, view.crates().size()), ccw = (w - 20 - gap * (n - 1)) / n;
        for (int i = 0; i < view.crates().size(); i++) {
            Net.StoreCrate c = view.crates().get(i);
            int cx = x + 10 + i * (ccw + gap);
            AotButton b = new AotButton(cx + 4, cy + 44, ccw - 8, 14,
                Text.literal("Open - " + c.price() + (c.gold() ? " Gold" : " Marks")), () -> ClientPlayNetworking.send(new Net.StoreAction("crate", c.id())));
            b.active = (c.gold() ? ClientState.gold : ClientState.marks) >= c.price();
            addDrawableChild(b);
        }
    }

    @Override
    public void close() {
        view = null;
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void tick() {
        // Wallet changes (a buy, a crate) re-enable or grey out the buttons.
        if (lastGold != ClientState.gold || lastMarks != ClientState.marks) {
            lastGold = ClientState.gold;
            lastMarks = ClientState.marks;
            refresh();
        }
    }
    private long lastGold = -1, lastMarks = -1;

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        int w = panelW(), x = left(), y = top();
        Ui.panel(c, x, y, w, 286);
        Ui.text(c, Ui.title("STORE"), width / 2f, y + 8, 1.4f, Ui.GOLD, true);
        String purse = ClientState.gold + " Gold   " + ClientState.marks + " Marks";
        Ui.text(c, Text.literal(purse), x + w - 10 - Ui.font().getWidth(purse) * 0.8f, y + 12, 0.8f, Ui.GOLD, false);
        if (view == null) {
            Ui.text(c, Text.literal("Loading..."), width / 2f, y + 80, 1f, Ui.CREAM, true);
            return;
        }
        long s = view.secondsLeft();
        String left = s > 86400 ? (s / 86400) + "d " + (s % 86400 / 3600) + "h" : (s / 3600) + "h " + (s % 3600 / 60) + "m";
        Ui.text(c, Text.literal("THIS WEEK"), x + 12, y + 32, 0.8f, Ui.CREAM, false);
        Ui.text(c, Text.literal("New stock in " + left), x + w - 12 - Ui.font().getWidth("New stock in " + left) * 0.7f, y + 33, 0.7f, Ui.MUTED, false);
        int cols = w >= 400 ? 3 : 2, gap = 6, cw = (w - 20 - gap * (cols - 1)) / cols;
        for (int i = 0; i < view.offers().size(); i++) {
            Net.StoreOffer o = view.offers().get(i);
            int cx = x + 10 + (i % cols) * (cw + gap), cy = y + 46 + (i / cols) * 50;
            Ui.well(c, cx, cy, cw, 46);
            c.fill(cx + 1, cy + 1, cx + 3, cy + 45, 0xFF000000 | o.color());
            Ui.text(c, Text.literal(fit(o.title(), cw - 12, 0.85f)), cx + 7, cy + 5, 0.85f, 0xFF000000 | o.color(), false);
            Ui.text(c, Text.literal(o.kind()), cx + 7, cy + 17, 0.65f, Ui.MUTED, false);
        }
        int rows = (view.offers().size() + cols - 1) / cols;
        int cy = y + 46 + rows * 50 + 8, n = Math.max(1, view.crates().size()), ccw = (w - 20 - gap * (n - 1)) / n;
        Ui.text(c, Text.literal("CRATES"), x + 12, cy, 0.8f, Ui.CREAM, false);
        cy += 14;
        for (int i = 0; i < view.crates().size(); i++) {
            Net.StoreCrate k = view.crates().get(i);
            int cx = x + 10 + i * (ccw + gap);
            Ui.well(c, cx, cy, ccw, 62);
            Ui.text(c, Text.literal(fit(k.title(), ccw - 10, 0.85f)), cx + 6, cy + 5, 0.85f, k.gold() ? Ui.GOLD : Ui.CREAM, false);
            c.getMatrices().push();
            c.getMatrices().scale(0.6f, 0.6f, 1);
            c.drawTextWrapped(Ui.font(), Text.literal(k.desc()), (int) ((cx + 6) / 0.6f), (int) ((cy + 17) / 0.6f), (int) ((ccw - 12) / 0.6f), Ui.MUTED);
            c.getMatrices().pop();
        }
        Ui.text(c, Text.literal("Test store: Gold is given by staff for now. Duplicates turn into Gold or Marks."), width / 2f, y + 250, 0.6f, Ui.MUTED, true);
    }

    private static String fit(String s, int w, float scale) {
        var f = Ui.font();
        if (f.getWidth(s) * scale <= w) return s;
        while (s.length() > 1 && f.getWidth(s + "...") * scale > w) s = s.substring(0, s.length() - 1);
        return s + "...";
    }
}
