package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The furniture store and your crate. Bought pieces are shipped to your home; standing on your
 * property you pick one from the crate and set it down where you look.
 */
public final class FurnitureScreen extends Screen {
    private static final int ROW = 26;
    private int left, top, w, h, scroll;
    private boolean crate;
    private String category = "All";

    public FurnitureScreen() {
        super(Text.literal("Furniture"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        clearAndInit();
    }

    private List<Net.FurniturePiece> rows() {
        List<Net.FurniturePiece> out = new ArrayList<>();
        Net.FurnitureView v = ClientState.furniture;
        if (v == null) return out;
        for (Net.FurniturePiece f : v.pieces()) {
            if (crate && f.owned() <= 0) continue;
            if (!crate && !category.equals("All") && !f.category().equals(category)) continue;
            out.add(f);
        }
        return out;
    }

    private int visible() {
        return Math.max(1, (h - 70) / ROW);
    }

    @Override
    protected void init() {
        w = Math.min(520, width - 20);
        h = Math.min(320, height - 70);
        left = (width - w) / 2;
        top = Math.max(50, (height - h) / 2);
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        addDrawableChild(new AotButton(left, top - 22, 110, 20, Ui.heading("Store"), () -> {
            crate = false;
            scroll = 0;
            clearAndInit();
        })).selected(!crate);
        addDrawableChild(new AotButton(left + 114, top - 22, 110, 20, Ui.heading("Your crate"), () -> {
            crate = true;
            scroll = 0;
            clearAndInit();
        })).selected(crate);
        Net.FurnitureView v = ClientState.furniture;
        if (v == null) return;
        if (!crate) {
            Set<String> cats = new LinkedHashSet<>();
            cats.add("All");
            for (Net.FurniturePiece f : v.pieces()) cats.add(f.category());
            int cx = left + 8, cw = Math.max(40, (w - 16) / cats.size() - 3);
            for (String cat : cats) {
                addDrawableChild(new AotButton(cx, top + 8, cw, 16, Text.literal(cat), () -> {
                    category = cat;
                    scroll = 0;
                    clearAndInit();
                })).selected(category.equals(cat));
                cx += cw + 3;
            }
        }
        List<Net.FurniturePiece> rows = rows();
        scroll = Math.max(0, Math.min(scroll, rows.size() - visible()));
        int y = top + 32;
        for (int i = scroll; i < rows.size() && i < scroll + visible(); i++) {
            Net.FurniturePiece f = rows.get(i);
            if (crate) {
                AotButton place = addDrawableChild(new AotButton(left + w - 110, y + 3, 100, 18, Ui.heading("Place"), () -> {
                    ClientState.placing = f.id();
                    close();
                }));
                place.active = v.onProperty();
            } else {
                AotButton buy = addDrawableChild(new AotButton(left + w - 110, y + 3, 100, 18,
                    Text.literal(String.format(Locale.ROOT, "Buy · %,d M", f.price())), () -> ClientPlayNetworking.send(new Net.FurnitureAction("buy", f.id(), 0))));
                buy.active = ClientState.marks >= f.price();
            }
            y += ROW;
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        scroll -= (int) Math.signum(vAmount);
        clearAndInit();
        return true;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.text(c, Ui.title("FURNITURE"), width / 2f, top - 44, 1.4f, Ui.GOLD, true);
        Ui.panel(c, left, top, w, h);
        Net.FurnitureView v = ClientState.furniture;
        if (v == null) return;
        List<Net.FurniturePiece> rows = rows();
        if (crate) {
            Ui.text(c, Text.literal(v.onProperty() ? "Pick a piece, then right-click a floor. It faces you. Break a placed piece to pack it up again."
                : "Go to your home or property to place furniture."), left + 10, top + 12, 0.7f, v.onProperty() ? Ui.CREAM : Ui.MUTED, false);
            if (rows.isEmpty()) Ui.text(c, Text.literal("Your crate is empty: buy furniture in the Store."), left + w / 2f, top + 80, 0.8f, Ui.DIM, true);
        }
        int y = top + 32;
        for (int i = scroll; i < rows.size() && i < scroll + visible(); i++) {
            Net.FurniturePiece f = rows.get(i);
            c.fill(left + 6, y, left + w - 6, y + ROW - 2, 0x28000000);
            Ui.item(c, BattlePassScreen.icon(f.icon()), left + 10, y + 3, 1.2f);
            Ui.text(c, Ui.heading(f.title()), left + 36, y + 3, 0.9f, Ui.CREAM, false);
            int wide = f.x1() - f.x0() + 1, deep = f.z1() - f.z0() + 1, tall = f.y1() + 1;
            String sub = f.category() + "  ·  " + wide + " x " + deep + (tall > 1 ? " x " + tall : "") + (f.owned() > 0 ? "  ·  " + f.owned() + " in your crate" : "");
            Ui.text(c, Text.literal(sub), left + 36, y + 14, 0.6f, f.owned() > 0 ? 0xFF8FD18F : Ui.MUTED, false);
            y += ROW;
        }
        Ui.text(c, Text.literal(String.format(Locale.ROOT, "%,d Marks", ClientState.marks)), left + 10, top + h - 14, 0.7f, Ui.GOLD, false);
    }
}
