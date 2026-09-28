package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * The island's own map (M out on a run): a sea chart of the island with its edge, the flares,
 * the hot zones, the objectives, your squad and the squad's marks, each named, and you with where
 * you're facing. Drag to move it, scroll to zoom, right-click to mark a spot for the squad
 * (right-click your mark again to take it back).
 */
public final class IslandMapScreen extends Screen {
    private final long opened = Util.getMeasuringTimeMs();
    /** Zoom (1 = the whole island) and the middle of the view, in blocks. */
    private static double zoom = 1;
    private static double midX = Double.NaN, midZ = Double.NaN;
    private static String lastIsland = "";

    public IslandMapScreen() {
        super(Text.literal("Island"));
        Net.RunView v = ExtractionHud.view;
        if (v != null && (!v.island().equals(lastIsland) || Double.isNaN(midX))) {
            lastIsland = v.island();
            recentre(v);
        }
    }

    private static void recentre(Net.RunView v) {
        zoom = 1;
        midX = v.cx();
        midZ = v.cz();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void init() {
        AotButton c = addDrawableChild(new AotButton(width - 74, height - 24, 64, 16, Text.literal("Recentre"), () -> {
            if (ExtractionHud.view != null) recentre(ExtractionHud.view);
        }));
        c.textScale = 0.75f;
    }

    private int R() {
        return (int) (Math.min(width, height) * 0.40f);
    }

    /** Blocks per pixel at the current zoom. */
    private double scale(Net.RunView v) {
        return v.radius() * 1.15 / R() / zoom;
    }

    private int sx(Net.RunView v, double x) {
        return width / 2 + (int) ((x - midX) / scale(v));
    }

    private int sy(Net.RunView v, double z) {
        return height / 2 + 8 + (int) ((z - midZ) / scale(v));
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        Net.RunView v = ExtractionHud.view;
        if (v == null || button != 0) return super.mouseDragged(mx, my, button, dx, dy);
        midX -= dx * scale(v);
        midZ -= dy * scale(v);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        Net.RunView v = ExtractionHud.view;
        if (v == null) return false;
        // Zoom about the point under the mouse.
        double wx = midX + (mx - width / 2.0) * scale(v), wz = midZ + (my - height / 2.0 - 8) * scale(v);
        zoom = MathHelper.clamp(zoom * (vy > 0 ? 1.25 : 0.8), 0.6, 6);
        midX = wx - (mx - width / 2.0) * scale(v);
        midZ = wz - (my - height / 2.0 - 8) * scale(v);
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        Net.RunView v = ExtractionHud.view;
        if (v != null && button == 1) {
            // Right-click your own mark: take it back. Anywhere else: mark it.
            for (Net.RunPoint pt : v.points()) {
                if (!pt.kind().equals("mymark")) continue;
                double dx = sx(v, pt.x()) - mx, dy = sy(v, pt.z()) - my;
                if (dx * dx + dy * dy < 10 * 10) {
                    ClientPlayNetworking.send(new Net.ExtractionAction("unmark", ""));
                    return true;
                }
            }
            int x = (int) Math.round(midX + (mx - width / 2.0) * scale(v)), z = (int) Math.round(midZ + (my - height / 2.0 - 8) * scale(v));
            ClientPlayNetworking.send(new Net.ExtractionAction("mark", x + "," + z));
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0xE0081018, 0xF0040608);
        Net.RunView v = ExtractionHud.view;
        if (v == null || client == null || client.player == null) return;
        long t = Util.getMeasuringTimeMs();
        float in = Math.min(1, (t - opened) / 250f);
        int frame = (int) (R() * (0.94f + 0.06f * in)), fx = width / 2, fy = height / 2 + 8;
        // The chart's frame; everything inside moves with the view.
        ExtractionHud.disc(c, fx, fy, frame, 0xC0102030);
        c.enableScissor(fx - frame, fy - frame, fx + frame, fy + frame);
        int ix = sx(v, v.cx()), iy = sy(v, v.cz()), ir = (int) (v.radius() / scale(v));
        if (ir < 2000) {
            ExtractionHud.disc(c, ix, iy, ir, (v.color() & 0xFFFFFF) | 0x30000000);
            ExtractionHud.ring(c, ix, iy, ir, (v.color() & 0xFFFFFF) | 0xC0000000);
        }
        // A 100-block grid.
        double step = 100 / scale(v);
        if (step > 12) {
            double gx0 = Math.floor((midX - frame * scale(v)) / 100) * 100, gz0 = Math.floor((midZ - frame * scale(v)) / 100) * 100;
            for (double gx = gx0; gx < midX + frame * scale(v); gx += 100) c.fill(sx(v, gx), fy - frame, sx(v, gx) + 1, fy + frame, 0x18FFFFFF);
            for (double gz = gz0; gz < midZ + frame * scale(v); gz += 100) c.fill(fx - frame, sy(v, gz), fx + frame, sy(v, gz) + 1, 0x18FFFFFF);
        }
        for (Net.RunPoint pt : v.points()) {
            int x = sx(v, pt.x()), y = sy(v, pt.z());
            int col = pt.done() ? 0xFF55524A : ExtractionHud.color(pt.kind());
            boolean mark = pt.kind().endsWith("mark");
            int s = pt.kind().equals("mate") ? 3 : 4;
            if (pt.kind().equals("exit") || mark) {
                float pulse = 0.5f + 0.5f * MathHelper.sin(t * 0.006f);
                c.fill(x - 7, y - 7, x + 8, y + 8, ((int) (90 * pulse) << 24) | (col & 0xFFFFFF));
            }
            if (mark) {
                // A pin: a diamond on a stem.
                c.fill(x, y - 10, x + 1, y, col);
                for (int k = 0; k < 4; k++) c.fill(x - k, y - 14 + k, x + k + 1, y - 13 + k, col);
                for (int k = 0; k < 4; k++) c.fill(x - 3 + k, y - 10 + k, x + 4 - k, y - 9 + k, col);
            } else {
                c.fill(x - s - 1, y - s - 1, x + s + 2, y + s + 2, 0xFF000000);
                c.fill(x - s, y - s, x + s + 1, y + s + 1, col);
            }
            String label = pt.kind().equals("exit") ? "Flare" : pt.name() + (pt.done() ? "  ✔" : "");
            Ui.text(c, Text.literal(label), x, y + 8, 0.7f, col, true);
        }
        // You.
        var p = client.player;
        int x = sx(v, p.getX()), y = sy(v, p.getZ());
        double yaw = Math.toRadians(p.getYaw());
        for (int i = 0; i < 9; i++) c.fill(x + (int) (-Math.sin(yaw) * i), y + (int) (Math.cos(yaw) * i), x + (int) (-Math.sin(yaw) * i) + 2,
            y + (int) (Math.cos(yaw) * i) + 2, 0xFFFFFFFF);
        c.fill(x - 2, y - 2, x + 3, y + 3, 0xFFFFFFFF);
        Ui.text(c, Text.literal("You"), x, y - 12, 0.7f, 0xFFFFFFFF, true);
        c.disableScissor();
        ExtractionHud.ring(c, fx, fy, frame, 0xFF7A6139);
        Ui.text(c, Text.literal("N"), fx, fy - frame - 10, 0.9f, Ui.GOLD, true);
        // Title and clock.
        Ui.text(c, Ui.title(v.name().toUpperCase()), fx, 14, 1.6f, v.color(), true);
        int left = ExtractionHud.secondsLeft();
        String sub = String.format("%d:%02d left", left / 60, left % 60) + (v.matchMinutes() > 0 ? "   ·   match " + v.matchMinutes() + " min in" : "");
        Ui.text(c, Text.literal(sub), fx, 34, 0.85f, left < 120 ? 0xFFE03A3A : Ui.CREAM, true);
        // Legend and controls.
        String[][] legend = {{"exit", "Flare"}, {"obj", "Objective"}, {"poi", "Hot zone"}, {"mate", "Squad"}, {"mark", "Mark"}};
        int ly = height - 20, lx = 12;
        for (String[] l : legend) {
            c.fill(lx, ly + 1, lx + 6, ly + 7, ExtractionHud.color(l[0]));
            Ui.text(c, Text.literal(l[1]), lx + 10, ly, 0.75f, Ui.CREAM, false);
            lx += 70;
        }
        Ui.text(c, Text.literal("Drag  ·  Scroll  ·  Right-click: mark"), width - 84, ly + 1, 0.65f, Ui.MUTED, false);
    }
}
