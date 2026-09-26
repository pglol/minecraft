package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.texture.Sprite;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Effects on you, as small cards down the top right: the effect's icon, its name and strength,
 * and the time left. A stripe down the left says whether it helps (green) or hurts (red).
 * Replaces the vanilla effect icons.
 */
public final class EffectCards {
    private EffectCards() {}

    private static final int W = 104, H = 22, GAP = 3, MAX = 4;
    /** Where the cards end, for toasts that stack below them. */
    public static int bottom = 4;

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        bottom = 4;
        if (mc.player == null || mc.options.hudHidden || mc.getDebugHud().shouldShowDebugHud()) return;
        List<StatusEffectInstance> list = new ArrayList<>();
        for (StatusEffectInstance e : mc.player.getStatusEffects()) if (e.shouldShowIcon()) list.add(e);
        if (list.isEmpty()) return;
        // Harmful first (they matter most), then the shortest.
        list.sort(Comparator.<StatusEffectInstance>comparingInt(e -> e.getEffectType().value().isBeneficial() ? 1 : 0)
            .thenComparingInt(e -> e.isInfinite() ? Integer.MAX_VALUE : e.getDuration()));
        int w = c.getScaledWindowWidth();
        int x = w - W - 4, y = 4;
        long now = Util.getMeasuringTimeMs();
        var font = mc.textRenderer;
        for (int i = 0; i < Math.min(MAX, list.size()); i++) {
            StatusEffectInstance e = list.get(i);
            StatusEffect type = e.getEffectType().value();
            int stripe = type.getCategory() == StatusEffectCategory.HARMFUL ? 0xFFB84A3A
                : type.getCategory() == StatusEffectCategory.BENEFICIAL ? 0xFF8FAF7A : 0xFFB8955A;
            boolean ending = !e.isInfinite() && e.getDuration() < 200;
            if (ending && (now / 250) % 2 == 0) stripe = stripe & 0xFFFFFF | 0x60000000;

            c.fill(x, y, x + W, y + H, 0xB80D0F0D);
            c.fill(x, y, x + 2, y + H, stripe);
            c.fill(x + 2, y + H, x + W, y + H + 1, 0x40B8955A);

            Sprite sprite = mc.getStatusEffectSpriteManager().getSprite(e.getEffectType());
            c.drawSprite(x + 5, y + 2, 0, 18, 18, sprite);

            int tx = x + 27, tw = W - 27 - 4;
            Text name = type.getName().copy();
            if (e.getAmplifier() > 0) name = name.copy().append(" " + roman(e.getAmplifier() + 1));
            String n = font.trimToWidth(name.getString(), tw);
            c.drawText(font, n, tx, y + 3, Ui.CREAM, false);
            String time = e.isInfinite() ? "∞" : clock(e.getDuration());
            c.drawText(font, time, tx, y + 12, ending ? 0xFFE07A6A : Ui.MUTED, false);
            y += H + GAP;
        }
        if (list.size() > MAX) {
            String more = "+" + (list.size() - MAX) + " more";
            c.drawText(font, more, x + W - font.getWidth(more) - 2, y, Ui.MUTED, true);
            y += 10;
        }
        bottom = y + 2;
    }

    private static String clock(int ticks) {
        int s = ticks / 20;
        if (s >= 3600) return s / 3600 + "h " + s % 3600 / 60 + "m";
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    private static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n < r.length ? r[n] : String.valueOf(n);
    }
}
