package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Loadout;
import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

/**
 * The combat hotbar, split down the middle: the combat wing (Melee, Ranged, Sidearm, Tool) on the
 * left and the support wing (Mount, Signal, two free slots) on the right, both pointing in at the
 * Heal slot in the centre. The ODM sheath / off hand sits at the outer left end.
 */
public final class CombatHotbar {
    private CombatHotbar() {}

    public static Net.HealInfo heal;
    public static long healAt;
    private static int lastSlot = -1;
    private static long switchedAt;

    private static final int S = 20, GAP = 1, HEAL = 26;

    /** Off while shifted into a titan, so the shifter's own hotbar shows instead. */
    public static boolean active() {
        return RpgHud.active() && !TitanState.shifted();
    }

    /** Screen x of hotbar slot i (0-8); the heal slot is wider. */
    public static int slotX(int w, int i) {
        int cx = w / 2;
        if (i == Loadout.HEAL_SLOT) return cx - HEAL / 2;
        if (i < Loadout.HEAL_SLOT) return cx - HEAL / 2 - 7 - (Loadout.HEAL_SLOT - i) * (S + GAP);
        return cx + HEAL / 2 + 7 + (i - Loadout.HEAL_SLOT - 1) * (S + GAP);
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity pl = mc.player;
        if (pl == null) return;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        int y0 = h - 23;
        int sel = pl.getInventory().selectedSlot;
        long now = Util.getMeasuringTimeMs();
        if (sel != lastSlot) {
            lastSlot = sel;
            switchedAt = now;
        }

        // Wing plates with inward points.
        int lx0 = slotX(w, 0) - 3, lx1 = slotX(w, 3) + S + 2;
        int rx0 = slotX(w, 5) - 2, rx1 = slotX(w, 8) + S + 3;
        c.fill(lx0, y0 - 1, lx1, y0 + S + 2, 0xC80B0F0C);
        c.fill(rx0, y0 - 1, rx1, y0 + S + 2, 0xC80B0F0C);
        LoadoutUi.chevron(c, lx1, y0 - 1, S + 3, 1, 0xC80B0F0C);
        LoadoutUi.chevron(c, rx0, y0 - 1, S + 3, -1, 0xC80B0F0C);
        c.fill(lx0, y0 - 2, lx1, y0 - 1, 0xFFC0463A);
        c.fill(rx0, y0 - 2, rx1, y0 - 1, 0xFF8F8A7A);
        c.fill(lx0, y0 + S + 2, lx1, y0 + S + 3, 0x807A6139);
        c.fill(rx0, y0 + S + 2, rx1, y0 + S + 3, 0x807A6139);

        for (int i = 0; i < 9; i++) {
            if (i == Loadout.HEAL_SLOT) continue;
            drawSlot(c, mc, pl, i, slotX(w, i), y0, i == sel, now);
        }
        drawHeal(c, mc, pl, slotX(w, Loadout.HEAL_SLOT), h - HEAL - 3, sel == Loadout.HEAL_SLOT);
        drawSheath(c, mc, pl, lx0 - 25, y0);

        // What the selected slot is for, shown briefly after switching.
        long since = now - switchedAt;
        if (since < 1400) {
            int a = (int) (255 * Math.min(1, (1400 - since) / 400.0));
            Loadout.Kind k = Loadout.SLOTS[sel];
            if (a > 8) {
                int sx = slotX(w, sel) + (sel == Loadout.HEAL_SLOT ? HEAL : S) / 2;
                Ui.text(c, Ui.heading(k.title), sx, (sel == Loadout.HEAL_SLOT ? h - HEAL - 3 : y0) - 22, 0.75f,
                    (a << 24) | (LoadoutUi.color(k) & 0xFFFFFF), true);
            }
        }
    }

    private static void drawSlot(DrawContext c, MinecraftClient mc, ClientPlayerEntity pl, int i, int x, int y, boolean sel, long now) {
        Loadout.Kind k = Loadout.SLOTS[i];
        int col = LoadoutUi.color(k);
        int lift = sel ? 2 : 0;
        y -= lift;
        if (sel) {
            float pulse = (float) (0.5 + 0.5 * Math.sin(now / 220.0));
            c.fill(x - 2, y - 2, x + S + 2, y + S + 2, ((int) (60 + 60 * pulse) << 24) | (col & 0xFFFFFF));
        }
        c.fill(x, y, x + S, y + S, sel ? 0xF0182018 : 0xE0121612);
        c.drawBorder(x, y, S, S, (sel ? 0xFF : 0x80) << 24 | (col & 0xFFFFFF));
        c.fill(x + 1, y + S - 2, x + S - 1, y + S - 1, (sel ? 0xFF : 0xA0) << 24 | (col & 0xFFFFFF));
        if (sel) c.drawBorder(x - 1, y - 1, S + 2, S + 2, 0xFFEDE3C8);
        ItemStack st = pl.getInventory().main.get(i);
        if (!st.isEmpty()) {
            c.drawItem(pl, st, x + 2, y + 2, i + 1);
            c.drawItemInSlot(mc.textRenderer, st, x + 2, y + 2);
        } else {
            LoadoutUi.drawGhost(c, LoadoutUi.ghost(k), x + 2, y + 2);
        }
    }

    private static void drawHeal(DrawContext c, MinecraftClient mc, ClientPlayerEntity pl, int x, int y, boolean sel) {
        int col = LoadoutUi.color(Loadout.Kind.HEAL);
        // Crest-like frame: dark disc, green ring, gold trim.
        c.fill(x - 2, y - 2, x + HEAL + 2, y + HEAL + 2, 0xD00B0F0C);
        c.drawBorder(x - 2, y - 2, HEAL + 4, HEAL + 4, Ui.TRIM);
        c.drawBorder(x, y, HEAL, HEAL, sel ? 0xFFEDE3C8 : col);
        c.fill(x + 1, y + 1, x + HEAL - 1, y + HEAL - 1, sel ? 0x50205A20 : 0x30205A20);
        ItemStack st = pl.getInventory().main.get(Loadout.HEAL_SLOT);
        Net.HealInfo hi = heal;
        int ix = x + (HEAL - 16) / 2, iy = y + (HEAL - 16) / 2;
        if (!st.isEmpty()) {
            c.drawItem(pl, st, ix, iy, 99);
            c.drawItemInSlot(mc.textRenderer, st, ix, iy);
        } else if (hi != null && hi.count() > 0 && !hi.item().isEmpty()) {
            // Nothing in the slot: show what [H] would use from your bags.
            c.drawItem(Registries.ITEM.get(Identifier.of(hi.item())).getDefaultStack(), ix, iy);
        } else {
            LoadoutUi.drawGhost(c, LoadoutUi.ghost(Loadout.Kind.HEAL), ix, iy);
        }
        if (hi != null && hi.cooldown() > 0 && hi.cooldownMax() > 0) {
            float left = Math.max(0, hi.cooldown() - (Util.getMeasuringTimeMs() - healAt) / 50f) / hi.cooldownMax();
            if (left > 0) {
                c.getMatrices().push();
                c.getMatrices().translate(0, 0, 250);
                int ch = Math.round((HEAL - 2) * left);
                c.fill(x + 1, y + HEAL - 1 - ch, x + HEAL - 1, y + HEAL - 1, 0xB0000000);
                c.getMatrices().pop();
            }
        }
        // Key tag
        String k = AotRpgClient.healKey().getBoundKeyLocalizedText().getString().toUpperCase();
        if (k.length() > 5) k = k.substring(0, 5);
        int kw = mc.textRenderer.getWidth(k) + 6;
        int kx = x + HEAL / 2 - kw / 2, ky = y - 12;
        c.getMatrices().push();
        c.getMatrices().translate(0, 0, 260);
        c.fill(kx, ky, kx + kw, ky + 10, 0xF0151A16);
        c.drawBorder(kx, ky, kw, 10, Ui.TRIM);
        c.drawText(mc.textRenderer, k, kx + 3, ky + 1, Ui.CREAM, false);
        c.getMatrices().pop();
    }

    /** Outer left: the off hand, or the sheathed twin grip when the off hand is free. */
    private static void drawSheath(DrawContext c, MinecraftClient mc, ClientPlayerEntity pl, int x, int y) {
        Net.SheathState st = ClientState.sheaths.get(pl.getUuid());
        ItemStack off = pl.getOffHandStack();
        boolean sheathed = st != null && st.count() > 0 && !st.item().isEmpty();
        if (off.isEmpty() && !sheathed) return;
        boolean drawn = com.pglol.aotrpg.Loadout.isGrip(off);
        int col = drawn ? 0xFFC0463A : sheathed ? Ui.GOLD : 0xFF8F8A7A;
        c.fill(x, y, x + 22, y + S, 0xD00B0F0C);
        c.drawBorder(x, y, 22, S, col);
        if (!off.isEmpty()) {
            c.drawItem(pl, off, x + 3, y + 2, 77);
            c.drawItemInSlot(mc.textRenderer, off, x + 3, y + 2);
        } else {
            LoadoutUi.drawGhost(c, Registries.ITEM.get(Identifier.of(st.item())).getDefaultStack(), x + 3, y + 2);
        }
        String key = AotRpgClient.sheathKey().getBoundKeyLocalizedText().getString().toUpperCase();
        String label = drawn ? "DRAWN " + key : sheathed ? "SHEATHED " + key : "OFF";
        Ui.text(c, Text.literal(label), x + 11, y - 7, 0.5f, col, true);
    }
}
