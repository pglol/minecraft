package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Arm;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

/**
 * The combat hotbar. Same nine slots in the same place, grouped by importance:
 * 1-3 arms (red), 4-6 gear (gold), 7-9 utility (grey). The selected slot lifts and glows.
 * Above it on the right sits the quick-heal, with its key, count and cooldown.
 */
public final class CombatHotbar {
    private CombatHotbar() {}

    private static final int[] GROUP = {0xFFC0463A, 0xFFC0463A, 0xFFC0463A, 0xFFE0B96A, 0xFFE0B96A, 0xFFE0B96A, 0xFF8F8A7A, 0xFF8F8A7A, 0xFF8F8A7A};
    private static final String[] GROUP_NAME = {"Arms", "Gear", "Utility"};

    public static Net.HealInfo heal;
    public static long healAt;
    private static int lastSlot = -1;
    private static long switchedAt;

    public static boolean active() {
        return RpgHud.active();
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity pl = mc.player;
        if (pl == null) return;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        int x0 = w / 2 - 91, y0 = h - 22;
        int sel = pl.getInventory().selectedSlot;
        long now = Util.getMeasuringTimeMs();
        if (sel != lastSlot) {
            lastSlot = sel;
            switchedAt = now;
        }

        c.getMatrices().push();
        c.getMatrices().translate(0, 0, -90);
        // Base plate
        c.fill(x0 - 1, y0 - 1, x0 + 183, y0 + 22, 0xC00B0F0C);
        c.fill(x0 - 1, y0 - 2, x0 + 183, y0 - 1, Ui.BORDER);
        for (int i = 0; i < 9; i++) {
            int sx = x0 + 1 + i * 20;
            int col = GROUP[i];
            boolean s = i == sel;
            // Importance: the arms slots carry a stronger tint.
            int tint = i < 3 ? 0x40 : i < 6 ? 0x28 : 0x14;
            c.fill(sx, y0 + 1, sx + 20, y0 + 21, (tint << 24) | (col & 0xFFFFFF));
            c.drawBorder(sx, y0 + 1, 20, 20, (s ? 0xFF : 0x70) << 24 | (col & 0xFFFFFF));
            // Group underline
            c.fill(sx + 1, y0 + 19, sx + 19, y0 + 21, col);
            if (i == 3 || i == 6) c.fill(sx - 1, y0 - 1, sx, y0 + 22, 0xFF000000);
        }
        // Selected slot: lifted, bright frame, pulse.
        int sx = x0 + 1 + sel * 20;
        float pulse = (float) (0.5 + 0.5 * Math.sin(now / 220.0));
        int glow = (int) (60 + 60 * pulse);
        c.fill(sx - 2, y0 - 3, sx + 22, y0 + 22, (glow << 24) | (GROUP[sel] & 0xFFFFFF));
        c.fill(sx - 1, y0 - 2, sx + 21, y0 + 21, 0xF0151A16);
        c.drawBorder(sx - 2, y0 - 3, 24, 25, 0xFFEDE3C8);
        c.fill(sx + 1, y0 + 19, sx + 19, y0 + 21, GROUP[sel]);
        c.getMatrices().pop();

        // Items, numbers
        int seed = 1;
        for (int i = 0; i < 9; i++) {
            int ix = x0 + 3 + i * 20, iy = y0 + 2 - (i == sel ? 2 : 0);
            ItemStack st = pl.getInventory().main.get(i);
            if (!st.isEmpty()) {
                c.drawItem(pl, st, ix, iy, seed++);
                c.drawItemInSlot(mc.textRenderer, st, ix, iy);
            }
            c.getMatrices().push();
            c.getMatrices().translate(0, 0, 200);
            Ui.text(c, Text.literal(String.valueOf(i + 1)), x0 + 2 + i * 20, y0 + 1 - (i == sel ? 3 : 0), 0.5f,
                i == sel ? 0xFFEDE3C8 : 0xA0FFFFFF & GROUP[i] | 0x80000000, false);
            c.getMatrices().pop();
        }

        // Group name, shown briefly after switching.
        long since = now - switchedAt;
        if (since < 1400) {
            int a = (int) (255 * Math.min(1, (1400 - since) / 400.0));
            if (a > 8) {
                Text g = Ui.heading(GROUP_NAME[sel / 3]);
                int gx = x0 + 1 + (sel / 3) * 60 + 30;
                Ui.text(c, g, gx, y0 - 12, 0.75f, (a << 24) | (GROUP[sel] & 0xFFFFFF), true);
            }
        }

        // Offhand
        ItemStack off = pl.getOffHandStack();
        if (!off.isEmpty()) {
            boolean left = pl.getMainArm() == Arm.RIGHT;
            int ox = left ? x0 - 26 : x0 + 188;
            c.fill(ox, y0 + 1, ox + 22, y0 + 21, 0xC00B0F0C);
            c.drawBorder(ox, y0 + 1, 22, 20, 0x90B8955A);
            c.drawItem(pl, off, ox + 3, y0 + 3, seed++);
            c.drawItemInSlot(mc.textRenderer, off, ox + 3, y0 + 3);
        }

        drawHeal(c, mc, x0 + 183 + 4 + (off.isEmpty() || pl.getMainArm() == Arm.RIGHT ? 0 : 26), y0);
    }

    /** Quick-heal plate: right of the hotbar, raised, with key, item, count and cooldown. */
    private static void drawHeal(DrawContext c, MinecraftClient mc, int x, int y0) {
        Net.HealInfo hi = heal;
        int y = y0 - 6;
        int size = 26;
        boolean has = hi != null && hi.count() > 0 && !hi.item().isEmpty();
        c.fill(x, y, x + size, y + size + 1, 0xD00B0F0C);
        c.drawBorder(x, y, size, size + 1, has ? 0xFF5BD35B : 0xFF55524A);
        c.fill(x + 1, y + 1, x + size - 1, y + 2, has ? 0x805BD35B : 0x4055524A);
        if (has) {
            ItemStack st = Registries.ITEM.get(Identifier.of(hi.item())).getDefaultStack();
            c.drawItem(st, x + 5, y + 5);
            String n = hi.count() > 99 ? "99+" : String.valueOf(hi.count());
            c.getMatrices().push();
            c.getMatrices().translate(0, 0, 200);
            c.drawTextWithShadow(mc.textRenderer, n, x + size - 2 - mc.textRenderer.getWidth(n), y + size - 8, 0xFFFFFFFF);
            c.getMatrices().pop();
        } else {
            Ui.text(c, Text.literal("+"), x + size / 2f, y + 7, 1.4f, 0xFF55524A, true);
        }
        // Cooldown shade drains from the top.
        if (hi != null && hi.cooldown() > 0 && hi.cooldownMax() > 0) {
            float left = Math.max(0, hi.cooldown() - (Util.getMeasuringTimeMs() - healAt) / 50f) / hi.cooldownMax();
            if (left > 0) {
                c.getMatrices().push();
                c.getMatrices().translate(0, 0, 250);
                int ch = Math.round((size - 2) * left);
                c.fill(x + 1, y + size - ch, x + size - 1, y + size, 0xB0000000);
                c.getMatrices().pop();
            }
        }
        // Key tag on top
        Text key = AotRpgClient.healKey().getBoundKeyLocalizedText();
        String k = key.getString().toUpperCase();
        if (k.length() > 5) k = k.substring(0, 5);
        int kw = mc.textRenderer.getWidth(k) + 6;
        int kx = x + size / 2 - kw / 2, ky = y - 9;
        c.getMatrices().push();
        c.getMatrices().translate(0, 0, 250);
        c.fill(kx, ky, kx + kw, ky + 10, 0xF0151A16);
        c.drawBorder(kx, ky, kw, 10, 0xFFB8955A);
        c.drawText(mc.textRenderer, k, kx + 3, ky + 1, 0xFFEDE3C8, false);
        c.getMatrices().pop();
    }
}
