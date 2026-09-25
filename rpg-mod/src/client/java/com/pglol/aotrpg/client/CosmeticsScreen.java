package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * Cosmetics: looks only, never power. On the left every category with what you wear in it (the
 * overview); on the right a live preview on your character and the options of the chosen
 * category. Hover an option to preview it before equipping.
 */
public final class CosmeticsScreen extends Screen {
    private static final int ROW = 24;
    private int left, top, w, h, listX, listW, scroll;
    private String slot = "slash";
    private String hovered;

    public CosmeticsScreen() {
        super(Text.literal("Cosmetics"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        clearAndInit();
    }

    /** What you wear in a slot (the free default if nothing is chosen). */
    static String selected(String slot) {
        String s = ClientState.worn.get(slot);
        if (s != null && !s.isEmpty()) return s;
        return CosmeticFx.category(slot).entries().get(0).id();
    }

    private int visible() {
        return Math.max(1, (h - 150) / ROW);
    }

    @Override
    protected void init() {
        w = Math.min(600, width - 20);
        h = Math.min(360, height - 60);
        left = (width - w) / 2;
        top = Math.max(44, (height - h) / 2 + 8);
        int catW = Math.min(170, w / 3);
        listX = left + catW + 8;
        listW = w - catW - 8;
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        int y = top + 8;
        int ch = Math.max(26, Math.min(36, (h - 16) / CosmeticFx.CATEGORIES.size()));
        for (CosmeticFx.Category cat : CosmeticFx.CATEGORIES) {
            CosmeticFx.Entry sel = CosmeticFx.entry(selected(cat.slot()));
            addDrawableChild(new AotButton(left + 4, y, catW - 8, ch - 3, Ui.heading(cat.title()), () -> {
                slot = cat.slot();
                scroll = 0;
                clearAndInit();
            }).sub(Text.literal(sel == null ? "" : sel.title()).withColor(sel == null ? Ui.MUTED : 0xFF000000 | sel.color()))
                .selected(slot.equals(cat.slot())));
            y += ch;
        }
        CosmeticFx.Category cat = CosmeticFx.category(slot);
        scroll = Math.max(0, Math.min(scroll, cat.entries().size() - visible()));
        int ly = top + 140;
        for (int i = scroll; i < cat.entries().size() && i < scroll + visible(); i++) {
            CosmeticFx.Entry e = cat.entries().get(i);
            boolean owned = ClientState.cosmeticsAll || ClientState.cosmetics.contains(e.id()) || i == 0;
            boolean sel = e.id().equals(selected(slot));
            AotButton b = addDrawableChild(new AotButton(listX + listW - 96, ly + 3, 88, 18,
                Ui.heading(sel ? "Equipped" : owned ? "Equip" : "Locked"), () -> ClientPlayNetworking.send(new Net.SelectCosmetic(e.id()))));
            b.selected(sel);
            b.active = owned && !sel;
            ly += ROW;
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        if (mx > listX) {
            scroll -= (int) Math.signum(vAmount);
            clearAndInit();
        }
        return true;
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        // Which option the mouse is over, for the preview.
        hovered = null;
        CosmeticFx.Category cat = CosmeticFx.category(slot);
        int ly = top + 140;
        for (int i = scroll; i < cat.entries().size() && i < scroll + visible(); i++) {
            if (mouseX >= listX && mouseX < listX + listW - 100 && mouseY >= ly && mouseY < ly + ROW - 2) hovered = cat.entries().get(i).id();
            ly += ROW;
        }
        super.render(c, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.text(c, Ui.title("COSMETICS"), width / 2f, top - 40, 1.3f, Ui.GOLD, true);
        Ui.panel(c, left, top, listX - left - 4, h);
        Ui.panel(c, listX, top, listW, h);
        CosmeticFx.Category cat = CosmeticFx.category(slot);
        String showId = hovered != null ? hovered : selected(slot);
        CosmeticFx.Entry show = CosmeticFx.entry(showId);

        // Preview: your character with the effect drawn around it.
        int px = listX + 8, py = top + 8, pw = listW - 16, ph = 124;
        c.fill(px, py, px + pw, py + ph, 0x60000000);
        c.drawBorder(px, py, pw, ph, 0x807A6139);
        int mx = px + 70;
        if (client.player != null) {
            InventoryScreen.drawEntity(c, px + 10, py + 6, px + 130, py + ph - 4, 42, 0.0625f, mx, py + 30, client.player);
        }
        if (show != null) preview(c, slot, show, mx, py + ph - 10, px, py, pw, ph);
        Ui.text(c, Ui.heading(cat.title()), px + 140, py + 8, 1.1f, Ui.GOLD, false);
        Ui.wrapped(c, Text.literal(cat.blurb()), px + 140, py + 22, pw - 150, Ui.MUTED);
        if (show != null) {
            Ui.text(c, Ui.heading(show.title()), px + 140, py + 52, 1f, 0xFF000000 | show.color(), false);
            Ui.wrapped(c, Text.literal(show.desc()), px + 140, py + 64, pw - 150, Ui.CREAM);
            if (hovered != null) Ui.text(c, Text.literal("Previewing"), px + pw - 60, py + ph - 12, 0.6f, Ui.MUTED, false);
        }
        Ui.text(c, Text.literal(ClientState.cosmeticsAll ? "Everything unlocked" : "Looks only, never power"), listX + 10, top + 134 - 10, 0.6f, Ui.MUTED, false);

        int ly = top + 140;
        for (int i = scroll; i < cat.entries().size() && i < scroll + visible(); i++) {
            CosmeticFx.Entry e = cat.entries().get(i);
            boolean owned = ClientState.cosmeticsAll || ClientState.cosmetics.contains(e.id()) || i == 0;
            boolean hov = e.id().equals(hovered);
            c.fill(listX + 8, ly, listX + listW - 8, ly + ROW - 2, hov ? 0x50E0B96A : 0x40000000);
            c.fill(listX + 8, ly, listX + 11, ly + ROW - 2, owned ? 0xFF000000 | e.color() : 0xFF55524A);
            c.fill(listX + 16, ly + 7, listX + 24, ly + 15, 0xFF000000 | e.color());
            c.fill(listX + 24, ly + 7, listX + 30, ly + 15, 0xFF000000 | e.color2());
            Ui.text(c, Ui.heading(e.title()), listX + 36, ly + 3, 0.9f, owned ? Ui.CREAM : Ui.DIM, false);
            Ui.text(c, Text.literal(owned ? e.desc() : "Locked · battle pass, events or shop"), listX + 36, ly + 13, 0.6f, Ui.MUTED, false);
            ly += ROW;
        }
    }

    /** An animated sketch of the effect around the model at (cx, feetY). */
    private void preview(DrawContext c, String slot, CosmeticFx.Entry e, int cx, int feetY, int px, int py, int pw, int ph) {
        float t = (Util.getMeasuringTimeMs() % 100000) / 1000f;
        int col = e.color(), col2 = e.color2();
        boolean rainbow = e.id().contains("rainbow");
        switch (slot) {
            case "body" -> {
                if (e.id().equals("body_none")) return;
                for (int i = 0; i < 14; i++) {
                    float life = (t * 0.6f + i * 0.37f) % 1f;
                    double a = i * 2.4 + t * 0.5;
                    int x = cx + (int) (Math.cos(a) * 22), y = feetY - (int) (life * 90);
                    dot(c, x, y, i % 3 == 0 ? col2 : col, 1 - life, 2);
                }
            }
            case "head" -> {
                int hy = feetY - 92;
                switch (e.id()) {
                    case "head_halo" -> ellipse(c, cx, hy - 6 + (int) (Math.sin(t * 2) * 2), 16, 4, col, 1f);
                    case "head_crown" -> {
                        c.fill(cx - 12, hy - 2, cx + 12, hy + 3, 0xFF000000 | col);
                        for (int k = 0; k < 5; k++) c.fill(cx - 11 + k * 5, hy - 7, cx - 9 + k * 5, hy - 2, 0xFF000000 | col2);
                    }
                    case "head_planets", "head_orbs", "head_embers" -> {
                        boolean pl = e.id().equals("head_planets");
                        int n = e.id().equals("head_embers") ? 10 : 3;
                        for (int k = 0; k < n; k++) {
                            double a = t * (pl ? 1.2 : 2) + k * Math.PI * 2 / n;
                            int x = cx + (int) (Math.cos(a) * (pl ? 32 : 20)), y = hy + (pl ? 30 : 0) + (int) (Math.sin(a) * 5);
                            dot(c, x, y, k == 1 && pl ? col2 : col, 1f, pl ? 4 : 2);
                        }
                    }
                    default -> { }
                }
            }
            case "odm", "horse", "trail" -> {
                // A streak flying across the preview.
                float p = (t * 0.7f) % 1f;
                int y = slot.equals("horse") ? feetY - 4 : feetY - 50;
                int headX = px + 10 + (int) (p * (pw - 20));
                for (int k = 0; k < 26; k++) {
                    int x = headX - k * 4;
                    if (x < px + 4) break;
                    int cc = rainbow ? MathHelper.hsvToRgb((k / 26f + t) % 1f, 0.8f, 1f) : k % 4 == 0 ? col2 : col;
                    dot(c, x, y + (int) (Math.sin(k * 0.9 + t * 6) * (slot.equals("trail") ? 0 : 2)), cc, 1 - k / 26f, 2);
                }
            }
            case "slash" -> {
                float p = (t * 1.3f) % 1f;
                for (int k = 0; k < 18; k++) {
                    float u = k / 17f;
                    if (u > p * 1.5f || u < p * 1.5f - 0.6f) continue;
                    double a = Math.toRadians(-60 + 120 * u);
                    int x = cx + (int) (Math.sin(a) * 34), y = feetY - 50 - (int) (Math.cos(a) * 22) + 14;
                    dot(c, x, y, k % 3 == 0 ? col2 : col, 1 - Math.abs(u - p), 3);
                }
            }
            case "block", "clash" -> {
                float p = (t * (slot.equals("clash") ? 1.1f : 0.9f)) % 1f;
                int bx = cx + 26, by = feetY - 60;
                if (slot.equals("clash")) {
                    c.fill(bx - 1, by - 14, bx + 1, by + 14, 0x80FFFFFF);
                }
                for (int k = 0; k < 16; k++) {
                    double a = k * 0.8 + (slot.equals("clash") ? 0 : -1.2);
                    int r = (int) (p * (slot.equals("clash") ? 34 : 22));
                    dot(c, bx + (int) (Math.cos(a) * r), by + (int) (Math.sin(a) * r * 0.8), k % 3 == 0 ? col2 : col, 1 - p, 2);
                }
            }
            default -> { }
        }
    }

    private static void dot(DrawContext c, int x, int y, int rgb, float alpha, int size) {
        int a = (int) (Math.max(0, Math.min(1, alpha)) * 255);
        if (a < 6) return;
        c.fill(x, y, x + size, y + size, (a << 24) | (rgb & 0xFFFFFF));
    }

    private static void ellipse(DrawContext c, int cx, int cy, int rx, int ry, int rgb, float alpha) {
        for (int k = 0; k < 40; k++) {
            double a = k * Math.PI * 2 / 40;
            dot(c, cx + (int) (Math.cos(a) * rx), cy + (int) (Math.sin(a) * ry), rgb, alpha, 2);
        }
    }
}
