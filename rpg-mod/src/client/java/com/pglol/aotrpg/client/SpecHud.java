package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/**
 * Fallen on a run: watching whoever's still out there. Left and right click switch between them;
 * hold Space to go back to the balloon (you can ready up for the next one while they finish).
 */
public final class SpecHud {
    private SpecHud() {}

    private static Net.SpecView view;
    private static long holdSince;

    public static void on(Net.SpecView v) {
        view = v.active() ? v : null;
        holdSince = 0;
    }

    public static void tick(MinecraftClient mc) {
        if (view == null || mc.player == null) return;
        if (mc.currentScreen != null) {
            holdSince = 0;
            return;
        }
        while (mc.options.attackKey.wasPressed()) ClientPlayNetworking.send(new Net.ExtractionAction("spec_next", ""));
        while (mc.options.useKey.wasPressed()) ClientPlayNetworking.send(new Net.ExtractionAction("spec_prev", ""));
        if (mc.options.jumpKey.isPressed()) {
            if (holdSince == 0) holdSince = Util.getMeasuringTimeMs();
            else if (Util.getMeasuringTimeMs() - holdSince > 1000) {
                holdSince = 0;
                ClientPlayNetworking.send(new Net.ExtractionAction("spec_leave", ""));
            }
        } else holdSince = 0;
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (view == null || mc.player == null || mc.options.hudHidden) return;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        int bw = 240, x = w / 2 - bw / 2, y = h - 78;
        c.fill(x, y, x + bw, y + 34, 0xB0000000);
        c.fill(x, y, x + bw, y + 1, 0xFFA02020);
        Ui.text(c, Text.literal(view.whose().toUpperCase(java.util.Locale.ROOT)), w / 2f, y + 4, 0.6f, 0xFFE07A6A, true);
        Ui.text(c, Ui.heading("◀  " + view.watching() + "  ▶"), w / 2f, y + 13, 1f, Ui.CREAM, true);
        Ui.text(c, Text.literal("Click to switch · Hold Space: back to the balloon"), w / 2f, y + 25, 0.55f, Ui.GOLD, true);
        if (holdSince > 0) {
            float k = Math.min(1, (Util.getMeasuringTimeMs() - holdSince) / 1000f);
            c.fill(x, y + 33, x + (int) (bw * k), y + 34, 0xFFE0B96A);
        }
    }
}
