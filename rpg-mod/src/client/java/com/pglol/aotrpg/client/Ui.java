package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Colours, fonts and drawing helpers for the AoT look: dark cloak green, worn leather, gold trim. */
public final class Ui {
    private Ui() {}

    public static final int BG = 0xE20D110E, BG_LIGHT = 0xE8171D18, BORDER = 0xFF7A6139, TRIM = 0xFFB8955A;
    public static final int GOLD = 0xFFE0B96A, CREAM = 0xFFEDE3C8, MUTED = 0xFF8F8A7A, DIM = 0xFF55524A;
    public static final int RED = 0xFFC0463A, HP = 0xFFB3362C, STAMINA = 0xFF7FB24A, XP = 0xFFD9A441, FOOD = 0xFF9C6A3A, AIR = 0xFF5A8FD0;

    private static final Identifier TITLE = Identifier.of("aot_rpg", "title"), HEADING = Identifier.of("aot_rpg", "heading");

    public static MutableText title(String s) {
        return Text.literal(s).styled(st -> st.withFont(TITLE));
    }

    public static MutableText heading(String s) {
        return Text.literal(s).styled(st -> st.withFont(HEADING));
    }

    public static TextRenderer font() {
        return MinecraftClient.getInstance().textRenderer;
    }

    public static final Identifier PANEL = Identifier.of("aot_rpg", "textures/gui/panel.png");
    public static final Identifier SLOT = Identifier.of("aot_rpg", "textures/gui/slot.png");
    public static final Identifier CREST = Identifier.of("aot_rpg", "textures/gui/crest.png");

    public static void slot(DrawContext c, int x, int y) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        c.drawTexture(SLOT, x, y, 0, 0, 18, 18, 18, 18);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    public static void crest(DrawContext c, int x, int y, int size, float alpha) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        c.setShaderColor(1f, 1f, 1f, alpha);
        c.drawTexture(CREST, x, y, size, size, 0, 0, 128, 128, 128, 128);
        c.setShaderColor(1f, 1f, 1f, 1f);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    /** Cloth texture fill (tiled in 512 px pieces). */
    public static void cloth(DrawContext c, int x, int y, int w, int h) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        for (int oy = 0; oy < h; oy += 512) {
            for (int ox = 0; ox < w; ox += 512) {
                int tw = Math.min(512, w - ox), th = Math.min(512, h - oy);
                c.drawTexture(PANEL, x + ox, y + oy, (x * 3 + ox) & 255, (y * 5 + oy) & 255, tw, th, 512, 512);
            }
        }
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    /** A framed panel with a gold outline and a faint top highlight. */
    public static void panel(DrawContext c, int x, int y, int w, int h) {
        c.fill(x, y, x + w, y + h, 0xC0070907);
        cloth(c, x, y, w, h);
        c.drawBorder(x, y, w, h, BORDER);
        c.fill(x + 1, y + 1, x + w - 1, y + 2, 0x22FFFFFF);
        // corner studs
        int s = 2;
        c.fill(x - 1, y - 1, x + s, y + s, TRIM);
        c.fill(x + w - s, y - 1, x + w + 1, y + s, TRIM);
        c.fill(x - 1, y + h - s, x + s, y + h + 1, TRIM);
        c.fill(x + w - s, y + h - s, x + w + 1, y + h + 1, TRIM);
    }

    /** Gold frame without a background. */
    public static void border(DrawContext c, int x, int y, int w, int h) {
        c.drawBorder(x, y, w, h, BORDER);
        int s = 2;
        c.fill(x - 1, y - 1, x + s, y + s, TRIM);
        c.fill(x + w - s, y - 1, x + w + 1, y + s, TRIM);
        c.fill(x - 1, y + h - s, x + s, y + h + 1, TRIM);
        c.fill(x + w - s, y + h - s, x + w + 1, y + h + 1, TRIM);
    }

    /** A horizontal rule with a small diamond in the middle. */
    public static void divider(DrawContext c, int x, int y, int w) {
        c.fill(x, y, x + w, y + 1, 0x807A6139);
        int m = x + w / 2;
        c.fill(m - 1, y - 2, m + 2, y + 3, TRIM);
        c.fill(m - 2, y - 1, m + 3, y + 2, TRIM);
    }

    public static void bar(DrawContext c, int x, int y, int w, int h, float frac, int color) {
        frac = Math.max(0, Math.min(1, frac));
        c.fill(x, y, x + w, y + h, 0xC0000000);
        c.drawBorder(x, y, w, h, 0xFF2A2620);
        int fw = Math.round((w - 2) * frac);
        if (fw > 0) {
            c.fill(x + 1, y + 1, x + 1 + fw, y + h - 1, color);
            c.fill(x + 1, y + 1, x + 1 + fw, y + 2, 0x40FFFFFF);
            if (h > 4) c.fill(x + 1, y + h - 2, x + 1 + fw, y + h - 1, 0x30000000);
        }
    }

    public static void text(DrawContext c, Text t, float x, float y, float scale, int color, boolean center) {
        MatrixStack m = c.getMatrices();
        m.push();
        m.translate(x, y, 0);
        m.scale(scale, scale, 1);
        c.drawTextWithShadow(font(), t, center ? -font().getWidth(t) / 2 : 0, 0, color);
        m.pop();
    }

    public static void item(DrawContext c, ItemStack stack, int x, int y, float scale) {
        MatrixStack m = c.getMatrices();
        m.push();
        m.translate(x, y, 0);
        m.scale(scale, scale, 1);
        c.drawItem(stack, 0, 0);
        m.pop();
    }

    /** Draws wrapped text and returns the y below it. */
    public static int wrapped(DrawContext c, Text t, int x, int y, int w, int color) {
        for (var line : font().wrapLines(t, w)) {
            c.drawTextWithShadow(font(), line, x, y, color);
            y += 10;
        }
        return y;
    }

    /** Full-screen backdrop: dark vignette with a warm glow at the top. */
    public static void backdrop(DrawContext c, int w, int h) {
        c.fillGradient(0, 0, w, h, 0xF0141A15, 0xF8050605);
        c.fillGradient(0, 0, w, h / 3, 0x30B8955A, 0x00000000);
        c.fillGradient(0, h - 40, w, h, 0x00000000, 0x60000000);
    }

    /** Title colour for an area theme (matches the entry titles). */
    public static int lookColor(String look) {
        return switch (look) {
            case "town" -> 0xFFE8BE5A;
            case "safe" -> 0xFF72D068;
            case "cave" -> 0xFFC0302A;
            case "camp" -> 0xFF4FB060;
            case "landmark" -> 0xFFE070FF;
            case "marley" -> 0xFF5AD8E8;
            case "sea" -> 0xFF6A9FE0;
            default -> 0xFFF0503A;
        };
    }

    public static int disciplineColor(int ordinal) {
        return switch (ordinal) {
            case 0 -> 0xFF6FB04F; // Scout
            case 1 -> 0xFFD0513F; // Vanguard
            case 2 -> 0xFF5B86C9; // Guardian
            case 3 -> 0xFFE0C04A; // Marksman
            default -> 0xFFD07CC0; // Medic
        };
    }
}
