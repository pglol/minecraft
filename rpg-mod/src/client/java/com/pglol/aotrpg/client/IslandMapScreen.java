package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * The island's own map (M out on a run): a sea chart of the island with its edge, the flares,
 * the hot zones, the objectives and your squad, each named, and you with where you're facing.
 */
public final class IslandMapScreen extends Screen {
    private final long opened = Util.getMeasuringTimeMs();

    public IslandMapScreen() {
        super(Text.literal("Island"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0xE0081018, 0xF0040608);
        Net.RunView v = ExtractionHud.view;
        if (v == null || client == null || client.player == null) return;
        long t = Util.getMeasuringTimeMs();
        float in = Math.min(1, (t - opened) / 250f);
        int R = (int) (Math.min(width, height) * 0.40f * (0.94f + 0.06f * in)), cx = width / 2, cy = height / 2 + 8;
        double range = v.radius() * 1.15;
        // The sea, the island's edge, a compass grid.
        ExtractionHud.disc(c, cx, cy, R, 0xC0102030);
        int ir = (int) (v.radius() / range * R);
        ExtractionHud.disc(c, cx, cy, ir, (v.color() & 0xFFFFFF) | 0x30000000);
        ExtractionHud.ring(c, cx, cy, ir, (v.color() & 0xFFFFFF) | 0xC0000000);
        ExtractionHud.ring(c, cx, cy, R, 0xFF7A6139);
        for (int g = -3; g <= 3; g++) {
            int o = g * R / 4;
            c.fill(cx + o, cy - R + 6, cx + o + 1, cy + R - 6, 0x18FFFFFF);
            c.fill(cx - R + 6, cy + o, cx + R - 6, cy + o + 1, 0x18FFFFFF);
        }
        Ui.text(c, Ui.title(v.name().toUpperCase()), cx, 14, 1.6f, v.color(), true);
        int left = ExtractionHud.secondsLeft();
        String sub = String.format("%d:%02d left", left / 60, left % 60) + (v.matchMinutes() > 0 ? "   ·   match " + v.matchMinutes() + " min in" : "");
        Ui.text(c, Text.literal(sub), cx, 34, 0.85f, left < 120 ? 0xFFE03A3A : Ui.CREAM, true);
        Ui.text(c, Text.literal("N"), cx, cy - R - 10, 0.9f, Ui.GOLD, true);
        // Everything on it, named.
        for (Net.RunPoint pt : v.points()) {
            int x = cx + (int) ((pt.x() - v.cx()) / range * R), y = cy + (int) ((pt.z() - v.cz()) / range * R);
            int col = pt.done() ? 0xFF55524A : ExtractionHud.color(pt.kind());
            int s = pt.kind().equals("mate") ? 3 : 4;
            if (pt.kind().equals("exit")) {
                float pulse = 0.5f + 0.5f * MathHelper.sin(t * 0.006f);
                c.fill(x - 7, y - 7, x + 8, y + 8, ((int) (90 * pulse) << 24) | (col & 0xFFFFFF));
            }
            c.fill(x - s - 1, y - s - 1, x + s + 2, y + s + 2, 0xFF000000);
            c.fill(x - s, y - s, x + s + 1, y + s + 1, col);
            String label = pt.kind().equals("exit") ? "Flare" : pt.name() + (pt.done() ? "  ✔" : "");
            Ui.text(c, Text.literal(label), x, y + 8, 0.7f, col, true);
        }
        // You.
        var p = client.player;
        int x = cx + (int) ((p.getX() - v.cx()) / range * R), y = cy + (int) ((p.getZ() - v.cz()) / range * R);
        double yaw = Math.toRadians(p.getYaw());
        for (int i = 0; i < 9; i++) c.fill(x + (int) (-Math.sin(yaw) * i), y + (int) (Math.cos(yaw) * i), x + (int) (-Math.sin(yaw) * i) + 2,
            y + (int) (Math.cos(yaw) * i) + 2, 0xFFFFFFFF);
        c.fill(x - 2, y - 2, x + 3, y + 3, 0xFFFFFFFF);
        Ui.text(c, Text.literal("You"), x, y - 12, 0.7f, 0xFFFFFFFF, true);
        // Legend.
        String[][] legend = {{"exit", "Flare: extract"}, {"obj", "Objective"}, {"poi", "Hot zone"}, {"mate", "Squad"}};
        int ly = height - 18;
        int lx = cx - 170;
        for (String[] l : legend) {
            c.fill(lx, ly + 1, lx + 6, ly + 7, ExtractionHud.color(l[0]));
            Ui.text(c, Text.literal(l[1]), lx + 10, ly, 0.75f, Ui.CREAM, false);
            lx += 90;
        }
    }
}
