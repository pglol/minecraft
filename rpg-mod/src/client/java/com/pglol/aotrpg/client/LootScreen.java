package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Searching a container on a run: a see-through overlay over the world, the container's grid on
 * the left and your pockets on the right. Unsearched slots sit dark until the rummaging reaches
 * them; the one being searched fills as you go. Click a find to pocket it, click your own things
 * to leave them in it.
 */
public final class LootScreen extends Screen {
    private static final int CELL = 22, GAP = 2, COLS = 9;
    private static Net.LootView view;
    private static long viewAt;
    private static final Map<Integer, ItemStack> items = new HashMap<>();
    private static final Set<Integer> hidden = new HashSet<>();
    /** When each slot turned up (for a short flash). */
    private static final Map<Integer, Long> revealedAt = new HashMap<>();
    private int lx, ly, rx, ry, rows;

    public LootScreen(Net.LootView v) {
        super(Text.literal(v.title()));
        revealedAt.clear();
        take(v);
    }

    private static void take(Net.LootView v) {
        long now = Util.getMeasuringTimeMs();
        if (view != null && view.pos() == v.pos()) {
            for (Net.BagEntry e : v.items()) if (hidden.contains(e.slot())) revealedAt.put(e.slot(), now);
        }
        view = v;
        viewAt = now;
        items.clear();
        for (Net.BagEntry e : v.items()) items.put(e.slot(), e.stack());
        hidden.clear();
        hidden.addAll(v.hidden());
    }

    public static void update(Net.LootView v) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (v.close()) {
            if (mc.currentScreen instanceof LootScreen) mc.setScreen(null);
            view = null;
            return;
        }
        if (v.open() || !(mc.currentScreen instanceof LootScreen)) {
            if (v.open()) mc.setScreen(new LootScreen(v));
            return;
        }
        take(v);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        ClientPlayNetworking.send(new Net.LootAction("close", 0));
    }

    @Override
    protected void init() {
        rows = Math.max(1, (view == null ? 27 : view.size() + COLS - 1) / COLS);
        int pw = COLS * (CELL + GAP) - GAP;
        int gap = 36;
        lx = width / 2 - gap / 2 - pw;
        rx = width / 2 + gap / 2;
        int ph = Math.max(rows, 5) * (CELL + GAP);
        ly = ry = Math.max(40, height / 2 - ph / 2);
        AotButton all = addDrawableChild(new AotButton(lx + pw - 70, ly + rows * (CELL + GAP) + 6, 70, 16, Text.literal("Take all"),
            () -> ClientPlayNetworking.send(new Net.LootAction("take_all", 0))));
        all.textScale = 0.75f;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        // The world stays in view: only a soft darkening toward the middle.
        c.fillGradient(0, 0, width, height, 0x50000000, 0x78000000);
    }

    /** A panel: clear dark glass with a hairline edge. */
    private static void glass(DrawContext c, int x, int y, int w, int h) {
        c.fill(x, y, x + w, y + h, 0x8C0A0C0E);
        c.drawBorder(x, y, w, h, 0x38FFFFFF);
        c.fill(x, y, x + w, y + 1, 0x60FFFFFF);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        super.render(c, mouseX, mouseY, delta);
        if (view == null || client == null || client.player == null) return;
        long now = Util.getMeasuringTimeMs();
        int pw = COLS * (CELL + GAP) - GAP;

        // The container.
        int total = items.size() + hidden.size();
        Ui.text(c, Ui.heading(view.title().toUpperCase()), lx, ly - 22, 1f, 0xFFE8E4DA, false);
        String count = hidden.isEmpty() ? total + (total == 1 ? " item" : " items") : "Searching  " + items.size() + " / " + total;
        Ui.text(c, Text.literal(count), lx + pw - textRenderer.getWidth(count) * 0.75f, ly - 18, 0.75f, hidden.isEmpty() ? 0xFFA8A49A : 0xFFE0B96A, false);
        glass(c, lx - 4, ly - 4, pw + 8, rows * (CELL + GAP) + 6);
        ItemStack hover = null;
        for (int i = 0; i < rows * COLS; i++) {
            int x = lx + (i % COLS) * (CELL + GAP), y = ly + (i / COLS) * (CELL + GAP);
            if (view.size() > 0 && i >= view.size()) break;
            boolean hov = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
            ItemStack s = items.get(i);
            c.fill(x, y, x + CELL, y + CELL, 0x50000000);
            c.drawBorder(x, y, CELL, CELL, hov && s != null ? 0xA0FFFFFF : 0x22FFFFFF);
            if (s != null) {
                int q = BagScreen.quality(s);
                if (q > 0) c.fillGradient(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0x00000000, (Ui.rarityTone(q) & 0xFFFFFF) | 0x40000000);
                c.fill(x + 1, y + CELL - 2, x + CELL - 1, y + CELL - 1, q > 0 ? Ui.rarityTone(q) : 0x30FFFFFF);
                c.drawItem(s, x + 3, y + 3);
                c.drawItemInSlot(textRenderer, s, x + 3, y + 3);
                Long at = revealedAt.get(i);
                if (at != null && now - at < 350) {
                    int a = (int) (160 * (1 - (now - at) / 350.0));
                    c.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, a << 24 | 0xFFFFFF);
                }
                if (hov) hover = s;
            } else if (hidden.contains(i)) {
                if (i == view.searching() && view.searchMs() > 0) {
                    // Being searched: a sweep filling the cell, and a ticking scan line.
                    float f = Math.min(1, (now - viewAt) / (float) view.searchMs());
                    c.fill(x + 1, y + CELL - 1 - (int) ((CELL - 2) * f), x + CELL - 1, y + CELL - 1, 0x40E0B96A);
                    int sy = y + 1 + (int) ((now / 12) % (CELL - 2));
                    c.fill(x + 1, sy, x + CELL - 1, sy + 1, 0x90E0B96A);
                    c.drawBorder(x, y, CELL, CELL, 0xC0E0B96A);
                } else {
                    float pulse = (float) (0.5 + 0.5 * Math.sin(now / 300.0 + i));
                    Ui.text(c, Text.literal("?"), x + CELL / 2f, y + CELL / 2f - 4, 1f, ((int) (60 + 60 * pulse) << 24) | 0xE8E4DA, true);
                }
            }
        }

        // Your pockets: the backpack, then the loadout bar under it.
        Ui.text(c, Ui.heading("POCKETS"), rx, ry - 22, 1f, 0xFFE8E4DA, false);
        var inv = client.player.getInventory();
        int pr = 4;
        glass(c, rx - 4, ry - 4, pw + 8, pr * (CELL + GAP) + 10);
        for (int k = 0; k < 36; k++) {
            int slot = k < 27 ? k + 9 : k - 27;
            int row = k < 27 ? k / COLS : 3;
            int x = rx + (k % COLS) * (CELL + GAP), y = ry + row * (CELL + GAP) + (k >= 27 ? 4 : 0);
            boolean hov = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
            ItemStack s = inv.main.get(slot);
            c.fill(x, y, x + CELL, y + CELL, k >= 27 ? 0x60101410 : 0x50000000);
            c.drawBorder(x, y, CELL, CELL, hov && !s.isEmpty() ? 0xA0FFFFFF : k >= 27 ? 0x40E0B96A : 0x22FFFFFF);
            if (!s.isEmpty()) {
                int q = BagScreen.quality(s);
                if (q > 0) c.fill(x + 1, y + CELL - 2, x + CELL - 1, y + CELL - 1, Ui.rarityTone(q));
                c.drawItem(s, x + 3, y + 3);
                c.drawItemInSlot(textRenderer, s, x + 3, y + 3);
                if (hov) hover = s;
            }
        }
        if (hover != null) c.drawItemTooltip(textRenderer, hover, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (view == null) return false;
        for (int i = 0; i < rows * COLS; i++) {
            int x = lx + (i % COLS) * (CELL + GAP), y = ly + (i / COLS) * (CELL + GAP);
            if (mx >= x && mx < x + CELL && my >= y && my < y + CELL) {
                if (items.containsKey(i)) ClientPlayNetworking.send(new Net.LootAction(hasShiftDown() ? "take_all" : "take", i));
                return true;
            }
        }
        for (int k = 0; k < 36; k++) {
            int slot = k < 27 ? k + 9 : k - 27;
            int row = k < 27 ? k / COLS : 3;
            int x = rx + (k % COLS) * (CELL + GAP), y = ry + row * (CELL + GAP) + (k >= 27 ? 4 : 0);
            if (mx >= x && mx < x + CELL && my >= y && my < y + CELL) {
                if (client != null && client.player != null && !client.player.getInventory().main.get(slot).isEmpty()) {
                    ClientPlayNetworking.send(new Net.LootAction("put", slot));
                }
                return true;
            }
        }
        return false;
    }
}
