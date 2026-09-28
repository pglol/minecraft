package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.List;
import java.util.Locale;

/**
 * Bartering at a stall. Buy and Sell tabs over a list down the left (what's for sale, or what
 * they'd take off you), the world still showing through on the right where the selected piece
 * turns slowly, large, with all its details. Pick how many, see the total against your purse,
 * and deal.
 */
public final class VendorScreen extends Screen {
    private static Net.VendorView view;
    private static boolean selling;
    private int sel, scroll, qty = 1;
    private static final int ROW = 20;
    private int lx, ly, lw, rowsShown, dx, dw;
    private AotButton minus, plus, max, deal;

    public VendorScreen() {
        super(Text.literal("Trader"));
    }

    public static void on(Net.VendorView v) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof VendorScreen s) {
            view = v;
            s.clampSel();
            s.clearAndInit();
        } else if (v.open()) {
            view = v;
            selling = false;
            mc.setScreen(new VendorScreen());
        }
    }

    private int count() {
        if (view == null) return 0;
        return selling ? view.sells().size() : view.items().size();
    }

    private ItemStack stack(int i) {
        return selling ? view.sells().get(i).stack() : view.items().get(i);
    }

    private long each(int i) {
        return selling ? view.sellPrices().get(i) : view.prices().get(i);
    }

    /** How many can change hands for this line: what's left (or what you hold), and for buying, what you can afford. */
    private int limit(int i) {
        if (i < 0 || i >= count()) return 0;
        if (selling) return stack(i).getCount();
        int left = view.units().get(i);
        long e = Math.max(1, each(i));
        return (int) Math.max(0, Math.min(left, ClientState.marks / e));
    }

    private void clampSel() {
        sel = Math.max(0, Math.min(sel, count() - 1));
        qty = Math.max(1, Math.min(qty, Math.max(1, limit(sel))));
    }

    @Override
    protected void init() {
        lx = 24;
        ly = 78;
        lw = Math.min(320, (int) (width * 0.44));
        rowsShown = Math.max(3, (height - ly - 46) / ROW);
        dx = lx + lw + 30;
        dw = width - dx - 24;
        addDrawableChild(new AotButton(width - 30, 10, 20, 20, Text.literal("✕"), this::close));
        AotButton buy = addDrawableChild(new AotButton(lx, 52, 70, 18, Text.literal("Buy"), () -> tab(false)));
        AotButton sell = addDrawableChild(new AotButton(lx + 74, 52, 70, 18, Text.literal("Sell"), () -> tab(true)));
        buy.selected(!selling);
        sell.selected(selling);
        if (view == null) return;
        clampSel();
        int by = height - 70;
        minus = addDrawableChild(new AotButton(dx, by, 20, 18, Text.literal("−"), () -> setQty(qty - 1)));
        plus = addDrawableChild(new AotButton(dx + 76, by, 20, 18, Text.literal("+"), () -> setQty(qty + 1)));
        max = addDrawableChild(new AotButton(dx + 100, by, 40, 18, Text.literal("All"), () -> setQty(limit(sel))));
        max.textScale = 0.75f;
        deal = addDrawableChild(new AotButton(dx + dw - 120, by - 2, 120, 22, Text.literal(selling ? "Sell" : "Buy"), this::deal));
        if (view.shady()) deal.accent = 0xFF8A2A2A;
        refreshButtons();
    }

    private void tab(boolean s) {
        if (selling == s) return;
        selling = s;
        sel = 0;
        scroll = 0;
        qty = 1;
        clearAndInit();
    }

    private void setQty(int q) {
        qty = Math.max(1, Math.min(q, Math.max(1, limit(sel))));
        refreshButtons();
    }

    private void refreshButtons() {
        if (deal == null) return;
        boolean any = count() > 0 && limit(sel) > 0;
        int stackable = count() > 0 ? (selling ? stack(sel).getCount() : view.units().get(sel)) : 0;
        boolean many = stackable > 1;
        minus.visible = plus.visible = max.visible = many;
        minus.active = qty > 1;
        plus.active = qty < limit(sel);
        max.active = qty < limit(sel);
        deal.active = any;
    }

    private void deal() {
        if (view == null || count() == 0 || limit(sel) <= 0) return;
        if (selling) ClientPlayNetworking.send(new Net.VendorSell(view.entity(), view.sells().get(sel).slot(), qty));
        else ClientPlayNetworking.send(new Net.VendorBuy(view.entity(), sel, qty));
        qty = 1;
    }

    private long lastMarks = -1;

    @Override
    public void tick() {
        if (lastMarks != ClientState.marks) {
            lastMarks = ClientState.marks;
            clampSel();
            refreshButtons();
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        var e = view == null || mc.world == null ? null : mc.world.getEntityById(view.entity());
        if (e == null || mc.player == null || e.squaredDistanceTo(mc.player) > 10 * 10) close();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        if (mx < lx + lw) {
            scroll = Math.max(0, Math.min(Math.max(0, count() - rowsShown), scroll - (int) Math.signum(vy)));
            return true;
        }
        setQty(qty + (int) Math.signum(vy));
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (view == null) return false;
        if (mx >= lx && mx < lx + lw && my >= ly) {
            int i = scroll + (int) ((my - ly) / ROW);
            if (i >= 0 && i < count() && i < scroll + rowsShown) {
                if (i != sel) {
                    sel = i;
                    qty = 1;
                    refreshButtons();
                    if (client != null && client.player != null) client.player.playSound(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.25f, 1.6f);
                } else if (button == 0 && hasShiftDown()) deal();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        // Up / down through the list, enter to deal.
        if (key == 265 || key == 264) {
            sel = Math.max(0, Math.min(count() - 1, sel + (key == 264 ? 1 : -1)));
            if (sel < scroll) scroll = sel;
            if (sel >= scroll + rowsShown) scroll = sel - rowsShown + 1;
            qty = 1;
            refreshButtons();
            return true;
        }
        if (key == 257 || key == 335) {
            deal();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        // Dark down the list side, the stall and the world still there on the right.
        for (int x = 0; x < width; x += 8) {
            float k = Math.min(1, x / (float) Math.max(1, width));
            int a = (int) (215 - 150 * k);
            c.fill(x, 0, x + 8, height, a << 24 | 0x0A0908);
        }
        if (view == null) return;
        int nameCol = view.shady() ? 0xFFC04A4A : Ui.GOLD;
        Ui.text(c, Ui.title(view.name().toUpperCase(Locale.ROOT)), lx, 16, 1.35f, nameCol, false);
        Ui.text(c, Text.literal(view.sub()), lx, 34, 0.75f, 0xFFB8B0A0, false);
        // The list.
        c.fill(lx - 6, ly - 4, lx + lw + 6, ly + rowsShown * ROW + 4, 0x70000000);
        c.fill(lx - 6, ly - 4, lx + lw + 6, ly - 3, 0x50E0B96A);
        if (count() == 0) {
            Ui.text(c, Text.literal(selling ? "Nothing they'd take off you" : "Sold out for today"), lx + 6, ly + 6, 0.85f, Ui.MUTED, false);
        }
        long now = Util.getMeasuringTimeMs();
        for (int r = 0; r < rowsShown; r++) {
            int i = scroll + r;
            if (i >= count()) break;
            ItemStack s = stack(i);
            int y = ly + r * ROW;
            boolean on = i == sel, hov = mouseX >= lx && mouseX < lx + lw && mouseY >= y && mouseY < y + ROW;
            boolean out = !selling && view.units().get(i) <= 0;
            if (on) {
                c.fillGradient(lx - 4, y, lx + lw + 4, y + ROW - 1, 0x60E0B96A, 0x20E0B96A);
                c.fill(lx - 4, y, lx - 2, y + ROW - 1, Ui.GOLD);
            } else if (hov) c.fill(lx - 4, y, lx + lw + 4, y + ROW - 1, 0x22FFFFFF);
            int q = GearUi.rarity(s);
            int tone = q >= 0 ? Ui.rarityTone(q) : 0xFFD8D0C0;
            c.drawItem(s, lx, y + 2);
            String name = s.getName().getString();
            int room = lw - 110;
            while (textRenderer.getWidth(name) * 0.85f > room && name.length() > 4) name = name.substring(0, name.length() - 2);
            if (!name.equals(s.getName().getString())) name = name + "…";
            Ui.text(c, Text.literal(name), lx + 22, y + 6, 0.85f, out ? Ui.DIM : (q >= 2 ? tone : 0xFFEDE3C8), false);
            int n = selling ? s.getCount() : view.units().get(i);
            String cnt = out ? "sold" : n > 1 ? "×" + n : "";
            String price = String.format(Locale.ROOT, "%,d", each(i));
            float pw = textRenderer.getWidth(price) * 0.85f;
            Ui.text(c, Text.literal(price), lx + lw - pw, y + 6, 0.85f, out ? Ui.DIM : Ui.GOLD, false);
            Ui.text(c, Text.literal(cnt), lx + lw - 70, y + 6, 0.75f, Ui.MUTED, false);
        }
        int total = count();
        if (total > rowsShown) {
            int bh = rowsShown * ROW, th = Math.max(12, bh * rowsShown / total), ty = ly + (bh - th) * scroll / Math.max(1, total - rowsShown);
            c.fill(lx + lw + 4, ly, lx + lw + 5, ly + bh, 0x30FFFFFF);
            c.fill(lx + lw + 4, ty, lx + lw + 5, ty + th, 0xC0E0B96A);
        }
        if (total == 0) return;
        // The selected piece, large and turning slowly, with everything about it.
        ItemStack s = stack(sel);
        int q = GearUi.rarity(s);
        int tone = q >= 0 ? Ui.rarityTone(q) : Ui.GOLD;
        int cx = dx + dw / 2, iy = 70;
        float bob = (float) Math.sin(now / 700.0) * 3;
        c.fillGradient(cx - 70, iy - 10, cx + 70, iy + 110, (tone & 0xFFFFFF) | 0x30000000, 0x00000000);
        var m = c.getMatrices();
        m.push();
        m.translate(cx, iy + 48 + bob, 150);
        m.scale(5f, 5f, 1f);
        c.drawItem(s, -8, -8);
        m.pop();
        int ty = iy + 118;
        Ui.text(c, s.getName().copy(), cx, ty, 1.15f, q >= 2 ? tone : 0xFFEDE3C8, true);
        List<Text> lines = Screen.getTooltipFromItem(MinecraftClient.getInstance(), s);
        int ly2 = ty + 16;
        for (int k = 1; k < lines.size() && ly2 < height - 110; k++) {
            if (lines.get(k).getString().isBlank()) continue;
            Ui.text(c, lines.get(k), cx, ly2, 0.75f, 0xFFD8D0C0, true);
            ly2 += 10;
        }
        // The deal: how many, the total, and your purse after.
        int by = height - 70;
        int n = selling ? s.getCount() : view.units().get(sel);
        if (n > 1) {
            String qs = String.valueOf(qty);
            Ui.text(c, Text.literal(qs), dx + 48, by + 5, 1f, 0xFFF2EDE2, true);
            // A bar showing how much of what's there this is.
            int bw = dw - 280;
            if (bw > 40) {
                int bx = dx + 150;
                c.fill(bx, by + 8, bx + bw, by + 10, 0x40FFFFFF);
                c.fill(bx, by + 8, bx + (int) (bw * qty / (float) Math.max(1, n)), by + 10, Ui.GOLD);
            }
        }
        long cost = each(sel) * qty;
        String tot = (selling ? "+" : "") + String.format(Locale.ROOT, "%,d Marks", cost);
        Ui.text(c, Text.literal(tot), dx + dw - textRenderer.getWidth(tot), by - 18, 1f, selling ? 0xFF7FD06A : (cost > ClientState.marks ? 0xFFC04A4A : Ui.GOLD), false);
        String purse = String.format(Locale.ROOT, "%,d", ClientState.marks) + "  →  " + String.format(Locale.ROOT, "%,d", ClientState.marks + (selling ? cost : -cost));
        Ui.text(c, Text.literal(purse), dx + dw - textRenderer.getWidth(purse) * 0.75f, by + 28, 0.75f, 0xFFB8B0A0, false);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        super.render(c, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
