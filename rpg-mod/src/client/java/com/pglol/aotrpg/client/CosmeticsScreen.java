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
    private static final int CARD_H = 56, GAP = 6;
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

    private int gridTop() {
        return top + 132;
    }

    private int cols() {
        return Math.max(2, Math.min(4, (listW - 16 + GAP) / (110 + GAP)));
    }

    private int cardW() {
        return (listW - 16 - GAP * (cols() - 1)) / cols();
    }

    private int visibleRows() {
        return Math.max(1, (top + h - 8 - gridTop()) / (CARD_H + GAP));
    }

    private static boolean owned(CosmeticFx.Entry e, int index) {
        return ClientState.cosmeticsAll || ClientState.cosmetics.contains(e.id()) || index == 0;
    }

    @Override
    protected void init() {
        w = Math.min(640, width - 20);
        h = Math.min(380, height - 60);
        left = (width - w) / 2;
        top = Math.max(44, (height - h) / 2 + 8);
        int catW = Math.min(160, w / 4);
        listX = left + catW + 8;
        listW = w - catW - 8;
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        int y = top + 8;
        int ch = Math.max(24, Math.min(34, (h - 16) / CosmeticFx.CATEGORIES.size()));
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
        int rows = (cat.entries().size() + cols() - 1) / cols();
        scroll = Math.max(0, Math.min(scroll, rows - visibleRows()));
    }

    /** The card under the mouse, as an index into the category, or -1. */
    private int cardAt(double mx, double my) {
        CosmeticFx.Category cat = CosmeticFx.category(slot);
        int cols = cols(), cw = cardW();
        for (int i = scroll * cols; i < cat.entries().size() && i < (scroll + visibleRows()) * cols; i++) {
            int r = i / cols - scroll, col = i % cols;
            int x = listX + 8 + col * (cw + GAP), y = gridTop() + r * (CARD_H + GAP);
            if (mx >= x && mx < x + cw && my >= y && my < y + CARD_H) return i;
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int i = cardAt(mx, my);
        if (i >= 0 && button == 0) {
            CosmeticFx.Entry e = CosmeticFx.category(slot).entries().get(i);
            if (owned(e, i) && !e.id().equals(selected(slot))) {
                ClientPlayNetworking.send(new Net.SelectCosmetic(e.id()));
                if (client != null) client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
                    net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.2f));
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
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
        int i = cardAt(mouseX, mouseY);
        hovered = i >= 0 ? CosmeticFx.category(slot).entries().get(i).id() : null;
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
        float t = (Util.getMeasuringTimeMs() % 100000) / 1000f;

        // Preview: your character with the effect drawn around it.
        int px = listX + 8, py = top + 8, pw = listW - 16, ph = 112;
        c.fill(px, py, px + pw, py + ph, 0x60000000);
        c.drawBorder(px, py, pw, ph, 0x807A6139);
        int mx = px + 70;
        if (client.player != null) {
            InventoryScreen.drawEntity(c, px + 10, py + 4, px + 130, py + ph - 4, 38, 0.0625f, mx, py + 30, client.player);
        }
        if (show != null) preview(c, slot, show, mx, py + ph - 10, px, py, pw, ph);
        Ui.text(c, Ui.heading(cat.title()), px + 140, py + 8, 1.1f, Ui.GOLD, false);
        Ui.wrapped(c, Text.literal(cat.blurb()), px + 140, py + 22, pw - 150, Ui.MUTED);
        if (show != null) {
            Ui.text(c, Ui.heading(show.title()), px + 140, py + 48, 1f, 0xFF000000 | show.color(), false);
            Ui.text(c, Text.literal(CosmeticFx.TIERS[show.tier()].toUpperCase()), px + 140, py + 60, 0.6f, CosmeticFx.TIER_COLORS[show.tier()], false);
            Ui.wrapped(c, Text.literal(show.desc()), px + 140, py + 70, pw - 150, Ui.CREAM);
            if (hovered != null) Ui.text(c, Text.literal("Previewing"), px + pw - 60, py + ph - 12, 0.6f, Ui.MUTED, false);
        }
        int have = 0;
        for (int k = 0; k < cat.entries().size(); k++) if (owned(cat.entries().get(k), k)) have++;
        Ui.text(c, Text.literal((ClientState.cosmeticsAll ? "Everything unlocked" : have + " / " + cat.entries().size() + " owned")
            + "  ·  looks only, never power  ·  click a card to equip"), listX + 10, top + 122, 0.6f, Ui.MUTED, false);

        // The cards.
        int cols = cols(), cw = cardW();
        for (int i = scroll * cols; i < cat.entries().size() && i < (scroll + visibleRows()) * cols; i++) {
            CosmeticFx.Entry e = cat.entries().get(i);
            int r = i / cols - scroll, col = i % cols;
            int x = listX + 8 + col * (cw + GAP), y = gridTop() + r * (CARD_H + GAP);
            card(c, e, owned(e, i), e.id().equals(selected(slot)), e.id().equals(hovered), x, y, cw, t, i);
        }
        int rows = (cat.entries().size() + cols - 1) / cols;
        if (rows > visibleRows()) {
            // A slim scroll bar down the right edge.
            int gy = gridTop(), gh = visibleRows() * (CARD_H + GAP) - GAP;
            c.fill(listX + listW - 5, gy, listX + listW - 3, gy + gh, 0x40000000);
            int bh = Math.max(12, gh * visibleRows() / rows), by = gy + (gh - bh) * scroll / Math.max(1, rows - visibleRows());
            c.fill(listX + listW - 5, by, listX + listW - 3, by + bh, Ui.GOLD);
        }
    }

    /** One cosmetic card: a swatch banner in its colours, its name, its rarity frame, and whether you have it. */
    private void card(DrawContext c, CosmeticFx.Entry e, boolean owned, boolean sel, boolean hov, int x, int y, int cw, float t, int i) {
        int tierCol = CosmeticFx.TIER_COLORS[e.tier()];
        c.fill(x, y, x + cw, y + CARD_H, hov ? 0xE0252A22 : 0xD0161914);
        // Banner: the two colours in a slanted gradient, with a sweep of light on rare and better.
        int bh = 22;
        for (int k = 0; k < cw - 2; k++) {
            float u = k / (float) (cw - 2);
            int col = lerp(e.color(), e.color2(), (float) (0.5 + 0.5 * Math.sin(u * 3.2 + t * (e.tier() >= 2 ? 1.5 : 0.4))));
            if (e.id().contains("rainbow")) col = MathHelper.hsvToRgb((u + t * 0.2f) % 1f, 0.7f, 1f);
            int a = owned ? 0xFF : 0x70;
            c.fill(x + 1 + k, y + 1, x + 2 + k, y + 1 + bh, (a << 24) | (col & 0xFFFFFF));
        }
        if (e.tier() >= 2 && owned) {
            float sweep = (t * 0.6f + i * 0.13f) % 1.6f - 0.3f;
            int sx = x + (int) (sweep * cw);
            for (int k = -6; k <= 6; k++) {
                int xx = sx + k;
                if (xx <= x || xx >= x + cw - 1) continue;
                int a = (int) (90 * (1 - Math.abs(k) / 7f));
                c.fill(xx, y + 1, xx + 1, y + 1 + bh, (a << 24) | 0xFFFFFF);
            }
        }
        if (e.tier() == 3 && owned) {
            // Legendary: a few glints drift over the banner.
            for (int k = 0; k < 4; k++) {
                float life = (t * 0.8f + k * 0.29f) % 1f;
                int gx = x + 6 + (int) (((k * 37 + (int) (t * 3)) % 97) / 97f * (cw - 12)), gy = y + bh - (int) (life * bh);
                int a = (int) (255 * (1 - life));
                c.fill(gx, gy, gx + 1, gy + 1, (a << 24) | 0xFFFFFF);
                c.fill(gx - 1, gy, gx + 2, gy + 1, ((a / 3) << 24) | 0xFFFFFF);
            }
        }
        // Frame in the rarity colour (dim when locked, gold when worn).
        int frame = sel ? Ui.GOLD : owned ? tierCol : 0xFF3A3830;
        c.drawBorder(x, y, cw, CARD_H, frame);
        if (sel || hov) c.drawBorder(x - 1, y - 1, cw + 2, CARD_H + 2, sel ? 0x80E0B96A : 0x40FFFFFF);
        Ui.text(c, Ui.heading(fit(e.title(), cw - 10, 0.85f)), x + 5, y + bh + 5, 0.85f, owned ? Ui.CREAM : Ui.DIM, false);
        Ui.text(c, Text.literal(CosmeticFx.TIERS[e.tier()]), x + 5, y + bh + 17, 0.55f, owned ? tierCol : Ui.DIM, false);
        String state = sel ? "EQUIPPED" : owned ? "" : "LOCKED";
        if (!state.isEmpty()) {
            float sw = Ui.font().getWidth(state) * 0.55f;
            Ui.text(c, Text.literal(state), x + cw - 5 - sw, y + bh + 17, 0.55f, sel ? Ui.GOLD : Ui.DIM, false);
        }
        if (!owned) {
            // A padlock on the banner.
            int lx = x + cw / 2 - 4, ly = y + 6;
            c.fill(lx + 1, ly, lx + 7, ly + 2, 0xC0101010);
            c.fill(lx + 1, ly, lx + 2, ly + 5, 0xC0101010);
            c.fill(lx + 6, ly, lx + 7, ly + 5, 0xC0101010);
            c.fill(lx, ly + 5, lx + 8, ly + 12, 0xE0101010);
            c.fill(lx + 3, ly + 7, lx + 5, ly + 10, 0xFF8F8A7A);
        }
    }

    private static int lerp(int a, int b, float u) {
        int r = (int) (((a >> 16) & 255) * (1 - u) + ((b >> 16) & 255) * u);
        int g = (int) (((a >> 8) & 255) * (1 - u) + ((b >> 8) & 255) * u);
        int bl = (int) ((a & 255) * (1 - u) + (b & 255) * u);
        return (r << 16) | (g << 8) | bl;
    }

    private static String fit(String s, int w, float scale) {
        if (Ui.font().getWidth(s) * scale <= w) return s;
        while (s.length() > 1 && Ui.font().getWidth(s + "...") * scale > w) s = s.substring(0, s.length() - 1);
        return s + "...";
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
                    case "head_laurel" -> ellipse(c, cx, hy + 2, 13, 3, col, 1f);
                    case "head_sun" -> {
                        for (int k = 0; k < 12; k++) {
                            double a = k * Math.PI / 6 + t * 0.4;
                            int len = k % 2 == 0 ? 20 : 14;
                            for (int d = 9; d < len; d += 2) dot(c, cx + (int) (Math.cos(a) * d), hy + 4 + (int) (Math.sin(a) * d), k % 2 == 0 ? col : col2, 1f, 2);
                        }
                    }
                    case "head_crows" -> {
                        for (int k = 0; k < 4; k++) {
                            double a = t * 1.1 + k * Math.PI / 2;
                            int x = cx + (int) (Math.cos(a) * 30), y = hy - 12 + (int) (Math.sin(a) * 4);
                            int f = (int) (Math.sin(t * 9 + k) * 2);
                            c.fill(x - 4, y + f, x, y + 1 + f, 0xFF1A1A22);
                            c.fill(x, y, x + 1, y + 2, 0xFF1A1A22);
                            c.fill(x + 1, y + f, x + 5, y + 1 + f, 0xFF1A1A22);
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
            case "back" -> {
                if (e.id().equals("back_none")) return;
                int sy = feetY - 70;
                if (e.id().startsWith("back_wings")) {
                    float flap = (float) Math.sin(t * 2.2) * 0.2f;
                    for (int s = -1; s <= 1; s += 2) {
                        for (int f = 0; f < 7; f++) {
                            double ang = Math.toRadians(-30 + f * 15) + flap;
                            int len = 34 + (3 - Math.abs(f - 3)) * 5;
                            for (int k = 3; k < len; k += 2) {
                                float u = k / (float) len;
                                dot(c, cx + (int) (s * Math.cos(ang) * k), sy - (int) (Math.sin(ang) * k), lerp(col, col2, u), 0.9f - u * 0.5f, 2);
                            }
                        }
                    }
                } else if (e.id().startsWith("back_cloak")) {
                    float sway = (float) Math.sin(t * 2.2) * 3;
                    for (int k = 0; k < 44; k++) {
                        int half = 12 + k / 8;
                        c.fill(cx - half + (int) (sway * k / 44f) + 30, sy + k, cx + half + (int) (sway * k / 44f) + 30, sy + k + 1, 0xFF000000 | col);
                    }
                    c.fill(cx + 26, sy + 12, cx + 34, sy + 20, 0xFF000000 | col2);
                } else if (e.id().equals("back_banner")) {
                    c.fill(cx + 22, sy - 40, cx + 24, sy + 30, 0xFF5A3A1A);
                    for (int k = 0; k < 30; k++) {
                        int wv = (int) (Math.sin(t * 5 - k * 0.3) * 2);
                        c.fill(cx + 24 + k, sy - 38 + wv, cx + 25 + k, sy - 20 + wv, 0xFF000000 | (k > 25 ? col2 : col));
                    }
                } else {
                    for (int i = 0; i < 12; i++) {
                        float life = (t * 0.7f + i * 0.21f) % 1f;
                        int s = i % 2 == 0 ? -1 : 1;
                        dot(c, cx + s * (10 + (int) (life * 16)), sy - (int) (life * 40), i % 3 == 0 ? col2 : col, 1 - life, 3);
                    }
                }
            }
            case "kill" -> {
                float p = (t * 0.5f) % 1f;
                int kx = px + pw - 60, ky = py + ph / 2 + 10;
                switch (e.id()) {
                    case "kill_holy" -> {
                        for (int k = 0; k < 30; k++) dot(c, kx + (int) (Math.cos(k + t * 3) * 6), ky - (int) (((k / 30f + p) % 1f) * 70), k % 3 == 0 ? col2 : col, 1f, 2);
                    }
                    case "kill_lightning" -> {
                        if (p < 0.35f) {
                            int bx = kx;
                            for (int yy = py + 4; yy < ky; yy += 4) {
                                int nx = bx + (int) ((Math.sin(yy * 1.7 + t) * 4));
                                c.fill(Math.min(bx, nx), yy, Math.max(bx, nx) + 2, yy + 4, 0xFFFFFFFF);
                                bx = nx;
                            }
                            if (p < 0.1f) c.fill(px + 1, py + 1, px + pw - 1, py + ph - 1, 0x40FFFFFF);
                        }
                    }
                    case "kill_wings" -> {
                        for (int s = -1; s <= 1; s += 2) for (int f = 0; f < 6; f++) {
                            double ang = Math.toRadians(-20 + f * 18);
                            int len = (int) (p * 40);
                            for (int k = 4; k < len; k += 3) dot(c, kx + (int) (s * Math.cos(ang) * k), ky - 20 - (int) (Math.sin(ang) * k), k > len - 6 ? col2 : col, 1 - p * 0.6f, 2);
                        }
                    }
                    case "kill_firework" -> {
                        for (int k = 0; k < 24; k++) {
                            double a = k * Math.PI * 2 / 24;
                            dot(c, kx + (int) (Math.cos(a) * p * 30), ky - 30 + (int) (Math.sin(a) * p * 30 + p * p * 10), k % 2 == 0 ? col : col2, 1 - p, 2);
                        }
                    }
                    case "kill_void" -> {
                        for (int k = 0; k < 20; k++) {
                            double a = k * 0.9 + t;
                            int r = (int) ((1 - p) * 34);
                            dot(c, kx + (int) (Math.cos(a) * r), ky - 20 + (int) (Math.sin(a) * r), k % 3 == 0 ? col2 : col, p, 2);
                        }
                    }
                    default -> {
                        for (int k = 0; k < 26; k++) {
                            float life = (p + k * 0.037f) % 1f;
                            dot(c, kx + (int) (Math.sin(k * 2.1) * 18), ky - (int) (life * 60), k % 3 == 0 ? col2 : col, 1 - life,
                                e.id().equals("kill_steam") ? 4 : 2);
                        }
                    }
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
