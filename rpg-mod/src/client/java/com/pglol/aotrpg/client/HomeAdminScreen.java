package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Operators: every home and property plot with its owners. Search, page, teleport to view, and
 * for the selected one: give it to a player, take it back, or send a private offer at your price.
 */
public final class HomeAdminScreen extends Screen {
    private int left, top, w, h;
    private TextFieldWidget search, price;
    private static String query = "";
    private static boolean ownedOnly;
    private int selected = Integer.MIN_VALUE;
    private String player = "";

    public HomeAdminScreen() {
        super(Text.literal("Properties"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        String q = search != null ? search.getText() : query, pr = price != null ? price.getText() : "";
        clearAndInit();
        search.setText(q);
        price.setText(pr);
    }

    private void list(int page) {
        query = search.getText();
        ClientPlayNetworking.send(new Net.HomeAction("admin_list", 0, query + "|" + page + "|" + (ownedOnly ? "1" : "0")));
    }

    private static void act(String a, int idx, String arg) {
        ClientPlayNetworking.send(new Net.HomeAction(a, idx, arg));
    }

    @Override
    protected void init() {
        w = Math.min(620, width - 20);
        h = Math.min(330, height - 50);
        left = (width - w) / 2;
        top = Math.max(40, (height - h) / 2 + 10);
        Net.HomeAdminView v = ClientState.homeAdmin;
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        search = addDrawableChild(new TextFieldWidget(textRenderer, left + 10, top + 8, 160, 16, Text.literal("Search")));
        search.setPlaceholder(Text.literal("town, #, owner..."));
        search.setText(query);
        addDrawableChild(new AotButton(left + 174, top + 7, 60, 18, Text.literal("Search"), () -> list(0)));
        addDrawableChild(new AotButton(left + 238, top + 7, 90, 18, Text.literal(ownedOnly ? "Owned only" : "All"), () -> {
            ownedOnly = !ownedOnly;
            list(0);
        })).selected(ownedOnly);
        if (v == null) return;
        addDrawableChild(new AotButton(left + 334, top + 7, 24, 18, Text.literal("<"), () -> list(v.page() - 1))).active = v.page() > 0;
        addDrawableChild(new AotButton(left + 362, top + 7, 24, 18, Text.literal(">"), () -> list(v.page() + 1))).active = v.page() < v.pages() - 1;
        int listW = w - 200;
        int y = top + 32;
        int rows = (h - 44) / 16;
        for (int i = 0; i < Math.min(rows, v.rows().size()); i++) {
            Net.AdminRow r = v.rows().get(i);
            AotButton b = addDrawableChild(new AotButton(left + 8, y, listW - 60, 15, Text.literal(r.label()), () -> {
                selected = r.index();
                refresh();
            }));
            b.selected(selected == r.index());
            b.textScale = 0.8f;
            addDrawableChild(new AotButton(left + listW - 48, y, 40, 15, Text.literal("View"), () -> act("admin_tp", r.index(), "")));
            y += 16;
        }
        // Right side: actions for the selected property and a player picker.
        int rx = left + listW + 6, rw = w - listW - 14;
        int py = top + 60;
        for (String name : v.players()) {
            if (py > top + h - 90) break;
            addDrawableChild(new AotButton(rx, py, rw, 14, Text.literal(name), () -> {
                player = name;
                refresh();
            })).selected(name.equals(player));
            py += 15;
        }
        int ay = top + h - 84;
        price = addDrawableChild(new TextFieldWidget(textRenderer, rx, ay, rw, 16, Text.literal("Price")));
        price.setPlaceholder(Text.literal("offer price (Marks)"));
        price.setTextPredicate(s -> s.matches("\\d{0,9}"));
        boolean ready = selected != Integer.MIN_VALUE && !player.isEmpty();
        addDrawableChild(new AotButton(rx, ay + 20, rw, 18, Text.literal("Give to " + (player.isEmpty() ? "..." : player)),
            () -> act("admin_grant", selected, player))).active = ready;
        addDrawableChild(new AotButton(rx, ay + 40, rw, 18, Text.literal("Send offer"),
            () -> act("admin_offer", selected, player + "|" + (price.getText().isEmpty() ? "0" : price.getText())))).active = ready;
        AotButton take = addDrawableChild(new AotButton(rx, ay + 60, rw, 18, Text.literal("Take back from player"), () -> act("admin_revoke", selected, player)));
        take.active = ready;
        take.accent = Ui.RED;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.text(c, Ui.title("PROPERTY OFFICE"), width / 2f, top - 36, 1.2f, Ui.GOLD, true);
        Ui.panel(c, left, top, w, h);
        Net.HomeAdminView v = ClientState.homeAdmin;
        if (v == null) return;
        int listW = w - 200;
        Ui.text(c, Text.literal(String.format(Locale.ROOT, "%,d results · page %d/%d", v.total(), v.page() + 1, v.pages())), left + 392, top + 12, 0.7f, Ui.MUTED, false);
        int rx = left + listW + 6;
        Ui.text(c, Ui.heading("Selected"), rx, top + 32, 0.85f, Ui.GOLD, false);
        Net.AdminRow sel = null;
        for (Net.AdminRow r : v.rows()) if (r.index() == selected) sel = r;
        if (sel != null) {
            Ui.text(c, Text.literal(sel.size() + " · " + sel.price() + " M"), rx, top + 42, 0.6f, Ui.CREAM, false);
            Ui.text(c, Text.literal(sel.owners().isEmpty() ? "No owners" : "Owners: " + sel.owners()), rx, top + 50, 0.6f, Ui.MUTED, false);
        }
        // Owners next to each row.
        int y = top + 32;
        int rows = (h - 44) / 16;
        for (int i = 0; i < Math.min(rows, v.rows().size()); i++) {
            Net.AdminRow r = v.rows().get(i);
            if (!r.owners().isEmpty()) {
                String o = r.owners().length() > 26 ? r.owners().substring(0, 25) + "…" : r.owners();
                Ui.text(c, Text.literal(o), left + listW - 190, y + 4, 0.6f, 0xFF5BD35B, false);
            }
            y += 16;
        }
    }
}
