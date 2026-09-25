package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** The special event shop: limited items for event tokens (and some for Gold). */
public final class EventShopScreen extends Screen {
    private static final int CARD_W = 196, CARD_H = 64;
    private int left, top, w, h, cols, page;

    public EventShopScreen() {
        super(Text.literal("Event Shop"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        clearAndInit();
    }

    private int perPage() {
        return cols * Math.max(1, (h - 60) / (CARD_H + 6));
    }

    @Override
    protected void init() {
        w = Math.min(620, width - 20);
        h = Math.min(330, height - 70);
        left = (width - w) / 2;
        top = Math.max(50, (height - h) / 2);
        cols = Math.max(1, (w - 12) / (CARD_W + 6));
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        Net.EventView v = ClientState.event;
        if (v == null) return;
        int per = perPage(), pages = Math.max(1, (v.items().size() + per - 1) / per);
        page = Math.max(0, Math.min(pages - 1, page));
        if (pages > 1) {
            addDrawableChild(new AotButton(left + w / 2 - 60, top + h - 24, 20, 18, Text.literal("<"), () -> {
                page--;
                clearAndInit();
            })).active = page > 0;
            addDrawableChild(new AotButton(left + w / 2 + 40, top + h - 24, 20, 18, Text.literal(">"), () -> {
                page++;
                clearAndInit();
            })).active = page < pages - 1;
        }
        int gx = left + (w - cols * (CARD_W + 6) + 6) / 2;
        for (int i = 0; i < per && page * per + i < v.items().size(); i++) {
            Net.EventItem it = v.items().get(page * per + i);
            int x = gx + (i % cols) * (CARD_W + 6), y = top + 36 + (i / cols) * (CARD_H + 6);
            boolean soldOut = it.limit() > 0 && it.bought() >= it.limit();
            int bx = x + CARD_W - 76;
            if (it.tokens() > 0) {
                AotButton b = addDrawableChild(new AotButton(bx, y + 18, 70, 18, Text.literal(it.tokens() + " tokens"),
                    () -> ClientPlayNetworking.send(new Net.EventAction("buy", it.key()))));
                b.active = v.running() && !soldOut && v.tokens() >= it.tokens();
            }
            if (it.gold() > 0) {
                AotButton b = addDrawableChild(new AotButton(bx, y + 40, 70, 18, Text.literal(it.gold() + " Gold"),
                    () -> ClientPlayNetworking.send(new Net.EventAction("buy_gold", it.key()))));
                b.active = v.running() && !soldOut && ClientState.gold >= it.gold();
            }
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        c.fillGradient(0, 0, width, height / 2, 0x30A040C0, 0x00000000);
        Net.EventView v = ClientState.event;
        Ui.text(c, Ui.title(v == null ? "EVENT SHOP" : v.name().toUpperCase(java.util.Locale.ROOT)), width / 2f, top - 42, 1.3f, 0xFFD9A0FF, true);
        Ui.panel(c, left, top, w, h);
        if (v == null) return;
        String tokens = v.tokens() + " " + v.token() + (v.tokens() == 1 ? "" : "s");
        Ui.text(c, Text.literal(tokens), left + 10, top + 10, 1f, 0xFFD9A0FF, false);
        Ui.text(c, Text.literal("Gold " + ClientState.gold), left + 10, top + 22, 0.7f, 0xFFFFD54A, false);
        String state = !v.running() ? "The event is over" : v.endsAt() > 0 ? "Ends in " + BattlePassScreen.days(v.endsAt()) : "Limited time";
        Ui.text(c, Text.literal(state), left + w - 10 - Ui.font().getWidth(state) * 0.75f, top + 12, 0.75f, v.running() ? Ui.MUTED : Ui.RED, false);
        int per = perPage();
        int gx = left + (w - cols * (CARD_W + 6) + 6) / 2;
        for (int i = 0; i < per && page * per + i < v.items().size(); i++) {
            Net.EventItem it = v.items().get(page * per + i);
            int x = gx + (i % cols) * (CARD_W + 6), y = top + 36 + (i / cols) * (CARD_H + 6);
            boolean soldOut = it.limit() > 0 && it.bought() >= it.limit();
            c.fill(x, y, x + CARD_W, y + CARD_H, soldOut ? 0x30000000 : 0x50000000);
            Ui.border(c, x, y, CARD_W, CARD_H);
            if (it.color() != 0) c.fill(x + 1, y + 1, x + 3, y + CARD_H - 1, 0xFF000000 | it.color());
            Ui.item(c, BattlePassScreen.icon(it.icon()), x + 8, y + 8, 1.5f);
            Ui.text(c, Ui.heading(it.title()), x + 38, y + 6, 0.85f, soldOut ? Ui.DIM : Ui.CREAM, false);
            Ui.wrapped(c, Text.literal(it.desc()), x + 38, y + 20, CARD_W - 118, Ui.MUTED);
            String lim = soldOut ? "Sold out" : it.limit() > 0 ? (it.limit() - it.bought()) + " left" : "";
            if (!lim.isEmpty()) Ui.text(c, Text.literal(lim), x + 8, y + CARD_H - 12, 0.6f, soldOut ? Ui.RED : Ui.MUTED, false);
        }
        Ui.text(c, Text.literal("Tokens drop from titans and completed quests while the event runs."), left + 10, top + h - 14, 0.6f, Ui.MUTED, false);
    }
}
