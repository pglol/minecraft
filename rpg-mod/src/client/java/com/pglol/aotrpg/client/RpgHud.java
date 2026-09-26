package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/** Top-right character panel: name, level, health, stamina, food and XP. */
public final class RpgHud {
    private RpgHud() {}

    private static final int W = 150;

    /** True when the RPG HUD replaces the vanilla hearts/hunger/XP. */
    public static boolean active() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return ClientState.profile != null && mc.player != null && mc.interactionManager != null
            && mc.interactionManager.hasStatusBars();
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Net.Sync p = ClientState.profile;
        ClientPlayerEntity pl = mc.player;
        if (p == null || pl == null || mc.currentScreen != null || mc.options.hudHidden || mc.getDebugHud().shouldShowDebugHud()) return;
        boolean bars = active();

        int x = c.getScaledWindowWidth() - W - 4;
        int y = 4;
        // Stay below the vanilla status-effect icons in the corner.
        boolean good = false, bad = false;
        for (StatusEffectInstance e : pl.getStatusEffects()) {
            if (!e.shouldShowIcon()) continue;
            if (e.getEffectType().value().isBeneficial()) good = true;
            else bad = true;
        }
        if (bad) y = 54;
        else if (good) y = 29;

        int h = bars ? 66 : 30;
        long now = Util.getMeasuringTimeMs();
        Ui.panel(c, x, y, W, h);
        float hpFrac = pl.getHealth() / pl.getMaxHealth();
        if (bars && hpFrac < 0.3f) {
            int a = (int) (80 + 70 * Math.sin(now / 150.0));
            c.drawBorder(x, y, W, h, (a << 24) | 0xC0302A);
        }

        // Name and level
        Text name = Ui.heading(p.name());
        c.drawTextWithShadow(Ui.font(), name, x + 6, y + 5, Ui.CREAM);
        Text lv = Ui.title("Lv " + p.level());
        c.drawTextWithShadow(Ui.font(), lv, x + W - 6 - Ui.font().getWidth(lv), y + 5, Ui.GOLD);
        String disc = p.role().tag() + " " + p.role().title;
        c.drawTextWithShadow(Ui.font(), Text.literal(disc), x + 6, y + 16, p.role().color);
        c.drawTextWithShadow(Ui.font(), Text.literal(" · " + p.originEnum().title),
            x + 6 + Ui.font().getWidth(disc), y + 16, Ui.MUTED);

        if (!bars) {
            Ui.bar(c, x + 6, y + 26, W - 12, 3, p.need() > 0 ? (float) p.xp() / p.need() : 1, Ui.XP);
            return;
        }

        int bx = x + 22, bw = W - 28;
        // Health
        label(c, "HP", x + 6, y + 29, Ui.HP);
        Ui.bar(c, bx, y + 28, bw, 9, hpFrac, Ui.HP);
        float abs = pl.getAbsorptionAmount();
        if (abs > 0) c.fill(bx + 1, y + 29, bx + 1 + Math.round((bw - 2) * Math.min(1, abs / pl.getMaxHealth())), y + 31, 0xFFE8C84A);
        center(c, Math.round(pl.getHealth()) + " / " + Math.round(pl.getMaxHealth()) + (abs > 0 ? " +" + Math.round(abs) : ""), bx + bw / 2, y + 29);

        // Stamina
        float st = ClientState.stamina < 0 ? ClientState.maxStamina : ClientState.stamina;
        int stColor = ClientState.exhausted ? ((now / 200) % 2 == 0 ? 0xFFC0463A : 0xFF7A2A22)
            : st / ClientState.maxStamina < 0.3f ? 0xFFD0A040 : Ui.STAMINA;
        label(c, "ST", x + 6, y + 40, stColor);
        Ui.bar(c, bx, y + 39, bw, 9, st / ClientState.maxStamina, stColor);
        center(c, ClientState.exhausted ? "EXHAUSTED" : Math.round(st) + " / " + Math.round(ClientState.maxStamina), bx + bw / 2, y + 40);

        // Food and armour on one line
        int food = pl.getHungerManager().getFoodLevel();
        boolean hungry = food <= 6;
        int foodColor = hungry ? ((now / 300) % 2 == 0 ? 0xFFD04A3A : 0xFF8A2A20) : Ui.FOOD;
        label(c, "FD", x + 6, y + 50, foodColor);
        int fw = bw - 34;
        Ui.bar(c, bx, y + 51, fw, 5, food / 20f, foodColor);
        if (hungry) {
            String warn = food <= 2 ? "STARVING" : "HUNGRY";
            c.drawTextWithShadow(Ui.font(), Text.literal(warn), x + W - Ui.font().getWidth(warn), y + h + 3 + (p.points() > 0 || p.skillPoints() > 0 ? 10 : 0), 0xFFD04A3A);
        }
        String armor = "⛨ " + pl.getArmor();
        c.drawTextWithShadow(Ui.font(), Text.literal(armor), x + W - 6 - Ui.font().getWidth(armor), y + 50, Ui.CREAM);

        // Air, only under water
        int air = pl.getAir(), maxAir = pl.getMaxAir();
        if (air < maxAir) Ui.bar(c, x + 6, y + 57, W - 12, 3, Math.max(0, air) / (float) maxAir, Ui.AIR);

        // XP along the bottom edge
        Ui.bar(c, x + 6, y + h - 5, W - 12, 3, p.need() > 0 ? (float) p.xp() / p.need() : 1, Ui.XP);

        // Unspent points reminder
        if (p.points() > 0 || p.skillPoints() > 0) {
            String hint = "Points to spend — press K";
            int a = (int) (160 + 90 * Math.sin(now / 300.0));
            c.drawTextWithShadow(Ui.font(), Text.literal(hint), x + W - Ui.font().getWidth(hint), y + h + 3, (a << 24) | 0xE0B96A);
        }
    }

    private static void label(DrawContext c, String s, int x, int y, int color) {
        c.drawTextWithShadow(Ui.font(), Text.literal(s), x, y, color);
    }

    private static void center(DrawContext c, String s, int cx, int y) {
        Text t = Text.literal(s);
        c.drawText(Ui.font(), t, cx - Ui.font().getWidth(t) / 2, y, 0xFFFFFFFF, true);
    }
}
