package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A workbench, set down in the field: the bench's name and how long it has left across the top;
 * the trades down the left (Lumber, Woodwork, Masonry...); what you can make down the middle,
 * ready ones lit, short ones dimmed, unknown ones locked with what it takes to learn them; and the
 * chosen one on the right, with its materials counted against what you carry. Pick how many, hold
 * nothing: one press and it's made.
 */
public final class CraftScreen extends Screen {
    private static final int ROW = 22;
    private static Net.CraftView view;
    private static long viewAt;

    private String category = "All";
    private String selected;
    private int scroll, qty = 1;
    private long craftingAt;
    private int lx, ly, lw, lh, mx0, mw, rx, rw, top, rowsShown;

    public CraftScreen(Net.CraftView v) {
        super(Text.literal(v.title()));
        view = v;
        viewAt = Util.getMeasuringTimeMs();
    }

    public static void on(Net.CraftView v) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (v.secondsLeft() < 0) {
            if (mc.currentScreen instanceof CraftScreen) mc.setScreen(null);
            view = null;
            return;
        }
        if (v.open()) {
            mc.setScreen(new CraftScreen(v));
            return;
        }
        view = v;
        viewAt = Util.getMeasuringTimeMs();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        ClientPlayNetworking.send(new Net.CraftAction("close", "", 0));
    }

    @Override
    protected void init() {
        top = 44;
        lx = 18;
        lw = 92;
        mx0 = lx + lw + 10;
        mw = Math.min(210, (width - mx0 - 30) / 2 + 20);
        rx = mx0 + mw + 12;
        rw = width - rx - 18;
        lh = height - top - 16;
        rowsShown = Math.max(3, lh / ROW);
    }

    // ------------------------------------------------------------------ data

    private List<String> categories() {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        s.add("All");
        for (Net.CraftRow r : view.rows()) s.add(r.category());
        return new ArrayList<>(s);
    }

    private static boolean ready(Net.CraftRow r) {
        if (!r.known()) return false;
        for (int i = 0; i < r.inputs().size(); i++) if (r.have().get(i) < r.inputs().get(i).getCount()) return false;
        return true;
    }

    private static int most(Net.CraftRow r) {
        int n = 64;
        for (int i = 0; i < r.inputs().size(); i++) n = Math.min(n, r.have().get(i) / Math.max(1, r.inputs().get(i).getCount()));
        return n;
    }

    /** This category's rows: ready first, then known but short, then locked. */
    private List<Net.CraftRow> rows() {
        List<Net.CraftRow> out = new ArrayList<>();
        for (int pass = 0; pass < 3; pass++) {
            for (Net.CraftRow r : view.rows()) {
                if (!category.equals("All") && !r.category().equals(category)) continue;
                int kind = !r.known() ? 2 : ready(r) ? 0 : 1;
                if (kind == pass) out.add(r);
            }
        }
        return out;
    }

    private Net.CraftRow chosen(List<Net.CraftRow> rows) {
        for (Net.CraftRow r : rows) if (r.id().equals(selected)) return r;
        if (rows.isEmpty()) return null;
        selected = rows.get(0).id();
        return rows.get(0);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        // The world stays visible behind: a dark wash, heavier at the edges.
        c.fillGradient(0, 0, width, height, 0xC0080A08, 0xD8050605);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        super.render(c, mouseX, mouseY, delta);
        if (view == null) return;
        int col = 0xFF000000 | view.color();
        long now = Util.getMeasuringTimeMs();
        // Header: the bench, the craft, and the clock.
        c.fill(0, 0, width, 34, 0xB0000000);
        c.fill(0, 34, width, 35, col & 0x90FFFFFF);
        c.getMatrices().push();
        c.getMatrices().translate(lx, 9, 0);
        c.getMatrices().scale(1.6f, 1.6f, 1);
        c.drawText(textRenderer, Ui.heading(view.title()), 0, 0, col, true);
        c.getMatrices().pop();
        String skill = view.skill() + " " + view.skillLevel();
        int secs = Math.max(0, view.secondsLeft() - (int) ((now - viewAt) / 1000));
        String clock = String.format("%d:%02d", secs / 60, secs % 60);
        boolean low = secs < 30;
        int clockCol = low ? (((now / 250) % 2 == 0) ? 0xFFFF5A4A : 0xFFB03A30) : 0xFFEDE3C8;
        int cw = textRenderer.getWidth(clock) * 2;
        c.getMatrices().push();
        c.getMatrices().translate(width - 18 - cw, 8, 0);
        c.getMatrices().scale(2f, 2f, 1);
        c.drawText(textRenderer, clock, 0, 0, clockCol, true);
        c.getMatrices().pop();
        c.drawText(textRenderer, Text.literal(skill), width - 30 - cw - textRenderer.getWidth(skill), 13, col, true);

        // Trades down the left.
        List<String> cats = categories();
        int y = top;
        for (String cat : cats) {
            boolean on = cat.equals(category), hover = mouseX >= lx && mouseX < lx + lw && mouseY >= y && mouseY < y + 18;
            c.fill(lx, y, lx + lw, y + 18, on ? 0x90000000 : hover ? 0x60000000 : 0x30000000);
            if (on) c.fill(lx, y, lx + 3, y + 18, col);
            c.drawText(textRenderer, cat, lx + 9, y + 5, on ? 0xFFFFFFFF : 0xFFCFC6B0, false);
            y += 20;
        }

        // What can be made.
        List<Net.CraftRow> rows = rows();
        Net.CraftRow sel = chosen(rows);
        scroll = MathHelper.clamp(scroll, 0, Math.max(0, rows.size() - rowsShown));
        c.fill(mx0 - 4, top - 4, mx0 + mw + 4, top + rowsShown * ROW + 4, 0x50000000);
        for (int i = 0; i < rowsShown && scroll + i < rows.size(); i++) {
            Net.CraftRow r = rows.get(scroll + i);
            int ry = top + i * ROW;
            boolean isSel = sel != null && r.id().equals(sel.id());
            boolean hover = mouseX >= mx0 && mouseX < mx0 + mw && mouseY >= ry && mouseY < ry + ROW - 2;
            boolean ok = ready(r);
            c.fill(mx0, ry, mx0 + mw, ry + ROW - 2, isSel ? 0xA0000000 : hover ? 0x70000000 : 0x40000000);
            if (isSel) {
                c.fill(mx0, ry, mx0 + 2, ry + ROW - 2, col);
                c.fill(mx0, ry + ROW - 3, mx0 + mw, ry + ROW - 2, col & 0x60FFFFFF);
            }
            ItemStack icon = r.result();
            if (!r.known()) {
                // Locked: the shape of the thing, in shadow.
                c.getMatrices().push();
                c.drawItem(icon, mx0 + 4, ry + 2);
                c.getMatrices().pop();
                c.fill(mx0 + 4, ry + 2, mx0 + 20, ry + 18, 0xB0000000);
            } else {
                c.drawItem(icon, mx0 + 4, ry + 2);
            }
            String name = r.name() + (icon.getCount() > 1 ? "  ×" + icon.getCount() : "");
            int nameCol = !r.known() ? 0xFF6E685C : ok ? 0xFFF4EEDC : 0xFFB9AE98;
            c.drawText(textRenderer, textRenderer.trimToWidth(name, mw - 70), mx0 + 25, ry + 6, nameCol, false);
            if (!r.known()) {
                String h = r.hint();
                c.drawText(textRenderer, h, mx0 + mw - 6 - textRenderer.getWidth(h), ry + 6, col & 0xB0FFFFFF, false);
            } else if (ok) {
                // A lit pip: ready to make.
                c.fill(mx0 + mw - 10, ry + 8, mx0 + mw - 5, ry + 13, 0xFF7FD06A);
            }
        }
        if (rows.size() > rowsShown) {
            int bh = rowsShown * ROW, th = Math.max(14, bh * rowsShown / rows.size());
            int ty = top + (bh - th) * scroll / Math.max(1, rows.size() - rowsShown);
            c.fill(mx0 + mw + 1, ty, mx0 + mw + 3, ty + th, col & 0xA0FFFFFF);
        }

        // The chosen one.
        if (sel != null && rw > 120) detail(c, sel, mouseX, mouseY, col, now);
    }

    private int craftX, craftY, craftW, minusX, plusX, maxX, btnY;

    private void detail(DrawContext c, Net.CraftRow r, int mouseX, int mouseY, int col, long now) {
        int x = rx, y = top;
        c.fill(x - 6, y - 4, x + rw, height - 16, 0x50000000);
        // Big and turning slowly.
        c.getMatrices().push();
        c.getMatrices().translate(x + 4, y + 2, 0);
        c.getMatrices().scale(2.5f, 2.5f, 1);
        c.drawItem(r.result(), 0, 0);
        c.getMatrices().pop();
        boolean overIcon = mouseX >= x + 4 && mouseX < x + 44 && mouseY >= y + 2 && mouseY < y + 42;
        c.drawText(textRenderer, Ui.heading(r.name()), x + 52, y + 8, r.known() ? 0xFFF4EEDC : 0xFF8A8478, true);
        if (r.result().getCount() > 1) c.drawText(textRenderer, "Makes " + r.result().getCount(), x + 52, y + 22, col, false);
        y += 52;
        // Materials, counted against what you carry.
        ItemStack hoverStack = overIcon ? r.result() : null;
        for (int i = 0; i < r.inputs().size(); i++) {
            ItemStack in = r.inputs().get(i);
            int need = in.getCount() * Math.max(1, qty), have = r.have().get(i);
            boolean enough = have >= need;
            c.fill(x, y, x + rw - 12, y + 20, 0x40000000);
            c.fill(x, y, x + 2, y + 20, enough ? 0xFF7FD06A : 0xFFD0503A);
            ItemStack show = in.copyWithCount(1);
            c.drawItem(show, x + 5, y + 2);
            String label = !r.labels().get(i).isEmpty() ? r.labels().get(i) : in.getName().getString();
            c.drawText(textRenderer, textRenderer.trimToWidth(label, rw - 90), x + 26, y + 6, 0xFFE8DCC0, false);
            String count = have + " / " + need;
            c.drawText(textRenderer, count, x + rw - 16 - textRenderer.getWidth(count), y + 6, enough ? 0xFF9FE08A : 0xFFF07060, false);
            if (mouseX >= x && mouseX < x + rw - 12 && mouseY >= y && mouseY < y + 20) hoverStack = show;
            y += 22;
        }
        y += 8;
        btnY = y;
        if (!r.known()) {
            // Locked: what it takes.
            c.fill(x, y, x + rw - 12, y + 26, 0x70000000);
            c.fill(x, y, x + rw - 12, y + 1, col & 0x80FFFFFF);
            String h = r.hint().equals("Schematic") ? "Learn it from a schematic" : "Unlocks at " + r.hint();
            c.drawText(textRenderer, Ui.heading(h), x + 10, y + 9, col, false);
            craftW = 0;
        } else {
            int most = most(r);
            // Quantity: - n + max
            minusX = x;
            c.fill(minusX, y, minusX + 18, y + 22, 0x70000000);
            c.drawCenteredTextWithShadow(textRenderer, "-", minusX + 9, y + 7, 0xFFFFFFFF);
            String q = "×" + qty;
            c.drawCenteredTextWithShadow(textRenderer, q, x + 38, y + 7, 0xFFFFFFFF);
            plusX = x + 58;
            c.fill(plusX, y, plusX + 18, y + 22, 0x70000000);
            c.drawCenteredTextWithShadow(textRenderer, "+", plusX + 9, y + 7, 0xFFFFFFFF);
            maxX = x + 80;
            c.fill(maxX, y, maxX + 30, y + 22, 0x70000000);
            c.drawCenteredTextWithShadow(textRenderer, "Max", maxX + 15, y + 7, most > 0 ? 0xFFFFFFFF : 0xFF6E685C);
            // The button: fills as it works.
            craftX = x + 118;
            craftY = y;
            craftW = Math.max(60, rw - 12 - 118);
            boolean can = most >= qty && most > 0;
            boolean hover = mouseX >= craftX && mouseX < craftX + craftW && mouseY >= y && mouseY < y + 22;
            c.fill(craftX, y, craftX + craftW, y + 22, can ? (hover ? (col & 0xE0FFFFFF) : (col & 0xA0FFFFFF)) : 0x60303030);
            float k = craftingAt == 0 ? 0 : MathHelper.clamp((now - craftingAt) / 380f, 0, 1);
            if (k > 0) c.fill(craftX, y + 20, craftX + (int) (craftW * k), y + 22, 0xFFFFFFFF);
            c.drawCenteredTextWithShadow(textRenderer, Ui.heading(can ? "CRAFT" : "MISSING MATERIALS"), craftX + craftW / 2, y + 7, can ? 0xFFFFFFFF : 0xFFB0A898);
            if (craftingAt != 0 && now - craftingAt >= 380) {
                craftingAt = 0;
                ClientPlayNetworking.send(new Net.CraftAction("craft", r.id(), qty));
            }
        }
        if (hoverStack != null) c.drawItemTooltip(textRenderer, hoverStack, mouseX, mouseY);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (view == null) return super.mouseClicked(mx, my, button);
        List<String> cats = categories();
        int y = top;
        for (String cat : cats) {
            if (mx >= lx && mx < lx + lw && my >= y && my < y + 18) {
                category = cat;
                scroll = 0;
                selected = null;
                qty = 1;
                click();
                return true;
            }
            y += 20;
        }
        List<Net.CraftRow> rows = rows();
        if (mx >= mx0 && mx < mx0 + mw && my >= top && my < top + rowsShown * ROW) {
            int i = scroll + (int) ((my - top) / ROW);
            if (i >= 0 && i < rows.size()) {
                selected = rows.get(i).id();
                qty = 1;
                click();
            }
            return true;
        }
        Net.CraftRow sel = chosen(rows);
        if (sel != null && sel.known() && my >= btnY && my < btnY + 22) {
            if (mx >= minusX && mx < minusX + 18) {
                qty = Math.max(1, qty - (hasShiftDown() ? 10 : 1));
                click();
                return true;
            }
            if (mx >= plusX && mx < plusX + 18) {
                qty = Math.min(64, qty + (hasShiftDown() ? 10 : 1));
                click();
                return true;
            }
            if (mx >= maxX && mx < maxX + 30) {
                qty = Math.max(1, most(sel));
                click();
                return true;
            }
            if (craftW > 0 && mx >= craftX && mx < craftX + craftW) {
                startCraft(sel);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private void startCraft(Net.CraftRow sel) {
        if (craftingAt != 0 || most(sel) < qty || most(sel) <= 0) return;
        craftingAt = Util.getMeasuringTimeMs();
        if (client != null && client.player != null) client.player.playSound(SoundEvents.UI_LOOM_TAKE_RESULT, 0.5f, 1.2f);
    }

    private void click() {
        if (client != null && client.player != null) client.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.3f, 1.4f);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        if (mx >= mx0 && mx < mx0 + mw) {
            scroll = Math.max(0, scroll - (int) Math.signum(vy) * 2);
            return true;
        }
        return super.mouseScrolled(mx, my, hx, vy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (view != null && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            Net.CraftRow sel = chosen(rows());
            if (sel != null && sel.known()) startCraft(sel);
            return true;
        }
        if (view != null && (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN)) {
            List<Net.CraftRow> rows = rows();
            int i = 0;
            for (int k = 0; k < rows.size(); k++) if (rows.get(k).id().equals(selected)) i = k;
            i = MathHelper.clamp(i + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1), 0, Math.max(0, rows.size() - 1));
            if (!rows.isEmpty()) selected = rows.get(i).id();
            if (i < scroll) scroll = i;
            if (i >= scroll + rowsShown) scroll = i - rowsShown + 1;
            qty = 1;
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
