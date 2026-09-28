package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The player list (Tab): the server's name and how many are on across the top, then everyone
 * grouped by where they are (the open world, homes, the balloons, each island), each with their
 * level badge, character name and ping.
 */
public final class PlayerList {
    private PlayerList() {}

    private static Map<UUID, Net.RosterEntry> roster = new LinkedHashMap<>();
    private static long openedAt;
    private static boolean wasOpen;

    public static void on(Net.Roster r) {
        Map<UUID, Net.RosterEntry> m = new LinkedHashMap<>();
        for (Net.RosterEntry e : r.players()) m.put(e.id(), e);
        roster = m;
    }

    /** Level badge colour: grey, green, blue, purple, gold as levels climb. */
    private static int tier(int lv) {
        if (lv >= 40) return 0xFFF2C14E;
        if (lv >= 30) return 0xFFC77DFF;
        if (lv >= 20) return 0xFF5A9AE0;
        if (lv >= 10) return 0xFF5BD35B;
        return 0xFF8F8A7A;
    }

    private static int placeColor(String where) {
        return switch (where) {
            case "Open World" -> 0xFFE0B96A;
            case "Home" -> 0xFF9AD0FF;
            case "Balloon" -> 0xFFE0823A;
            case "Verdant Reach" -> 0xFF5BD35B;
            case "Ashen Crags" -> 0xFFE0823A;
            case "Frostfell" -> 0xFF9AD0FF;
            default -> Ui.CREAM;
        };
    }

    public static void render(DrawContext c, int sw) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return;
        long now = Util.getMeasuringTimeMs();
        if (!wasOpen) openedAt = now;
        wasOpen = true;
        float in = Math.min(1, (now - openedAt) / 180f);
        // Everyone connected, with the roster's details where we have them.
        Map<String, List<Object[]>> groups = new LinkedHashMap<>();
        int total = 0;
        for (PlayerListEntry e : mc.getNetworkHandler().getListedPlayerListEntries()) {
            total++;
            Net.RosterEntry r = roster.get(e.getProfile().getId());
            String where = r == null ? "Online" : r.where();
            groups.computeIfAbsent(where, k -> new ArrayList<>()).add(new Object[] {e, r});
        }
        int colW = 190, cols = Math.max(1, Math.min(2, (total + 11) / 12)), w = colW * cols + 20;
        int x0 = (sw - w) / 2, y0 = 8 - (int) ((1 - in) * 20);
        int rows = 0;
        for (var g : groups.values()) rows += g.size() + 1;
        int perCol = (rows + cols - 1) / cols;
        int h = 44 + perCol * 13 + 8;
        int alpha = (int) (230 * in);
        c.fill(x0, y0, x0 + w, y0 + h, (alpha << 24) | 0x0D0F0C);
        c.fill(x0, y0, x0 + w, y0 + 2, (alpha << 24) | 0xE0B96A);
        c.drawBorder(x0, y0, w, h, (alpha << 24) | 0x5A4630);
        // The header: the server's name and the count.
        Ui.text(c, Ui.title("ATTACK ON TITAN"), x0 + 12, y0 + 10, 1.2f, Ui.GOLD, false);
        String online = total + " online";
        int ow = (int) (Ui.font().getWidth(online) * 0.9f) + 16;
        c.fill(x0 + w - ow - 10, y0 + 9, x0 + w - 10, y0 + 23, 0xC01B2A1B);
        c.fill(x0 + w - ow - 6, y0 + 14, x0 + w - ow - 2, y0 + 18, 0xFF5BD35B);
        Ui.text(c, Text.literal(online), x0 + w - ow + 2, y0 + 12, 0.9f, 0xFF9AE09A, false);
        c.fill(x0 + 10, y0 + 30, x0 + w - 10, y0 + 31, 0x40E0B96A);
        int col = 0, row = 0;
        for (var g : groups.entrySet()) {
            int gx = x0 + 10 + col * colW, gy = y0 + 36 + row * 13;
            Ui.text(c, Ui.heading(g.getKey() + "  " + g.getValue().size()), gx, gy + 2, 0.7f, placeColor(g.getKey()), false);
            row++;
            for (Object[] o : g.getValue()) {
                if (row >= perCol) {
                    row = 0;
                    col++;
                }
                PlayerListEntry e = (PlayerListEntry) o[0];
                Net.RosterEntry r = (Net.RosterEntry) o[1];
                int x = x0 + 10 + col * colW, y = y0 + 36 + row * 13;
                boolean me = mc.player != null && e.getProfile().getId().equals(mc.player.getUuid());
                c.fill(x, y, x + colW - 8, y + 12, me ? 0x50E0B96A : 0x30000000);
                int lv = r == null ? 0 : r.level();
                String lvs = lv > 0 ? String.valueOf(lv) : "-";
                c.fill(x + 1, y + 1, x + 19, y + 11, (tier(lv) & 0xFFFFFF) | 0x50000000);
                c.drawBorder(x + 1, y + 1, 18, 10, tier(lv));
                Ui.text(c, Text.literal(lvs), x + 10, y + 3, 0.6f, tier(lv), true);
                String name = r != null ? r.name() : e.getProfile().getName();
                Ui.text(c, Text.literal(name), x + 24, y + 2, 0.75f, r != null ? r.color() | 0xFF000000 : Ui.CREAM, false);
                // Ping bars.
                int ping = e.getLatency(), bars = ping < 0 ? 0 : ping < 80 ? 4 : ping < 150 ? 3 : ping < 300 ? 2 : 1;
                int bx = x + colW - 26;
                for (int b = 0; b < 4; b++) {
                    int bh = 2 + b * 2;
                    c.fill(bx + b * 3, y + 10 - bh, bx + b * 3 + 2, y + 10, b < bars ? (bars >= 3 ? 0xFF5BD35B : bars == 2 ? 0xFFE0B96A : 0xFFE03A3A) : 0x40FFFFFF);
                }
                row++;
            }
        }
    }

    /** Tab released. */
    public static void closed() {
        wasOpen = false;
    }
}
