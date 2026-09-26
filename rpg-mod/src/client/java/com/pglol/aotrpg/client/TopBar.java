package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

import java.util.ArrayList;
import java.util.List;

/**
 * One quiet strip at the top centre: the hour and the sky, your level, and your purse. Every
 * segment is its own cell; text sits in the middle of it and never touches an edge.
 */
public final class TopBar {
    private TopBar() {}

    private static final int H = 16, TOP = 3, PAD = 6, GAP = 4;
    private static final int BG = 0xB40D0F0D, EDGE = 0x70B8955A, SEP = 0x40B8955A;

    /** Where the bar ends, for anything that stacks under it (boss bars). */
    public static int bottom() {
        return visible() ? TOP + H + 3 : 4;
    }

    private static boolean visible() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && mc.world != null && !mc.options.hudHidden && ClientState.profile != null
            && !mc.getDebugHud().shouldShowDebugHud();
    }

    private record Cell(String[] icon, int main, int second, Text text, int width, float xp) { }

    public static void render(DrawContext c, RenderTickCounter tick) {
        if (!visible()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Net.Sync p = ClientState.profile;
        long now = Util.getMeasuringTimeMs();
        List<Cell> cells = new ArrayList<>();

        // The hour and the sky.
        long t = Math.floorMod(mc.world.getTimeOfDay(), 24000L);
        int hour = (int) ((t / 1000 + 6) % 24), min = (int) (t % 1000 * 60 / 1000);
        String clock = (hour % 12 == 0 ? 12 : hour % 12) + ":" + (min < 10 ? "0" : "") + min + (hour < 12 ? " AM" : " PM");
        boolean night = hour >= 19 || hour < 6;
        BlockPos at = mc.player.getBlockPos();
        boolean snow = mc.world.getBiome(at).value().getPrecipitation(at) == Biome.Precipitation.SNOW;
        String[] sky;
        int m1, m2;
        if (mc.world.isThundering()) {
            sky = Glyphs.STORM; m1 = 0xFF8C8A84; m2 = 0xFFE8C860;
        } else if (mc.world.isRaining()) {
            sky = snow ? Glyphs.SNOW : Glyphs.RAIN; m1 = 0xFF9A9890; m2 = snow ? 0xFFEDEDE8 : 0xFF7FA6C8;
        } else if (night) {
            sky = Glyphs.MOON; m1 = 0xFFD8D2BE; m2 = 0;
        } else {
            sky = Glyphs.SUN; m1 = 0xFFE8C170; m2 = 0;
        }
        cells.add(cell(sky, m1, m2, Text.literal(clock).withColor(Ui.CREAM), -1));

        // Level, with the way to the next one as a thin line under it.
        float xp = p.need() > 0 ? Math.min(1, (float) p.xp() / p.need()) : 1;
        Text lv = Text.literal("Lv ").withColor(Ui.MUTED).append(Text.literal(String.valueOf(p.level())).withColor(Ui.GOLD));
        int pts = p.points() + p.skillPoints();
        if (pts > 0) {
            // Points waiting to be spent: just how many.
            int a = (int) (170 + 85 * Math.sin(now / 300.0));
            lv = lv.copy().append(Text.literal("  +" + pts).withColor(a << 24 | 0xE0B96A));
        }
        cells.add(cell(null, 0, 0, lv, xp));

        // The purse.
        cells.add(cell(Glyphs.COIN, 0xFF8C6A2E, 0xFFE0B96A, Text.literal(String.format("%,d", ClientState.marks)).withColor(Ui.CREAM), -1));
        if (ClientState.gold > 0) {
            cells.add(cell(Glyphs.COIN, 0xFF6E6A62, 0xFFD8D4C8, Text.literal(String.format("%,d", ClientState.gold)).withColor(0xFFD8D4C8), -1));
        }
        if (WitnessFx.bounty > 0) {
            cells.add(cell(Glyphs.SKULL, 0xFFC0463A, 0xFF1A0D0B, Text.literal(String.format("%,d", WitnessFx.bounty)).withColor(0xFFE07A6A), -1));
        }

        int total = 0;
        for (Cell x : cells) total += x.width;
        total += (cells.size() - 1) * 1;
        int w = c.getScaledWindowWidth();
        int x = w / 2 - total / 2, y = TOP;
        // Body with softened corners and a faint brass edge.
        c.fill(x + 1, y, x + total - 1, y + H, BG);
        c.fill(x, y + 1, x + 1, y + H - 1, BG);
        c.fill(x + total - 1, y + 1, x + total, y + H - 1, BG);
        c.fill(x + 2, y + H, x + total - 2, y + H + 1, EDGE);

        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            int cx = x + PAD;
            if (cell.icon != null) {
                int ih = cell.icon.length;
                Glyphs.draw(c, cx, y + (H - ih) / 2, cell.icon, cell.main, cell.second);
                cx += Glyphs.width(cell.icon) + GAP;
            }
            c.drawText(mc.textRenderer, cell.text, cx, y + 3, 0xFFFFFFFF, false);
            if (cell.xp >= 0) {
                int lx0 = x + PAD, lx1 = x + cell.width - PAD;
                // The XP line sits clear below the text, with a gap, never touching it.
                c.fill(lx0, y + H - 2, lx1, y + H - 1, 0x40E0B96A);
                c.fill(lx0, y + H - 2, lx0 + Math.round((lx1 - lx0) * cell.xp), y + H - 1, 0xFFD9A441);
            }
            x += cell.width;
            if (i < cells.size() - 1) {
                c.fill(x, y + 3, x + 1, y + H - 3, SEP);
                x += 1;
            }
        }
    }

    private static Cell cell(String[] icon, int main, int second, Text text, float xp) {
        int tw = MinecraftClient.getInstance().textRenderer.getWidth(text);
        int w = PAD + (icon == null ? 0 : Glyphs.width(icon) + GAP) + tw + PAD;
        return new Cell(icon, main, second, text, w, xp);
    }
}
