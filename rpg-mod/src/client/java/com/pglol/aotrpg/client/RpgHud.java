package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/**
 * Health and stamina, low and centred over the hotbar like two straps of a harness: health on
 * the left, stamina on the right, food and breath as thin cords beneath them. Numbers sit above
 * the straps, never on them.
 */
public final class RpgHud {
    private RpgHud() {}

    private static final int FRAME = 0xFF0B0C0A, TRACK = 0xB01B1A16;
    private static final int BLOOD = 0xFF9E2B25, BLOOD_HI = 0xFFC2493A, BLOOD_GHOST = 0xFFD8C4A8;
    private static final int SAGE = 0xFF7E9A6C, SAGE_HI = 0xFFA2BC8C;
    private static final int WHEAT = 0xFFC9A15A, BREATH = 0xFF8FB3CF;

    private static float shownHp = -1, ghostHp = -1;
    private static long ghostHold;

    /** True when the RPG HUD replaces the vanilla hearts/hunger/XP. */
    public static boolean active() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return ClientState.profile != null && mc.player != null && mc.interactionManager != null
            && mc.interactionManager.hasStatusBars();
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity pl = mc.player;
        if (ClientState.profile == null || pl == null || mc.options.hudHidden || mc.getDebugHud().shouldShowDebugHud()) return;
        if (!active()) return;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        long now = Util.getMeasuringTimeMs();
        int cx = w / 2;
        int l0 = cx - 107, l1 = cx - 24, r0 = cx + 24, r1 = cx + 107;
        int by = h - 35, bh = 5, cordY = h - 29, labelY = h - 44;

        // Health, with a pale trail showing what the last hit took.
        float max = pl.getMaxHealth(), hp = pl.getHealth();
        float frac = Math.max(0, Math.min(1, hp / max));
        if (shownHp < 0) shownHp = ghostHp = frac;
        if (frac < shownHp) ghostHold = now + 450;
        shownHp = frac;
        if (ghostHp < frac) ghostHp = frac;
        else if (now > ghostHold) ghostHp = Math.max(frac, ghostHp - 0.012f);
        boolean low = frac < 0.3f;
        int hpFrame = low ? blend(FRAME, 0xFFC0302A, (float) (0.5 + 0.5 * Math.sin(now / 150.0))) : FRAME;
        strap(c, l0, by, l1 - l0, bh, frac, ghostHp, BLOOD, BLOOD_HI, hpFrame, false);
        float abs = pl.getAbsorptionAmount();
        if (abs > 0) {
            int aw = Math.round((l1 - l0 - 2) * Math.min(1, abs / max));
            c.fill(l0 + 1, by + 1, l0 + 1 + aw, by + 2, 0xFFE8C84A);
        }

        // Stamina, mirrored: it drains towards the centre.
        float st = ClientState.stamina < 0 ? ClientState.maxStamina : ClientState.stamina;
        float sf = Math.max(0, Math.min(1, st / ClientState.maxStamina));
        int sc = SAGE, shi = SAGE_HI;
        if (ClientState.exhausted) {
            float k = (float) (0.5 + 0.5 * Math.sin(now / 110.0));
            sc = blend(0xFF7A2A22, 0xFFB0443A, k);
            shi = sc;
        } else if (sf < 0.3f) {
            sc = 0xFFB08E4A;
            shi = 0xFFCCAA66;
        }
        strap(c, r0, by, r1 - r0, bh, sf, sf, sc, shi, FRAME, true);

        // Food under stamina (an empty belly is the first thing that tires you); breath under health, only while it runs short.
        int food = pl.getHungerManager().getFoodLevel();
        boolean hungry = food <= 6;
        int fc = hungry ? blend(0xFF8A2A20, 0xFFD04A3A, (float) (0.5 + 0.5 * Math.sin(now / 200.0))) : WHEAT;
        cord(c, r0, cordY, r1 - r0, food / 20f, fc, true);
        int air = pl.getAir(), maxAir = pl.getMaxAir();
        if (air < maxAir) cord(c, l0, cordY, l1 - l0, Math.max(0, air) / (float) maxAir, BREATH, false);

        // Numbers above the straps: health and armour on the left, stamina on the right.
        var font = mc.textRenderer;
        int x = l0;
        Glyphs.draw(c, x, labelY + 1, Glyphs.HEART, low ? 0xFFE0564A : 0xFFB8463A, 0);
        x += 10;
        Text hpText = Text.literal(String.valueOf(Math.round(hp))).withColor(Ui.CREAM)
            .append(Text.literal("/" + Math.round(max)).withColor(Ui.MUTED));
        if (abs > 0) hpText = hpText.copy().append(Text.literal(" +" + Math.round(abs)).withColor(0xFFE8C84A));
        c.drawText(font, hpText, x, labelY, 0xFFFFFFFF, true);
        x += font.getWidth(hpText) + 8;
        int armor = pl.getArmor();
        if (armor > 0) {
            Glyphs.draw(c, x, labelY, Glyphs.SHIELD, 0xFF8F8A7A, 0xFF3A3834);
            x += 10;
            c.drawText(font, String.valueOf(armor), x, labelY, Ui.MUTED, true);
                    }

        String stText = ClientState.exhausted ? "Exhausted" : String.valueOf(Math.round(st));
        int stw = font.getWidth(stText);
        int sx = r1 - stw;
        c.drawText(font, stText, sx, labelY, ClientState.exhausted ? sc : Ui.CREAM, true);
        Glyphs.draw(c, sx - 8, labelY, Glyphs.BOLT, ClientState.exhausted ? sc : 0xFFA2BC8C, 0);
        if (hungry) {
            Glyphs.draw(c, r0, labelY, Glyphs.WHEAT, fc, 0);
            if (!ClientState.exhausted) c.drawText(font, food <= 2 ? "Starving" : "Hungry", r0 + 8, labelY, fc, true);
        }
    }

    /** A strap: dark frame, dim track, the fill with a lighter top edge, and quarter notches. */
    private static void strap(DrawContext c, int x, int y, int w, int h, float frac, float ghost, int col, int hi, int frame, boolean fromRight) {
        c.fill(x - 1, y - 1, x + w + 1, y + h + 1, frame);
        c.fill(x, y, x + w, y + h, TRACK);
        int gw = Math.round(w * ghost), fw = Math.round(w * frac);
        if (fromRight) {
            if (gw > fw) c.fill(x + w - gw, y, x + w - fw, y + h, BLOOD_GHOST);
            c.fill(x + w - fw, y, x + w, y + h, col);
            c.fill(x + w - fw, y, x + w, y + 1, hi);
        } else {
            if (gw > fw) c.fill(x + fw, y, x + gw, y + h, BLOOD_GHOST);
            c.fill(x, y, x + fw, y + h, col);
            c.fill(x, y, x + fw, y + 1, hi);
        }
        for (int q = 1; q < 4; q++) {
            int nx = x + w * q / 4;
            c.fill(nx, y + h - 2, nx + 1, y + h, 0x90000000);
        }
    }

    /** A cord: a two-pixel line under a strap. */
    private static void cord(DrawContext c, int x, int y, int w, float frac, int col, boolean fromRight) {
        c.fill(x, y, x + w, y + 2, 0x80101010);
        int fw = Math.round(w * Math.max(0, Math.min(1, frac)));
        if (fromRight) c.fill(x + w - fw, y, x + w, y + 2, col);
        else c.fill(x, y, x + fw, y + 2, col);
    }

    private static int blend(int a, int b, float k) {
        int ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return 0xFF000000 | (int) (ar + (br - ar) * k) << 16 | (int) (ag + (bg - ag) * k) << 8 | (int) (ab + (bb - ab) * k);
    }
}
