package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.List;

/** Your homes and properties as cards: pick one to manage it. */
public final class HomesScreen extends Screen {
    public static Net.HomeList list;
    private static final int CARD_W = 150, CARD_H = 96, GAP = 10;

    public HomesScreen() {
        super(Text.literal("Homes"));
    }

    public static void on(Net.HomeList v) {
        list = v;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof HomesScreen s) s.clearAndInit();
        else mc.setScreen(new HomesScreen());
    }

    private List<String> cards() {
        return list == null ? List.of() : list.cards();
    }

    private int cols() {
        return Math.max(1, Math.min(cards().size(), (width - 40 + GAP) / (CARD_W + GAP)));
    }

    private int gridX() {
        int cols = cols();
        return width / 2 - (cols * CARD_W + (cols - 1) * GAP) / 2;
    }

    private int gridY() {
        int rows = Math.max(1, (cards().size() + cols() - 1) / cols());
        return Math.max(50, height / 2 - (rows * CARD_H + (rows - 1) * GAP) / 2 + 8);
    }

    @Override
    protected void init() {
        addDrawableChild(new AotButton(width - 30, 10, 20, 20, Text.literal("✕"), this::close));
        List<String> cards = cards();
        int cols = cols(), x0 = gridX(), y0 = gridY();
        for (int i = 0; i < cards.size(); i++) {
            String[] a = cards.get(i).split("\\|", -1);
            int home = Integer.parseInt(a[0]);
            int cx = x0 + (i % cols) * (CARD_W + GAP), cy = y0 + (i / cols) * (CARD_H + GAP);
            addDrawableChild(new AotButton(cx + 8, cy + CARD_H - 26, CARD_W - 16, 18, Ui.heading("Manage"),
                () -> ClientPlayNetworking.send(new Net.HomeAction("view", home, "")))).selected(a[6].equals("1"));
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        List<String> cards = cards();
        int homes = 0;
        for (String k : cards) if (k.split("\\|")[1].equals("home")) homes++;
        Ui.text(c, Ui.title("YOUR HOMES"), width / 2f, gridY() - 34, 1.4f, Ui.GOLD, true);
        if (list != null) Ui.text(c, Text.literal("Town homes " + homes + " / " + list.maxHomes()), width / 2f, gridY() - 16, 0.8f, Ui.CREAM, true);
        if (cards.isEmpty()) {
            Ui.text(c, Ui.heading("No homes yet"), width / 2f, height / 2f, 1.1f, Ui.CREAM, true);
            return;
        }
        int cols = cols(), x0 = gridX(), y0 = gridY();
        for (int i = 0; i < cards.size(); i++) {
            String[] a = cards.get(i).split("\\|", -1);
            boolean plot = a[1].equals("plot"), here = a[6].equals("1");
            int cx = x0 + (i % cols) * (CARD_W + GAP), cy = y0 + (i / cols) * (CARD_H + GAP);
            Ui.panel(c, cx, cy, CARD_W, CARD_H);
            int accent = plot ? 0xFF7FB24A : Ui.GOLD;
            c.fill(cx + 3, cy + 3, cx + CARD_W - 3, cy + 5, accent);
            Ui.item(c, new ItemStack(plot ? Items.GRASS_BLOCK : Items.OAK_DOOR), cx + 8, cy + 12, 1.5f);
            String title = a[2];
            while (title.length() > 4 && Ui.font().getWidth(title) * 0.9f > CARD_W - 46) title = title.substring(0, title.length() - 2);
            Ui.text(c, Ui.heading(title), cx + 36, cy + 12, 0.9f, Ui.CREAM, false);
            Ui.text(c, Text.literal(plot ? "Property" : "Town home"), cx + 36, cy + 24, 0.65f, accent, false);
            Ui.text(c, Text.literal(a[3]), cx + 8, cy + 40, 0.65f, Ui.CREAM, false);
            // Upgrades built.
            int built = Integer.parseInt(a[4]), total = Math.max(1, Integer.parseInt(a[5]));
            Ui.bar(c, cx + 8, cy + 54, CARD_W - 16, 4, built / (float) total, accent);
            Ui.text(c, Text.literal((plot ? "Buildings " : "Upgrades ") + built + " / " + total), cx + 8, cy + 61, 0.6f, Ui.GOLD, false);
            if (here) Ui.text(c, Text.literal("You're here"), cx + CARD_W - 8 - Ui.font().getWidth("You're here") * 0.6f, cy + 61, 0.6f, 0xFF5BD35B, false);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
