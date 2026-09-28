package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * A duel's face-off. The screen splits on a slant: one fighter's face fills the left, the other's
 * the right, sliding in from the edges with their names, ranks and roles. VS slams into the seam,
 * the rules sit under it, then three, two, one counts down big in the middle, and FIGHT.
 */
public final class DuelIntro {
    private DuelIntro() {}

    private static Net.DuelIntro now;
    private static long at;
    /** Face-off, then the count: 2s + 3s, and FIGHT fading out. */
    private static final float FACE = 2f, TOTAL = 5.7f;

    public static void on(Net.DuelIntro d) {
        now = d;
        at = Util.getMeasuringTimeMs();
    }

    public static boolean active() {
        return now != null && (Util.getMeasuringTimeMs() - at) / 1000f < TOTAL;
    }

    private static SkinTextures skin(java.util.UUID id) {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerListEntry e = mc.getNetworkHandler() == null ? null : mc.getNetworkHandler().getPlayerListEntry(id);
        return e != null ? e.getSkinTextures() : net.minecraft.client.util.DefaultSkinHelper.getSkinTextures(id);
    }

    private static float ease(float t) {
        t = MathHelper.clamp(t, 0, 1);
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        if (now == null) return;
        float t = (Util.getMeasuringTimeMs() - at) / 1000f;
        if (t > TOTAL) {
            now = null;
            return;
        }
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        var m = c.getMatrices();
        m.push();
        m.translate(0, 0, 950);
        // The face-off: the halves slide in, hold, and part again as the count begins.
        float in = ease(t / 0.45f), out = ease((t - FACE) / 0.4f);
        float open = in * (1 - out);
        if (open > 0.01f) {
            int slant = h / 5;
            int half = w / 2;
            int shift = (int) ((1 - in) * half + out * half);
            // Left half (gold) and right half (red), each a slanted panel.
            for (int y = 0; y < h; y += 2) {
                int edge = half + slant / 2 - (int) (slant * (y / (float) h));
                c.fill(-shift, y, edge - shift - 2, y + 2, 0xE0161210);
                c.fill(edge + shift + 2, y, w + shift, y + 2, 0xE0100C0C);
            }
            // The seam: a bright slash.
            for (int y = 0; y < h; y += 2) {
                int edge = half + slant / 2 - (int) (slant * (y / (float) h));
                c.fill(edge - 2, y, edge + 2, y + 2, ((int) (255 * open) << 24) | 0xF2EDE2);
            }
            int face = Math.min(h / 2, w / 4);
            int a = (int) (255 * open);
            // Faces, big, each on its side.
            int ly = h / 2 - face / 2 - 12;
            PlayerSkinDrawer.draw(c, skin(now.a()), w / 4 - face / 2 - shift, ly, face);
            PlayerSkinDrawer.draw(c, skin(now.b()), w * 3 / 4 - face / 2 + shift, ly, face);
            // Names, ranks and roles under them.
            int ny = ly + face + 8;
            Ui.text(c, Ui.title(now.aName().toUpperCase()), w / 4f - shift, ny, 1.6f, (a << 24) | 0xE0B96A, true);
            Ui.text(c, Text.literal("Lv " + now.aLevel() + (now.aRole().isEmpty() ? "" : "  ·  " + now.aRole())), w / 4f - shift, ny + 16, 0.85f,
                (a << 24) | 0xD8D0C0, true);
            Ui.text(c, Ui.title(now.bName().toUpperCase()), w * 3 / 4f + shift, ny, 1.6f, (a << 24) | 0xE0463A, true);
            Ui.text(c, Text.literal("Lv " + now.bLevel() + (now.bRole().isEmpty() ? "" : "  ·  " + now.bRole())), w * 3 / 4f + shift, ny + 16, 0.85f,
                (a << 24) | 0xD8D0C0, true);
            // VS slams into the seam just after the halves meet.
            float vs = MathHelper.clamp((t - 0.4f) / 0.18f, 0, 1);
            if (vs > 0) {
                float s = 4.5f - 1.7f * ease(vs);
                Ui.text(c, Ui.title("VS"), w / 2f, h / 2f - 16, s, (a << 24) | 0xFFFFFF, true);
                if (t < 0.7f) c.fill(0, 0, w, h, ((int) (90 * (1 - (t - 0.4f) / 0.3f))) << 24 | 0xFFFFFF);
            }
            Ui.text(c, Text.literal(now.rules()), w / 2f, h - 36, 0.8f, (a << 24) | 0xB8B0A0, true);
        }
        // The count: 3, 2, 1, each landing big and shrinking away; then FIGHT.
        if (t >= FACE) {
            float ct = t - FACE;
            int n = 3 - (int) ct;
            float k = ct - (int) ct;
            String s = n >= 1 ? String.valueOf(n) : "FIGHT";
            int col = n >= 1 ? 0xE0B96A : 0xE0463A;
            float scale = (n >= 1 ? 6f : 5f) * (1.35f - 0.35f * ease(k / 0.25f));
            int a = (int) (255 * (n >= 1 ? MathHelper.clamp(1 - (k - 0.7f) / 0.3f, 0, 1) : MathHelper.clamp(1 - (ct - 3.2f) / 0.5f, 0, 1)));
            if (a > 4) Ui.text(c, Ui.title(s), w / 2f, h / 2f - 22, scale, (a << 24) | col, true);
            if (n < 1 && ct < 3.15f) c.fill(0, 0, w, h, ((int) (70 * (1 - (ct - 3f) / 0.15f))) << 24 | 0xE0463A);
        }
        m.pop();
    }
}
