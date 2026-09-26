package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** The Ferryman: sail to stations you've found (and are strong enough for), or home. */
public final class FerryScreen extends Screen {
    public static Net.FerryView view;
    private static int scroll;
    private int left, top, w, h, rows;

    public FerryScreen() {
        super(Text.literal("Ferry"));
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
        w = Math.min(380, width - 20);
        h = Math.min(320, height - 70);
        left = (width - w) / 2;
        top = Math.max(50, (height - h) / 2 + 12);
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        if (view == null) return;
        int y = top + 26;
        if (view.homeFare() >= 0) {
            AotButton home = addDrawableChild(new AotButton(left + 8, y, w - 16, 24, Ui.heading("⌂ Home"), () -> sail("home")));
            home.sub(Text.literal("Your property or house · " + view.homeFare() + " Marks"));
            home.accent = Ui.GOLD;
            home.active = view.marks() >= view.homeFare();
            y += 30;
        }
        rows = Math.max(1, (top + h - 8 - y) / 28);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, view.stops().size() - rows)));
        for (int i = scroll; i < view.stops().size() && i < scroll + rows; i++) {
            Net.FerryStop s = view.stops().get(i);
            boolean open = s.locked().isEmpty();
            String title = (s.sea() ? "⚓ " : "") + s.name();
            AotButton b = addDrawableChild(new AotButton(left + 8, y, w - 16, 24, Ui.heading(title), () -> sail(s.id())));
            String lv = "Lv " + s.min() + (s.max() > s.min() ? "-" + s.max() : "");
            String dist = s.distance() >= 1000 ? String.format(java.util.Locale.ROOT, "%.1fkm", s.distance() / 1000.0) : s.distance() + "m";
            b.sub(open ? Text.literal(s.sub() + " · " + lv + " · " + dist + " · " + s.fare() + " Marks" + (s.sea() ? " · sea crossing" : ""))
                : Text.literal("🔒 " + s.locked()).withColor(0xFFC07060));
            b.active = open && view.marks() >= s.fare();
            b.accent = s.sea() ? 0xFF4A8AC0 : 0xFF4AA8C0;
            y += 28;
        }
    }

    private void sail(String id) {
        ClientPlayNetworking.send(new Net.FerryGo(id));
        close();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        if (view == null) return false;
        int before = scroll;
        scroll = Math.max(0, Math.min(Math.max(0, view.stops().size() - rows), scroll - (int) Math.signum(vy)));
        if (scroll != before) clearAndInit();
        return true;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.text(c, Ui.title("FERRY"), width / 2f, top - 40, 1.3f, 0xFF4AA8C0, true);
        Ui.panel(c, left, top, w, h);
        if (view == null) return;
        Ui.text(c, Ui.heading("From " + view.here()), left + 10, top + 9, 0.9f, Ui.GOLD, false);
        String m = view.marks() + " Marks";
        Ui.text(c, Text.literal(m), left + w - 10 - Ui.font().getWidth(m), top + 9, 0.9f, Ui.CREAM, false);
        Ui.text(c, Text.literal("Camps, caves and landmarks have no ferry: travel to those yourself."),
            width / 2f, top + h + 4, 0.6f, Ui.MUTED, true);
        if (view.stops().size() > rows) {
            String sc = (scroll + 1) + "-" + Math.min(view.stops().size(), scroll + rows) + " of " + view.stops().size() + " · scroll";
            Ui.text(c, Text.literal(sc), left + w - 10, top + h - 10, 0.55f, Ui.MUTED, false);
        }
    }
}
