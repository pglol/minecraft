package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.Locale;

/**
 * The Extraction deployment board: pick a zone on the left, see your squad, Salvage and stash on
 * the right, and drop in. Zones glow hotter the higher their level.
 */
public final class ExtractionScreen extends Screen {
    private static Net.ExtractionView v;
    private static String chosen = "";
    private int left, top, w, h, scroll;
    private final long opened = Util.getMeasuringTimeMs();
    private static final int CARD = 26;

    public ExtractionScreen(Net.ExtractionView view) {
        super(Text.literal("Extraction"));
        v = view;
        if (chosen.isEmpty() && !view.zones().isEmpty()) chosen = view.zones().get(0).id();
    }

    /** A fresh view while the board is open (after buying stash space). */
    public static void update(Net.ExtractionView view) {
        v = view;
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.currentScreen instanceof ExtractionScreen s) s.clearAndInit();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void act(String a, String arg) {
        ClientPlayNetworking.send(new Net.ExtractionAction(a, arg));
    }

    private int listX() { return left + 12; }
    private int listY() { return top + 50; }
    private int listW() { return w * 55 / 100; }
    private int listH() { return h - 96; }

    @Override
    protected void init() {
        w = Math.min(440, width - 20);
        h = Math.min(290, height - 20);
        left = (width - w) / 2;
        top = (height - h) / 2;
        int rx = left + listW() + 24, rw = w - listW() - 36;
        int sy = top + 132;
        addDrawableChild(new AotButton(rx, sy + 30, rw, 20, Ui.heading("Open Stash"), () -> act("stash", "")))
            .icon(new ItemStack(Items.ENDER_CHEST));
        String cost = v.rowCost() < 0 ? "Stash full size" : "+9 slots · " + String.format(Locale.ROOT, "%,d", v.rowCost());
        AotButton ex = addDrawableChild(new AotButton(rx, sy + 54, rw, 20, Text.literal(cost), () -> act("expand", "")));
        ex.icon(salvageIcon());
        ex.active = v.rowCost() >= 0 && v.salvage() >= v.rowCost();
        AotButton go = addDrawableChild(new AotButton(left + w - 150, top + h - 36, 138, 26, Ui.heading(v.leader() ? "DEPLOY" : "Leader deploys"), () -> {
            act("deploy", chosen);
            close();
        }));
        go.selected(true);
        go.accent = 0xFFE03A3A;
        go.active = v.leader() && !chosen.isEmpty();
    }

    private static ItemStack salvageIcon() {
        var item = Registries.ITEM.get(Identifier.of("aot_rpg", "gas_refueler"));
        return new ItemStack(item == Items.AIR ? Items.IRON_NUGGET : item);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        int max = Math.max(0, v.zones().size() * (CARD + 3) - listH());
        scroll = (int) Math.max(0, Math.min(max, scroll - vy * 16));
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (mx >= listX() && mx < listX() + listW() && my >= listY() && my < listY() + listH()) {
            int i = (int) ((my - listY() + scroll) / (CARD + 3));
            if (i >= 0 && i < v.zones().size()) {
                chosen = v.zones().get(i).id();
                if (client != null && client.player != null) client.player.playSound(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.3f);
                clearAndInit();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    /** Level heat: green for easy, through gold and orange, to red. */
    private static int heat(int level) {
        if (level < 8) return 0xFF5BD35B;
        if (level < 16) return 0xFFE0B96A;
        if (level < 26) return 0xFFE0823A;
        return 0xFFE03A3A;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0xA0100404, 0xD0000000);
        long t = Util.getMeasuringTimeMs();
        float k = Math.min(1, (t - opened) / 220f);
        int slide = (int) ((1 - k) * 14);
        int y0 = top + slide;
        Ui.panel(c, left, y0, w, h);
        // The header: a red band that breathes.
        int pulse = (int) (60 + 40 * Math.sin(t * 0.004));
        c.fillGradient(left + 2, y0 + 2, left + w - 2, y0 + 38, (pulse << 24) | 0xB02020, 0x00000000);
        Ui.text(c, Ui.title("EXTRACTION"), left + 14, y0 + 12, 1.6f, 0xFFF0E0C0, false);
        // Salvage, top right.
        String sv = String.format(Locale.ROOT, "%,d", v.salvage());
        int sw = (int) (Ui.font().getWidth(sv) * 1.1f);
        Ui.item(c, salvageIcon(), left + w - 30, y0 + 10, 1f);
        Ui.text(c, Text.literal(sv), left + w - 34 - sw, y0 + 15, 1.1f, 0xFF9AD0FF, false);
        Ui.divider(c, left + 10, y0 + 40, w - 20);

        // Zones.
        int lx = listX(), ly = listY() + slide, lw = listW(), lh = listH();
        c.enableScissor(lx, ly, lx + lw, ly + lh);
        int y = ly - scroll;
        for (Net.ExtractionZone z : v.zones()) {
            if (y + CARD >= ly && y <= ly + lh) {
                boolean sel = z.id().equals(chosen);
                boolean hov = mouseX >= lx && mouseX < lx + lw && mouseY >= y && mouseY < y + CARD;
                int lvl = Math.max(1, (z.min() + z.max()) / 2), col = heat(lvl);
                c.fill(lx, y, lx + lw, y + CARD, sel ? 0xE8341818 : hov ? 0xD8221A16 : 0xC8141110);
                if (sel) {
                    int a = (int) (120 + 80 * Math.sin(t * 0.008));
                    c.drawBorder(lx, y, lw, CARD, (a << 24) | 0xE03A3A);
                }
                c.fill(lx, y, lx + 3, y + CARD, col);
                Ui.text(c, Text.literal(z.name()), lx + 10, y + 9, 0.95f, sel ? 0xFFFFFFFF : Ui.CREAM, false);
                String lv = z.min() == z.max() ? "Lv " + z.min() : "Lv " + z.min() + "-" + z.max();
                int bw = (int) (Ui.font().getWidth(lv) * 0.8f) + 10;
                c.fill(lx + lw - bw - 6, y + 6, lx + lw - 6, y + CARD - 6, (col & 0xFFFFFF) | 0x50000000);
                Ui.text(c, Text.literal(lv), lx + lw - bw / 2f - 6, y + 10, 0.8f, col, true);
            }
            y += CARD + 3;
        }
        c.disableScissor();
        if (v.zones().isEmpty()) Ui.text(c, Text.literal("No zones"), lx + lw / 2f, ly + 20, 1f, Ui.MUTED, true);

        // Squad.
        int rx = left + listW() + 24, rw = w - listW() - 36;
        Ui.text(c, Ui.heading("Squad"), rx, top + 50 + slide, 0.9f, Ui.GOLD, false);
        int sy = top + 64 + slide;
        for (int i = 0; i < v.squad().size() && i < 4; i++) {
            c.fill(rx, sy, rx + rw, sy + 14, 0x40000000);
            c.fill(rx + 4, sy + 5, rx + 8, sy + 9, 0xFF5BD35B);
            Ui.text(c, Text.literal(v.squad().get(i)), rx + 13, sy + 3, 0.8f, Ui.CREAM, false);
            sy += 16;
        }
        // Stash.
        int st = top + 132 + slide;
        Ui.text(c, Ui.heading("Stash"), rx, st, 0.9f, Ui.GOLD, false);
        String cap = v.stashUsed() + " / " + v.stashCap();
        Ui.text(c, Text.literal(cap), rx + rw - Ui.font().getWidth(cap) * 0.8f, st + 1, 0.8f, Ui.CREAM, false);
        float frac = v.stashCap() == 0 ? 0 : v.stashUsed() / (float) v.stashCap();
        Ui.bar(c, rx, st + 14, rw, 6, frac, frac > 0.9f ? 0xFFE03A3A : 0xFF6FB6E0);

        // The chosen zone, big, by the deploy button.
        for (Net.ExtractionZone z : v.zones()) {
            if (!z.id().equals(chosen)) continue;
            int lvl = Math.max(1, (z.min() + z.max()) / 2);
            Ui.text(c, Ui.title(z.name()), left + 14, top + h - 32 + slide, 1.2f, heat(lvl), false);
        }
    }
}
