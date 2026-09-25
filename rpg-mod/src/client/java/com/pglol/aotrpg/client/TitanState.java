package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/** Titan shifting (hand the hotbar to the shifter UI) and being grabbed (the escape prompt). */
public final class TitanState {
    private TitanState() {}

    private static final int NEEDED = 12;
    private static boolean wasDown;
    private static int strikes, decay;

    private static boolean aot(Entity e) {
        return e != null && Registries.ENTITY_TYPE.getId(e.getType()).getNamespace().equals("dannys-aot");
    }

    /** Playing as a titan: riding and steering one, or seeing through one. */
    public static boolean shifted() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;
        Entity v = mc.player.getVehicle();
        if (aot(v) && v.getControllingPassenger() == mc.player) return true;
        Entity cam = mc.getCameraEntity();
        return cam != null && cam != mc.player && aot(cam);
    }

    /** Held by a titan that someone else (or its AI) controls. */
    public static boolean grabbed() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;
        Entity v = mc.player.getVehicle();
        return aot(v) && !(v instanceof AbstractHorseEntity) && v.getControllingPassenger() != mc.player;
    }

    public static void tick(MinecraftClient mc) {
        boolean down = mc.options.attackKey.isPressed() && mc.currentScreen == null;
        boolean click = down && !wasDown;
        wasDown = down;
        if (!grabbed()) {
            strikes = 0;
            return;
        }
        if (click) {
            strikes = Math.min(NEEDED, strikes + 1);
            ClientPlayNetworking.send(new Net.Struggle());
        }
        if (++decay >= 10) {
            decay = 0;
            if (strikes > 0) strikes--;
        }
    }

    /** The big prompt while grabbed. */
    public static void renderHud(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || !grabbed()) return;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        long now = Util.getMeasuringTimeMs();
        float pulse = (float) (0.5 + 0.5 * Math.sin(now / 90.0));
        int pw = 220, ph = 58, x = (w - pw) / 2, y = h / 2 + 28;
        Ui.panel(c, x, y, pw, ph);
        c.drawBorder(x - 1, y - 1, pw + 2, ph + 2, ((int) (120 + 135 * pulse) << 24) | 0xC0302A);
        Ui.text(c, Ui.title("GRABBED!"), w / 2f, y + 6, 1.3f, 0xFFE04A3A, true);
        String key = mc.options.attackKey.getBoundKeyLocalizedText().getString();
        Ui.text(c, Text.literal("Spam [" + key + "] to strike its eye!"), w / 2f, y + 24, 1f, Ui.CREAM, true);
        Ui.bar(c, x + 14, y + 40, pw - 28, 8, strikes / (float) NEEDED, 0xFFE0B96A);
        // A little mouse-button glyph that flashes with each beat.
        int mx = x + pw - 22, my = y + 6;
        c.fill(mx, my, mx + 12, my + 16, 0xFF2A2A26);
        c.fill(mx, my, mx + 6, my + 7, (pulse > 0.5f ? 0xFFE0B96A : 0xFF6A5A3A));
        c.drawBorder(mx, my, 12, 16, Ui.TRIM);
    }
}
