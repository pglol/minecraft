package com.pglol.aotrpg.client;

import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/**
 * How a worn title looks. Most are one colour, in italics. Mythic titles (the red ones) are alive:
 * a molten gradient rolls through the letters, a white-hot glint sweeps across now and then, the
 * letters bob in a slow wave of brightness, and every few seconds one flickers into a glitch.
 * Pk'er gets crossed swords and skulls either side.
 */
public final class TitleFx {
    private TitleFx() {}

    /** The colour every Mythic title is given (server side, Store.TITLES). */
    public static final int MYTHIC = 0xE02A2A;
    private static final String GLITCH = "#%&$@!?";

    public static boolean mythic(int color) {
        return (color & 0xFFFFFF) == MYTHIC;
    }

    /** The title as it should be shown, framed in guillemets (or its own flourishes). */
    public static MutableText styled(String title, int color) {
        if (!mythic(color)) return Text.literal("« " + title + " »").withColor(0xFF000000 | color).styled(st -> st.withItalic(true));
        long now = Util.getMeasuringTimeMs();
        double t = now / 1000.0;
        boolean pk = title.equalsIgnoreCase("Pk'er");
        String left = pk ? "☠ ⚔ " : "✦ ", right = pk ? " ⚔ ☠" : " ✦";
        MutableText out = Text.empty();
        // The flourishes pulse between blood red and ember.
        int pulse = mix(0x7A0A0A, 0xFF4A2A, (float) (0.5 + 0.5 * Math.sin(t * 4)));
        out.append(Text.literal(left).setStyle(Style.EMPTY.withColor(pulse).withBold(pk)));
        // One letter glitches for a moment every few seconds.
        int glitchAt = (int) ((now / 2600) % Math.max(1, title.length()));
        boolean glitching = now % 2600 < 140;
        double sweep = (t * 0.8) % 2.2 - 0.6; // the glint, travelling left to right
        for (int i = 0; i < title.length(); i++) {
            char ch = title.charAt(i);
            double u = title.length() <= 1 ? 0 : i / (double) (title.length() - 1);
            // Molten gradient rolling through the letters.
            float wave = (float) (0.5 + 0.5 * Math.sin(t * 3 - u * 5));
            int c = mix(0x8A0A0A, 0xFF3A2A, wave);
            // The white-hot glint.
            double g = 1 - Math.min(1, Math.abs(u - sweep) * 4);
            if (g > 0) c = mix(c, 0xFFF0D0, (float) g);
            Style st = Style.EMPTY.withColor(c).withBold(true).withItalic(false);
            if (glitching && i == glitchAt && ch != ' ') {
                out.append(Text.literal(String.valueOf(GLITCH.charAt((int) (now / 40 % GLITCH.length())))).setStyle(st.withColor(0xFFFFFF).withObfuscated(true)));
            } else {
                out.append(Text.literal(String.valueOf(ch)).setStyle(st));
            }
        }
        out.append(Text.literal(right).setStyle(Style.EMPTY.withColor(pulse).withBold(pk)));
        return out;
    }

    private static int mix(int a, int b, float u) {
        u = Math.max(0, Math.min(1, u));
        int r = (int) (((a >> 16) & 255) * (1 - u) + ((b >> 16) & 255) * u);
        int g = (int) (((a >> 8) & 255) * (1 - u) + ((b >> 8) & 255) * u);
        int bl = (int) ((a & 255) * (1 - u) + (b & 255) * u);
        return (r << 16) | (g << 8) | bl;
    }
}
