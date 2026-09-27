package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.text.Text;

/** Page arrows and the fill count over the stash (a chest screen with many pages). */
public final class StashPager {
    private StashPager() {}

    private static Net.StashInfo info = new Net.StashInfo(0, 1, 0, 0);

    public static void on(Net.StashInfo i) {
        info = i;
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, sw, sh) -> {
            if (!(screen instanceof GenericContainerScreen gs) || !"Stash".equals(screen.getTitle().getString())) return;
            int rows = gs.getScreenHandler().getRows();
            int bh = 114 + rows * 18, x = (sw - 176) / 2, y = (sh - bh) / 2;
            AotButton prev = new AotButton(x + 176 - 46, y - 20, 20, 16, Text.literal("<"), () -> page(-1));
            AotButton next = new AotButton(x + 176 - 22, y - 20, 20, 16, Text.literal(">"), () -> page(1));
            Screens.getButtons(screen).add(prev);
            Screens.getButtons(screen).add(next);
            ScreenEvents.afterRender(screen).register((s, c, mx, my, delta) -> {
                prev.active = info.page() > 0;
                next.active = info.page() < info.pages() - 1;
                String pg = (info.page() + 1) + " / " + info.pages();
                Ui.text(c, Text.literal(pg), x + 176 - 52 - Ui.font().getWidth(pg) * 0.85f, y - 15, 0.85f, Ui.CREAM, false);
                String used = info.used() + " / " + info.capacity();
                Ui.text(c, Ui.heading("Stash"), x + 2, y - 16, 1f, Ui.GOLD, false);
                Ui.text(c, Text.literal(used), x + 44, y - 15, 0.85f, info.used() >= info.capacity() ? Ui.RED : 0xFF9AD0FF, false);
            });
        });
    }

    private static void page(int d) {
        int n = info.page() + d;
        if (n < 0 || n >= info.pages()) return;
        ClientPlayNetworking.send(new Net.ExtractionAction("stash_page", String.valueOf(n)));
    }
}
