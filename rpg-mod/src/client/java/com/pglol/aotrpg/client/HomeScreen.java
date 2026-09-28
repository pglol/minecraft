package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A house deed or property: buy it (or accept an offer), and for your own home: go in, build its
 * upgrades, furnish it, visit party members' copies, or sell it back. (Staff manage every property
 * from the pause menu's Properties.)
 */
public final class HomeScreen extends Screen {
    private int left, top, w, h;
    private boolean confirmSell;

    public HomeScreen() {
        super(Text.literal("Home"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        clearAndInit();
    }

    private static void act(String a, int home, String arg) {
        ClientPlayNetworking.send(new Net.HomeAction(a, home, arg));
    }

    @Override
    protected void init() {
        w = Math.min(440, width - 20);
        h = Math.min(290, height - 60);
        left = (width - w) / 2;
        top = Math.max(44, (height - h) / 2 + 10);
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        Net.HomeView v = ClientState.home;
        if (v == null) return;
        boolean plot = v.kind().equals("plot");
        int by = top + h - 30;
        if (!v.owned()) {
            boolean taken = plot && !v.owner().isEmpty();
            if (v.offer() >= 0) {
                addDrawableChild(new AotButton(left + 10, by, 180, 22, Ui.heading("Accept offer · " + v.offer() + " M"), () -> act("acceptoffer", v.home(), "")))
                    .selected(true);
            }
            AotButton buy = addDrawableChild(new AotButton(left + w - 170, by, 160, 22, Ui.heading(taken ? "Owned" : "Buy · " + v.price() + " M"),
                () -> act("buy", v.home(), "")));
            buy.active = !taken && (plot || v.homes() < v.maxHomes());
            int y = top + 120;
            for (String host : v.visits()) {
                addDrawableChild(new AotButton(left + 10, y, 200, 18, Text.literal("Visit " + host + "'s home"), () -> {
                    act("visit", v.home(), host);
                    close();
                }));
                y += 22;
            }
            return;
        }
        // Back to all your homes, and selling, sit above the panel, out of the way.
        addDrawableChild(new AotButton(left, top - 22, 90, 20, Text.literal("‹ All homes"),
            () -> ClientPlayNetworking.send(new Net.HomeAction("list", -1, ""))));
        AotButton sell = addDrawableChild(new AotButton(left + w - 134, top - 22, 110, 20,
            Text.literal(confirmSell ? "Confirm sale" : "Sell " + (plot ? "property" : "home")), () -> {
                if (confirmSell) {
                    act("sell", v.home(), "");
                    close();
                } else {
                    confirmSell = true;
                    clearAndInit();
                }
            }));
        sell.accent = Ui.RED;
        sell.selected(confirmSell);
        // One row of actions along the bottom, all the same size.
        List<Object[]> row = new ArrayList<>();
        if (!plot) {
            boolean atHome = client.world != null && client.world.getRegistryKey().getValue().toString().equals("aot_rpg:homes");
            row.add(new Object[] {atHome ? "Step outside" : "Go inside", (Runnable) () -> {
                act(atHome ? "leave" : "enter", v.home(), "");
                close();
            }});
        }
        row.add(new Object[] {"Furniture", (Runnable) () -> ClientPlayNetworking.send(new Net.FurnitureAction("open", "", 0))});
        row.add(new Object[] {"Estate & Pets", (Runnable) () -> ClientPlayNetworking.send(new Net.EstateAction("open", ""))});
        boolean stable = false;
        for (Net.HomeUpgrade u : v.upgrades()) if (u.id().equals("STABLE") && u.owned()) stable = true;
        if (stable) row.add(new Object[] {"Stables", (Runnable) () -> act("stables", v.home(), "")});
        int n = row.size(), gap = 6, bw = (w - 20 - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            AotButton b = addDrawableChild(new AotButton(left + 10 + i * (bw + gap), by, bw, 22, Ui.heading((String) row.get(i)[0]), (Runnable) row.get(i)[1]));
            if (i == 0) b.selected(true);
        }
        // Upgrades not yet built get a Build button; built ones just say so.
        int y = top + (plot ? 50 : 52), step = plot ? 30 : 24;
        for (Net.HomeUpgrade u : v.upgrades()) {
            if (!u.owned()) {
                boolean locked = u.desc().startsWith("Needs the Cellar");
                AotButton b = addDrawableChild(new AotButton(left + w - 120, y + 1, 110, 17,
                    Text.literal(locked ? "Needs Cellar" : "Build · " + String.format(Locale.ROOT, "%,d", u.price()) + " M"),
                    () -> act(plot ? (u.id().equals("STABLE") ? "stable" : "build") : "upgrade", v.home(), u.id())));
                b.active = !locked && ClientState.marks >= u.price();
            }
            y += step;
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Net.HomeView v = ClientState.home;
        if (v == null) return;
        boolean plot = v.kind().equals("plot");
        Ui.text(c, Ui.title(v.owned() ? (plot ? "YOUR PROPERTY" : "YOUR HOME") : "DEED"), width / 2f, top - 44, 1.3f, Ui.GOLD, true);
        Ui.panel(c, left, top, w, h);
        Ui.crest(c, left + w - 70, top + 6, 60, 0.12f);
        Ui.text(c, Ui.heading(v.town()), left + 12, top + 10, 1.1f, Ui.CREAM, false);
        Ui.text(c, Text.literal(v.size()), left + 12, top + 24, 0.8f, Ui.GOLD, false);
        if (!v.owned()) {
            Ui.text(c, Text.literal(String.format(Locale.ROOT, "Price %,d Marks  ·  You have %,d", v.price(), ClientState.marks)), left + 12, top + 36, 0.8f, Ui.GOLD, false);
            String how = plot ? (v.owner().isEmpty() ? "Unique land. Once bought, a house is built right here and only you can build on the plot."
                    : "Owned by " + v.owner() + ".")
                : "Every buyer gets their own private copy of this house behind its door, with a cellar to dig and fit out. "
                    + "Homes owned: " + v.homes() + " / " + v.maxHomes() + ".";
            Ui.wrapped(c, Text.literal(how), left + 12, top + 54, w - 24, Ui.CREAM);
            if (v.offer() >= 0) Ui.text(c, Text.literal("Private offer for you: " + v.offer() + " Marks"), left + 12, top + 100, 0.85f, 0xFF5BD35B, false);
            return;
        }
        Ui.divider(c, left + 10, top + 40, w - 20);
        int y = top + (plot ? 50 : 52), step = plot ? 30 : 24;
        for (Net.HomeUpgrade u : v.upgrades()) {
            c.fill(left + 8, y - 1, left + w - 8, y + step - 3, u.owned() ? 0x40302418 : 0x30000000);
            c.fill(left + 8, y - 1, left + 10, y + step - 3, u.owned() ? Ui.GOLD : 0xFF4A4238);
            Ui.text(c, Ui.heading(u.title()), left + 16, y + 1, 0.8f, u.owned() ? Ui.GOLD : Ui.CREAM, false);
            String d = u.desc().replace("Needs the Cellar. ", "");
            int max = w - 150;
            while (d.length() > 4 && Ui.font().getWidth(d) * 0.55f > max) d = d.substring(0, d.length() - 4) + "...";
            Ui.text(c, Text.literal(d), left + 16, y + 11, 0.55f, 0xFFCFC3A6, false);
            if (u.owned()) Ui.text(c, Ui.heading("✔ Built"), left + w - 65, y + 5, 0.8f, Ui.GOLD, true);
            y += step;
        }
    }
}
