package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * Out on an island the open world's HUD steps aside (no Paradis minimap, markers or area banners):
 * a radar of the island instead, with the flares, hot zones, objectives and your squad on it,
 * the clock, and your tasks and objectives underneath.
 */
public final class ExtractionHud {
    private ExtractionHud() {}

    static Net.RunView view;
    private static long viewAt;

    public static void on(Net.RunView v) {
        view = v.island().isEmpty() ? null : v;
        viewAt = Util.getMeasuringTimeMs();
        if (view == null && MinecraftClient.getInstance().currentScreen instanceof IslandMapScreen) MinecraftClient.getInstance().setScreen(null);
    }

    /** On an island in a run right now. */
    public static boolean active() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return view != null && mc.world != null && mc.world.getRegistryKey().getValue().getPath().equals(view.island());
    }

    static int secondsLeft() {
        if (view == null) return 0;
        return (int) Math.max(0, view.secondsLeft() - (Util.getMeasuringTimeMs() - viewAt) / 1000);
    }

    static int color(String kind) {
        return switch (kind) {
            case "exit" -> 0xFFE0463A;
            case "poi" -> 0xFFC77DFF;
            case "obj" -> 0xFFF2C14E;
            default -> 0xFF5BD35B;
        };
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!active() || mc.player == null || mc.options.hudHidden || mc.currentScreen != null) return;
        long t = Util.getMeasuringTimeMs();
        int R = 46, cx = 14 + R, cy = 40 + R;
        double range = 220;
        double px = mc.player.getX(), pz = mc.player.getZ();
        // The radar: a dark disc, rings, north up.
        disc(c, cx, cy, R, 0xB0080A08);
        ring(c, cx, cy, R, 0xFF7A6139);
        ring(c, cx, cy, R * 2 / 3, 0x40FFFFFF);
        ring(c, cx, cy, R / 3, 0x30FFFFFF);
        // The island's edge, when it's in range.
        double ex = (view.cx() - px) / range * R, ez = (view.cz() - pz) / range * R, er = view.radius() / range * R;
        for (int i = 0; i < 90; i++) {
            double a = i * Math.PI * 2 / 90;
            double x = ex + Math.cos(a) * er, z = ez + Math.sin(a) * er;
            if (x * x + z * z < R * R) c.fill(cx + (int) x, cy + (int) z, cx + (int) x + 1, cy + (int) z + 1, 0x90A0D8FF);
        }
        for (Net.RunPoint pt : view.points()) {
            double dx = (pt.x() - px) / range * R, dz = (pt.z() - pz) / range * R;
            double d = Math.sqrt(dx * dx + dz * dz);
            boolean rim = d > R - 4;
            if (rim) {
                dx = dx / d * (R - 4);
                dz = dz / d * (R - 4);
            }
            int col = pt.done() ? 0xFF55524A : color(pt.kind());
            int x = cx + (int) dx, y = cy + (int) dz, s = pt.kind().equals("mate") ? 2 : 3;
            if (pt.kind().equals("exit") && !rim) {
                float pulse = 0.5f + 0.5f * MathHelper.sin(t * 0.008f);
                c.fill(x - s - 1, y - s - 1, x + s + 2, y + s + 2, ((int) (120 * pulse) << 24) | (col & 0xFFFFFF));
            }
            c.fill(x - s, y - s, x + s + 1, y + s + 1, 0xFF000000);
            c.fill(x - s + 1, y - s + 1, x + s, y + s, col);
        }
        // You: an arrow pointing where you face.
        double yaw = Math.toRadians(mc.player.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        for (int i = 0; i < 6; i++) c.fill(cx + (int) (fx * i), cy + (int) (fz * i), cx + (int) (fx * i) + 2, cy + (int) (fz * i) + 2, 0xFFFFFFFF);
        c.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF);
        Ui.text(c, Text.literal("N"), cx, cy - R - 9, 0.7f, Ui.GOLD, true);
        // The island, the clock, the match.
        int y = cy + R + 6;
        Ui.text(c, Ui.heading(view.name()), 14, y, 0.9f, view.color(), false);
        int left = secondsLeft();
        String clock = String.format("%d:%02d", left / 60, left % 60);
        int cc = left < 120 ? (t / 400 % 2 == 0 ? 0xFFE03A3A : 0xFFFFFFFF) : left < 300 ? Ui.GOLD : Ui.CREAM;
        Ui.text(c, Text.literal(clock), 14 + R * 2 - Ui.font().getWidth(clock) * 0.9f, y, 0.9f, cc, false);
        y += 12;
        // Tasks and objectives.
        for (Net.RunTask task : view.tasks()) {
            boolean done = task.progress() >= task.goal();
            boolean obj = task.text().startsWith("★");
            c.fill(14, y, 14 + 170, y + 12, 0x80000000);
            c.fill(14, y, 16, y + 12, done ? 0xFF5BD35B : obj ? 0xFFF2C14E : 0xFF8F8A7A);
            Ui.text(c, Text.literal(task.text()), 20, y + 3, 0.62f, done ? 0xFF5BD35B : Ui.CREAM, false);
            String pr = done ? "✔" : task.progress() + "/" + task.goal();
            Ui.text(c, Text.literal(pr), 14 + 166 - Ui.font().getWidth(pr) * 0.62f, y + 3, 0.62f, done ? 0xFF5BD35B : Ui.GOLD, false);
            y += 14;
        }
    }

    static void disc(DrawContext c, int cx, int cy, int r, int col) {
        for (int dy = -r; dy <= r; dy++) {
            int w = (int) Math.sqrt(r * r - dy * dy);
            c.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, col);
        }
    }

    static void ring(DrawContext c, int cx, int cy, int r, int col) {
        int n = Math.max(24, r * 4);
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n;
            int x = cx + (int) Math.round(Math.cos(a) * r), y = cy + (int) Math.round(Math.sin(a) * r);
            c.fill(x, y, x + 1, y + 1, col);
        }
    }
}
