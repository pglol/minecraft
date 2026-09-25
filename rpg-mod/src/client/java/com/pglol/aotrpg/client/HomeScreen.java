package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * A house deed or property: buy it (or accept an offer), and for your own home: go in, buy yard
 * upgrades, visit party members' copies, or sell it back.
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
        w = Math.min(420, width - 20);
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
        if (!plot) {
            addDrawableChild(new AotButton(left + 10, by, 120, 22, Ui.heading("Go inside"), () -> {
                act("enter", v.home(), "");
                close();
            }));
            int y = top + 70;
            for (Net.HomeUpgrade u : v.upgrades()) {
                AotButton b = addDrawableChild(new AotButton(left + w - 120, y + 2, 110, 18,
                    Text.literal(u.owned() ? "Built" : "Build · " + u.price() + " M"), () -> act("upgrade", v.home(), u.id())));
                b.active = !u.owned();
                y += 28;
            }
        }
        AotButton sell = addDrawableChild(new AotButton(left + w - 170, by, 160, 22,
            Text.literal(confirmSell ? "Click again to sell (half back)" : "Sell " + (plot ? "property" : "home")), () -> {
                if (confirmSell) {
                    act("sell", v.home(), "");
                    close();
                } else {
                    confirmSell = true;
                    clearAndInit();
                }
            }));
        sell.accent = Ui.RED;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Net.HomeView v = ClientState.home;
        if (v == null) return;
        boolean plot = v.kind().equals("plot");
        Ui.text(c, Ui.title(v.owned() ? (plot ? "YOUR PROPERTY" : "YOUR HOME") : "DEED"), width / 2f, top - 40, 1.3f, Ui.GOLD, true);
        Ui.panel(c, left, top, w, h);
        Ui.crest(c, left + w - 70, top + 6, 60, 0.12f);
        Ui.text(c, Ui.heading(v.town()), left + 12, top + 10, 1.1f, Ui.CREAM, false);
        Ui.text(c, Text.literal(v.size()), left + 12, top + 24, 0.8f, Ui.MUTED, false);
        Ui.text(c, Text.literal(String.format(Locale.ROOT, "Price %,d Marks  ·  You have %,d", v.price(), ClientState.marks)), left + 12, top + 36, 0.8f, Ui.GOLD, false);
        if (!v.owned()) {
            String how = plot ? (v.owner().isEmpty() ? "Unique land. Once bought, a house is built right here and only you can build on the plot."
                    : "Owned by " + v.owner() + ".")
                : "Every buyer gets their own private copy of this house behind its door, with a fenced yard for upgrades. "
                    + "The house in town stays as it is. Homes owned: " + v.homes() + " / " + v.maxHomes() + ".";
            Ui.wrapped(c, Text.literal(how), left + 12, top + 54, w - 24, Ui.CREAM);
            if (v.offer() >= 0) Ui.text(c, Text.literal("Private offer for you: " + v.offer() + " Marks"), left + 12, top + 100, 0.85f, 0xFF5BD35B, false);
            return;
        }
        if (plot) {
            Ui.wrapped(c, Text.literal("Your land. You can build and break anything inside the plot."), left + 12, top + 54, w - 24, Ui.CREAM);
            return;
        }
        Ui.text(c, Ui.heading("Yard upgrades"), left + 12, top + 54, 0.95f, Ui.GOLD, false);
        int y = top + 70;
        for (Net.HomeUpgrade u : v.upgrades()) {
            Ui.text(c, Ui.heading(u.title()), left + 12, y + 2, 0.9f, u.owned() ? Ui.GOLD : Ui.CREAM, false);
            Ui.text(c, Text.literal(u.desc()), left + 12, y + 13, 0.6f, Ui.MUTED, false);
            y += 28;
        }
    }
}
