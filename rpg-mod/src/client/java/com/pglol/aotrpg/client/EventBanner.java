package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayDeque;

/**
 * The big moments of a match, across the top of the screen: a band slams in with a punch of
 * scale, the icon large, the name wide and bright in the event's colour, which way and how far
 * under it, rules sweeping out to the sides. A few seconds, then it lifts away.
 */
public final class EventBanner {
    private EventBanner() {}

    private static final ArrayDeque<Net.EventBanner> queue = new ArrayDeque<>();
    private static Net.EventBanner now;
    private static long at;
    private static final long SHOW = 4200;

    public static void on(Net.EventBanner b) {
        queue.add(b);
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        long t = Util.getMeasuringTimeMs();
        if (now == null || t - at > SHOW) {
            now = queue.poll();
            at = t;
            if (now == null) return;
        }
        if (mc.options.hudHidden && !com.pglol.aotrpg.client.story.CutscenePlayer.active()) return;
        float e = (t - at) / 1000f, total = SHOW / 1000f;
        float in = MathHelper.clamp(e / 0.25f, 0, 1), out = MathHelper.clamp((total - e) / 0.5f, 0, 1);
        float k = in * out;
        if (k <= 0.01f) return;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        int col = now.color() & 0xFFFFFF;
        int cy = (int) (h * 0.2f) - (int) ((1 - out) * 20);
        // The punch: a touch too big as it lands, settling.
        float punch = 1 + 0.25f * (1 - in) + 0.06f * (float) Math.max(0, Math.sin(Math.min(1, e / 0.45f) * Math.PI)) * (e < 0.45f ? 1 : 0);
        int a = (int) (255 * k);
        // The band: dark, fading out at the ends.
        int bw = (int) (w * 0.62f * Math.min(1, e / 0.35f + 0.2f));
        for (int i = 0; i < 24; i++) {
            float f = i / 24f;
            int x0 = w / 2 - bw / 2 + (int) (bw * f / 2), x1 = w / 2 + bw / 2 - (int) (bw * f / 2);
            c.fill(x0, cy - 18, x1, cy + 26, ((int) (a * 0.12f)) << 24);
        }
        // Rules sweeping out from the middle.
        int rw = (int) (bw / 2 * Math.min(1, e / 0.5f));
        c.fill(w / 2 - rw, cy - 19, w / 2 + rw, cy - 18, (a << 24) | col);
        c.fill(w / 2 - rw, cy + 26, w / 2 + rw, cy + 27, (a << 24) | col);
        var m = c.getMatrices();
        m.push();
        m.translate(w / 2f, cy + 2, 400);
        m.scale(punch, punch, 1);
        Text title = Ui.title(now.title());
        float tw = mc.textRenderer.getWidth(title) * 2.2f;
        ItemStack icon = new ItemStack(Registries.ITEM.get(Identifier.of(now.icon())));
        m.push();
        m.translate(-tw / 2 - 30, -12, 0);
        m.scale(1.7f, 1.7f, 1);
        c.drawItem(icon, 0, 0);
        m.pop();
        Ui.text(c, title, 0, -10, 2.2f, (a << 24) | col, true);
        Ui.text(c, Text.literal(now.sub()), 0, 12, 0.9f, (a << 24) | 0xF2EDE2, true);
        m.pop();
        // A flash on arrival.
        if (e < 0.15f) c.fill(0, 0, w, h, ((int) (60 * (1 - e / 0.15f))) << 24 | col);
    }
}
