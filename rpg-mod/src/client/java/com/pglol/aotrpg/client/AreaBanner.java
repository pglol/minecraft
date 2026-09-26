package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/**
 * Entering a place: a quiet banner in the style of its culture, not a coloured pop-up.
 *
 * Paradis (inside and around the Walls) is an old-world, Germanic-European society under the
 * Church of the Walls: serif capitals on parchment ink, brass rules, the wall named above.
 * Marley is an industrial empire: plain spaced type in steel grey with a red armband rule.
 * The sea is slate. Danger is told with the caption (Titan Territory), not with neon.
 */
public final class AreaBanner {
    private AreaBanner() {}

    private static Net.Area area;
    private static long shownAt = -99999;
    private static final long IN = 600, STAY = 3200, OUT = 900;

    /** A title that names a known place becomes its banner. Returns false if it isn't a place. */
    public static boolean tryShow(Text title) {
        String name = title.getString().trim();
        if (name.isEmpty()) return false;
        for (Net.Area a : ClientState.areas) {
            if (a.name().equalsIgnoreCase(name)) {
                if (area != null && area.id().equals(a.id()) && Util.getMeasuringTimeMs() - shownAt < IN + STAY) return true;
                area = a;
                shownAt = Util.getMeasuringTimeMs();
                return true;
            }
        }
        return false;
    }

    private static String caption(Net.Area a) {
        return switch (a.look()) {
            case "marley" -> a.sub().equals("Marley") ? "MARLEYAN EMPIRE" : a.sub().toUpperCase();
            case "sea" -> "BETWEEN PARADIS AND MARLEY";
            case "cave" -> "TITAN CAVE";
            case "camp" -> "SURVEY CORPS";
            case "danger" -> "TITAN TERRITORY";
            default -> a.sub().toUpperCase();
        };
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (area == null || mc.options.hudHidden) return;
        long age = Util.getMeasuringTimeMs() - shownAt;
        if (age > IN + STAY + OUT) return;
        float a = age < IN ? age / (float) IN : age > IN + STAY ? 1 - (age - IN - STAY) / (float) OUT : 1;
        int alpha = Math.max(6, (int) (255 * a));
        int w = c.getScaledWindowWidth();
        int y = (int) (c.getScaledWindowHeight() * 0.2f);
        String look = area.look();
        String lv = "Lv " + area.min() + (area.max() > area.min() ? "–" + area.max() : "");
        if (look.equals("marley")) {
            // Industrial: plain spaced capitals, steel, a red armband rule.
            String name = spaced(area.name().toUpperCase());
            Ui.text(c, Text.literal(spaced(caption(area))), w / 2f, y, 0.7f, alpha << 24 | 0x9AA3AA, true);
            Ui.text(c, Text.literal(name), w / 2f, y + 10, 1.6f, alpha << 24 | 0xDADDE0, true);
            int rw = 70;
            c.fill(w / 2 - rw, y + 29, w / 2 + rw, y + 30, alpha * 3 / 5 << 24 | 0x6E7478);
            c.fill(w / 2 - 12, y + 28, w / 2 + 12, y + 31, alpha << 24 | 0x8E2A2A);
            Ui.text(c, Text.literal(lv), w / 2f, y + 35, 0.65f, alpha << 24 | 0x8C9296, true);
            return;
        }
        int cap, ink, rule;
        switch (look) {
            case "sea" -> { cap = 0x7F97AE; ink = 0xDCE3EA; rule = 0x5E7488; }
            case "danger", "cave" -> { cap = 0x9A4A3E; ink = 0xE6DCC4; rule = 0x7A4A38; }
            case "camp" -> { cap = 0x8A9A62; ink = 0xE6DFC8; rule = 0x6E7A4A; }
            default -> { cap = 0xB09060; ink = 0xEEE4CB; rule = 0x8C7248; }
        }
        // Old-world: serif capitals, brass rules with a small lozenge, the wall named above.
        Ui.text(c, Ui.heading(caption(area)), w / 2f, y, 0.75f, alpha << 24 | cap, true);
        Ui.text(c, Ui.title(area.name()), w / 2f, y + 10, 1.7f, alpha << 24 | ink, true);
        int rw = 80, ry = y + 31;
        c.fill(w / 2 - rw, ry, w / 2 - 6, ry + 1, alpha * 3 / 4 << 24 | rule);
        c.fill(w / 2 + 6, ry, w / 2 + rw, ry + 1, alpha * 3 / 4 << 24 | rule);
        Ui.text(c, Text.literal("◆"), w / 2f, ry - 3, 0.6f, alpha << 24 | cap, true);
        Ui.text(c, Text.literal(lv), w / 2f, ry + 5, 0.65f, alpha << 24 | 0xA69E8A, true);
    }

    private static String spaced(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return b.toString().replace("   ", "  ");
    }
}
