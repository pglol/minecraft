package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

import java.util.Locale;
import java.util.Random;

/**
 * The forge. Temper: pick a piece and it rises with you, a level per temper (two for a perfect set
 * of strikes), never past your own level + 3. Forge New: make gear from materials. Both go through
 * the strike: three hammer blows in the heart of the heat.
 */
public final class ForgeScreen extends Screen {
    private static int tab, selected = -1;
    private int left, top, w, h, scroll;
    private static final int ROW = 26;
    /** The last level seen for the selected piece: when it rises, the anvil throws sparks. */
    private static int seenLevel = -1;
    private static long sparkAt;
    private static boolean perfectSpark;
    private static float lastQuality;

    public ForgeScreen() {
        super(Text.literal("Forge"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        Net.ForgeGear g = current();
        if (g != null && seenLevel >= 0 && g.level() > seenLevel) {
            sparkAt = Util.getMeasuringTimeMs();
            perfectSpark = g.level() - seenLevel >= 2;
        }
        seenLevel = g == null ? -1 : g.level();
        clearAndInit();
    }

    private Net.ForgeGear current() {
        Net.ForgeView v = ClientState.forge;
        if (v == null) return null;
        for (Net.ForgeGear g : v.gear()) if (g.slot() == selected) return g;
        if (!v.gear().isEmpty()) {
            selected = v.gear().get(0).slot();
            return v.gear().get(0);
        }
        return null;
    }

    private void strike(String title, float difficulty, java.util.function.Consumer<Float> then) {
        client.setScreen(new MinigameScreen(MinigameScreen.Kind.STRIKE, title, "Three blows: Space or click in the bright heart of the heat", difficulty, q -> {
            lastQuality = q;
            then.accept(q);
            client.setScreen(this);
        }));
    }

    @Override
    protected void init() {
        w = Math.min(520, width - 20);
        h = Math.min(300, height - 70);
        left = (width - w) / 2;
        top = Math.max(50, (height - h) / 2 + 12);
        addDrawableChild(new AotButton(left, top - 24, 120, 20, Ui.heading("Temper"), () -> {
            tab = 0;
            clearAndInit();
        })).selected(tab == 0);
        addDrawableChild(new AotButton(left + 124, top - 24, 120, 20, Ui.heading("Forge new"), () -> {
            tab = 1;
            clearAndInit();
        })).selected(tab == 1);
        addDrawableChild(new AotButton(left + w - 20, top - 24, 20, 20, Text.literal("✕"), this::close));
        Net.ForgeView v = ClientState.forge;
        if (v == null) return;
        if (tab == 0) {
            Net.ForgeGear g = current();
            if (seenLevel < 0 && g != null) seenLevel = g.level();
            if (g != null) {
                int px = left + listW() + 16, pw = w - listW() - 26;
                boolean capped = g.level() >= g.cap();
                AotButton t = addDrawableChild(new AotButton(px + 10, top + h - 40, pw - 20, 28,
                    Ui.heading(capped ? "At your limit" : "TEMPER  ·  " + String.format(Locale.ROOT, "%,d", g.marks()) + " M"),
                    () -> strike("Temper", Math.min(0.9f, g.level() / 60f), q -> ClientPlayNetworking.send(new Net.ForgeAction("upgrade", g.slot(), "", q)))));
                t.selected(!capped);
                t.accent = 0xFFFF8A3A;
                t.active = !capped && ClientState.marks >= g.marks();
            }
        } else {
            int y = top + 34;
            for (Net.ForgeRecipe r : v.recipes()) {
                if (y > top + h - 34) break;
                AotButton b = addDrawableChild(new AotButton(left + w - 110, y + 5, 96, 20, Ui.heading("Forge"),
                    () -> strike("Forge " + r.title(), 0.4f, q -> ClientPlayNetworking.send(new Net.ForgeAction("craft", 0, r.id(), q)))));
                b.active = r.ready();
                y += 36;
            }
        }
    }

    private int listW() {
        return Math.min(220, w * 45 / 100);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        Net.ForgeView v = ClientState.forge;
        if (tab == 0 && v != null && mx >= left + 8 && mx < left + 8 + listW() && my >= top + 34 && my < top + h - 8) {
            int i = (int) ((my - top - 34) / ROW) + scroll;
            if (i >= 0 && i < v.gear().size()) {
                selected = v.gear().get(i).slot();
                seenLevel = v.gear().get(i).level();
                if (client != null && client.player != null) client.player.playSound(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.2f);
                clearAndInit();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        Net.ForgeView v = ClientState.forge;
        if (v == null) return false;
        int rows = (h - 42) / ROW;
        scroll = MathHelper.clamp(scroll - (int) Math.signum(vy), 0, Math.max(0, v.gear().size() - rows));
        return true;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        long t = Util.getMeasuringTimeMs();
        // The forge's glow from below, breathing.
        int glow = (int) (70 + 30 * Math.sin(t * 0.003));
        c.fillGradient(0, height / 2, width, height, 0, (glow << 24) | 0xC0501A);
        Ui.text(c, Ui.title("FORGE"), width / 2f, top - 46, 1.4f, 0xFFFFB060, true);
        Ui.panel(c, left, top, w, h);
        Net.ForgeView v = ClientState.forge;
        if (v == null || client.player == null) return;
        String money = String.format(Locale.ROOT, "%,d Marks", ClientState.marks);
        Ui.text(c, Text.literal(money), left + w - 12 - Ui.font().getWidth(money) * 0.8f, top + 12, 0.8f, Ui.GOLD, false);
        Ui.text(c, Text.literal("Smithing " + v.smithing()), left + 12, top + 12, 0.8f, Ui.CREAM, false);
        if (tab == 0) temper(c, v, mouseX, mouseY, t);
        else recipes(c, v);
    }

    private void temper(DrawContext c, Net.ForgeView v, int mouseX, int mouseY, long t) {
        int lx = left + 8, ly = top + 34, lw = listW();
        Ui.well(c, lx, ly - 2, lw, h - 40);
        if (v.gear().isEmpty()) Ui.text(c, Text.literal("No gear to temper"), lx + lw / 2f, ly + 20, 0.85f, Ui.MUTED, true);
        int rows = (h - 42) / ROW;
        for (int i = 0; i < rows && i + scroll < v.gear().size(); i++) {
            Net.ForgeGear g = v.gear().get(i + scroll);
            ItemStack s = ClientState.stackAt(g.slot());
            int y = ly + i * ROW;
            boolean sel = g.slot() == selected, hov = mouseX >= lx && mouseX < lx + lw && mouseY >= y && mouseY < y + ROW - 2;
            int tone = Ui.rarityTone(g.rarity());
            c.fill(lx + 2, y, lx + lw - 2, y + ROW - 2, sel ? 0xE0302012 : hov ? 0xC0201A14 : 0x90100C0A);
            c.fill(lx + 2, y, lx + 4, y + ROW - 2, tone);
            GearUi.backing(c, s, lx + 8, y + 4);
            c.drawItem(s, lx + 8, y + 4);
            String name = s.getName().getString();
            while (Ui.font().getWidth(name) * 0.8f > lw - 70 && name.length() > 4) name = name.substring(0, name.length() - 2);
            Ui.text(c, Text.literal(name).withColor(tone & 0xFFFFFF), lx + 30, y + 5, 0.8f, 0xFFFFFFFF, false);
            boolean capped = g.level() >= g.cap();
            Ui.text(c, Text.literal("Lv " + g.level()), lx + 30, y + 15, 0.65f, capped ? Ui.GOLD : Ui.CREAM, false);
            if (capped) Ui.text(c, Text.literal("★"), lx + lw - 14, y + 8, 0.9f, Ui.GOLD, false);
            if (hov) c.drawItemTooltip(textRenderer, s, mouseX, mouseY);
        }
        // The anvil: the chosen piece, glowing in the heat of its rarity.
        Net.ForgeGear g = current();
        if (g == null) return;
        ItemStack s = ClientState.stackAt(g.slot());
        int px = left + lw + 16, pw = w - lw - 26, cx = px + pw / 2, cy = top + 96;
        int tone = Ui.rarityTone(g.rarity());
        float pulse = 0.6f + 0.4f * (float) Math.sin(t * 0.004);
        for (int r = 44; r > 0; r -= 4) {
            int a = (int) (26 * pulse * (1 - r / 48f) * 3);
            c.fill(cx - r, cy - r, cx + r, cy + r, (Math.min(255, a) << 24) | (tone & 0xFFFFFF));
        }
        // The anvil itself under it.
        c.fill(cx - 34, cy + 34, cx + 34, cy + 42, 0xFF2A2724);
        c.fill(cx - 22, cy + 42, cx + 22, cy + 48, 0xFF1C1A18);
        c.fill(cx - 30, cy + 48, cx + 30, cy + 54, 0xFF2A2724);
        float bob = (float) Math.sin(t * 0.003) * 2;
        Ui.item(c, s, cx - 24, (int) (cy - 26 + bob), 3f);
        // Sparks when it rises (gold and many on a perfect).
        long since = t - sparkAt;
        if (since < 1100) {
            Random r = new Random(sparkAt);
            int n = perfectSpark ? 60 : 28;
            float k = since / 1100f;
            if (since < 180) c.fill(0, 0, width, height, ((int) (90 * (1 - since / 180f)) << 24) | (perfectSpark ? 0xF2C14E : 0xFF8A3A));
            for (int i = 0; i < n; i++) {
                double a = r.nextDouble() * Math.PI * 2, sp = 40 + r.nextDouble() * 90;
                int x = cx + (int) (Math.cos(a) * sp * k), y = cy + (int) (Math.sin(a) * sp * k + 60 * k * k);
                int al = (int) (255 * (1 - k));
                c.fill(x, y, x + 2, y + 2, (al << 24) | (perfectSpark && i % 2 == 0 ? 0xF2C14E : 0xFF9A40));
            }
            if (perfectSpark) Ui.text(c, Ui.title("PERFECT"), cx, cy - 60 - 10 * k, 1.4f, ((int) (255 * (1 - k)) << 24) | 0xF2C14E, true);
        }
        Ui.text(c, Text.literal(s.getName().getString()).withColor(tone & 0xFFFFFF), cx, top + 160, 0.95f, 0xFFFFFFFF, true);
        // The level track: where it is, where it can go (your level + 3).
        boolean capped = g.level() >= g.cap();
        String lv = capped ? "Lv " + g.level() : "Lv " + g.level() + "  →  " + (g.level() + 1);
        Ui.text(c, Ui.heading(lv), cx, top + 176, 1.3f, capped ? Ui.GOLD : 0xFFFFB060, true);
        int bx = px + 16, bw = pw - 32, by = top + 198;
        c.fill(bx, by, bx + bw, by + 6, 0xFF1A1512);
        float frac = MathHelper.clamp(g.level() / (float) g.cap(), 0, 1);
        c.fillGradient(bx, by, bx + (int) (bw * frac), by + 6, 0xFFFF8A3A, 0xFFF2C14E);
        c.drawBorder(bx - 1, by - 1, bw + 2, 8, 0xFF5A4630);
        String capText = "Cap Lv " + g.cap();
        Ui.text(c, Text.literal(capText), bx + bw - Ui.font().getWidth(capText) * 0.65f, by + 10, 0.65f, Ui.CREAM, false);
        Ui.text(c, Text.literal("Perfect strikes: +2"), bx, by + 10, 0.65f, 0xFFF2C14E, false);
    }

    private void recipes(DrawContext c, Net.ForgeView v) {
        int y = top + 34;
        for (Net.ForgeRecipe r : v.recipes()) {
            if (y > top + h - 34) break;
            c.fill(left + 8, y, left + w - 8, y + 32, r.ready() ? 0xC0201A14 : 0x90100C0A);
            c.fill(left + 8, y, left + 10, y + 32, r.ready() ? 0xFFFF8A3A : 0xFF34322C);
            Ui.text(c, Ui.heading(r.title()), left + 16, y + 6, 0.95f, r.ready() ? Ui.CREAM : Ui.DIM, false);
            Ui.text(c, Text.literal(String.format(Locale.ROOT, "%,d M", r.marks()) + "  ·  " + r.materials()), left + 16, y + 19, 0.62f, r.ready() ? Ui.GOLD : Ui.RED, false);
            y += 36;
        }
    }
}
