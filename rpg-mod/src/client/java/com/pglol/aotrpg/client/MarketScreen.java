package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A town market: local prices (Buy & Sell), the player Exchange, and selling gear for scrap. */
public final class MarketScreen extends Screen {
    private static final String[] TABS = {"Buy & Sell", "Exchange", "Sell Gear"};
    private static final String[] CATS = {"ALL", "FOOD", "INGREDIENT", "FISH", "MATERIAL", "SUPPLY", "TOOL", "LUXURY"};
    private static int tab, cat;
    private int scroll;
    private int left, top, w, h;
    private int listSlot = -1;
    private TextFieldWidget price, startBid, bidField;
    /** The listing picked to bid on, and the auction length for new listings. */
    private long selectedId = -1;
    private static int hours = 24;
    private static final int ROW = 22;

    /** The Global Market: players' listings, open anywhere from the pause menu. Town markets are NPC trade only. */
    private final boolean global;

    public MarketScreen() {
        this(false);
    }

    public MarketScreen(boolean global) {
        super(Text.literal(global ? "Global Market" : "Market"));
        this.global = global;
    }

    /** Things in your satchel you could list (story items can't be sold). */
    private static List<Integer> listable() {
        List<Integer> out = new ArrayList<>();
        for (var e : ClientState.bag.entrySet()) if (!com.pglol.aotrpg.Satchel.isStory(e.getValue())) out.add(e.getKey());
        return out;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        String keep = price != null ? price.getText() : "", keepStart = startBid != null ? startBid.getText() : "",
            keepBid = bidField != null ? bidField.getText() : "";
        clearAndInit();
        if (price != null) price.setText(keep);
        if (startBid != null) startBid.setText(keepStart);
        if (bidField != null && !keepBid.isEmpty()) bidField.setText(keepBid);
    }

    private static String left(long secs) {
        if (secs <= 0) return "ending";
        long h = secs / 3600, m = secs % 3600 / 60;
        return h > 0 ? h + "h " + m + "m" : m > 0 ? m + "m " + secs % 60 + "s" : secs + "s";
    }

    private Net.ExchangeEntry selected() {
        for (Net.ExchangeEntry e : ClientState.exchange) if (e.id() == selectedId) return e;
        return null;
    }

    private static void send(String action, String item, int qty, long number) {
        ClientPlayNetworking.send(new Net.MarketAction(action, item, qty, number));
    }

    private List<Net.MarketGood> goods() {
        Net.MarketView v = ClientState.market;
        List<Net.MarketGood> out = new ArrayList<>();
        if (v == null) return out;
        for (Net.MarketGood g : v.goods()) if (cat == 0 || g.category().equals(CATS[cat])) out.add(g);
        return out;
    }

    private int rows() {
        return Math.max(1, (h - 70) / ROW);
    }

    @Override
    protected void init() {
        w = Math.min(520, width - 20);
        h = Math.min(300, height - 60);
        left = (width - w) / 2;
        top = Math.max(44, (height - h) / 2 + 10);
        if (global) tab = 1;
        else if (tab == 1) tab = 0;
        for (int i = 0; i < TABS.length && !global; i++) {
            if (i == 1) continue;
            int t = i;
            addDrawableChild(new AotButton(left + (i == 2 ? 1 : i) * 110, top - 22, 106, 20, Ui.heading(TABS[i]), () -> {
                tab = t;
                scroll = 0;
                if (t == 1) send("exchange", "", 0, 0);
                clearAndInit();
            })).selected(tab == i);
        }
        addDrawableChild(new AotButton(left + w - 20, top - 22, 20, 20, Text.literal("✕"), this::close));
        if (tab == 0) initGoods();
        else if (tab == 1) initExchange();
        else initGear();
    }

    private void initGoods() {
        for (int i = 0; i < CATS.length; i++) {
            int c = i;
            String label = CATS[i].charAt(0) + CATS[i].substring(1).toLowerCase(Locale.ROOT);
            AotButton b = addDrawableChild(new AotButton(left + 8 + i * ((w - 16) / CATS.length), top + 26, (w - 16) / CATS.length - 2, 16,
                Text.literal(label), () -> {
                    cat = c;
                    scroll = 0;
                    clearAndInit();
                }));
            b.selected(cat == i);
        }
        List<Net.MarketGood> list = goods();
        int y = top + 48;
        for (int i = scroll; i < Math.min(list.size(), scroll + rows()); i++) {
            Net.MarketGood g = list.get(i);
            int bx = left + w - 186;
            addDrawableChild(new AotButton(bx, y + 2, 40, 18, Text.literal("Buy"), () -> send("buy", g.item(), hasShiftDown() ? 16 : 1, 0)));
            addDrawableChild(new AotButton(bx + 42, y + 2, 40, 18, Text.literal("x8"), () -> send("buy", g.item(), 8, 0)));
            AotButton s1 = addDrawableChild(new AotButton(bx + 92, y + 2, 40, 18, Text.literal("Sell"), () -> send("sell", g.item(), hasShiftDown() ? 16 : 1, 0)));
            AotButton sa = addDrawableChild(new AotButton(bx + 134, y + 2, 44, 18, Text.literal("All"), () -> send("sell", g.item(), 9999, 0)));
            s1.active = sa.active = g.have() > 0;
            y += ROW;
        }
    }

    private void initExchange() {
        List<Net.ExchangeEntry> list = ClientState.exchange;
        int y = top + 30;
        int n = rows() - 4;
        for (int i = scroll; i < Math.min(list.size(), scroll + n); i++) {
            Net.ExchangeEntry e = list.get(i);
            int bx = left + w - 96;
            if (e.mine()) {
                if (e.bids() == 0) {
                    AotButton b = addDrawableChild(new AotButton(bx, y + 2, 86, 18, Text.literal("Take back"), () -> send("cancel", "", 0, e.id())));
                    b.accent = Ui.RED;
                }
            } else {
                if (e.startBid() > 0) {
                    AotButton b = addDrawableChild(new AotButton(bx, y + 2, 42, 18, Text.literal("Bid"), () -> {
                        selectedId = e.id();
                        clearAndInit();
                        if (bidField != null) bidField.setText(String.valueOf(e.minBid()));
                    }));
                    b.selected = e.id() == selectedId;
                }
                if (e.price() > 0) {
                    addDrawableChild(new AotButton(bx + 44, y + 2, 42, 18, Text.literal("Buy"), () -> send("buylisting", "", 0, e.id())))
                        .accent = 0xFF5BD35B;
                }
            }
            y += ROW;
        }
        // Bidding on the chosen listing.
        Net.ExchangeEntry sel = selected();
        int ry = top + 30 + n * ROW + 4;
        if (sel != null && !sel.mine() && sel.startBid() > 0) {
            bidField = addDrawableChild(new TextFieldWidget(textRenderer, left + w - 200, ry, 90, 18, Text.literal("Bid")));
            bidField.setTextPredicate(t -> t.matches("\\d{0,9}"));
            bidField.setText(String.valueOf(sel.minBid()));
            addDrawableChild(new AotButton(left + w - 104, ry, 94, 18, Ui.heading("Place bid"), () -> {
                try {
                    long v = Long.parseLong(bidField.getText());
                    if (v > 0) send("bid", String.valueOf(sel.id()), 0, v);
                } catch (NumberFormatException ignored) { }
            })).accent = Ui.GOLD;
        } else {
            bidField = null;
        }
        // Listing an item: pick one from the satchel below, then a starting bid and/or a buy-now price.
        int by = top + h - 30;
        startBid = addDrawableChild(new TextFieldWidget(textRenderer, left + w - 330, by, 80, 18, Text.literal("Start bid")));
        startBid.setPlaceholder(Text.literal("start bid").withColor(Ui.DIM));
        startBid.setTextPredicate(t -> t.matches("\\d{0,8}"));
        price = addDrawableChild(new TextFieldWidget(textRenderer, left + w - 246, by, 80, 18, Text.literal("Buy now")));
        price.setPlaceholder(Text.literal("buy now").withColor(Ui.DIM));
        price.setTextPredicate(t -> t.matches("\\d{0,8}"));
        addDrawableChild(new AotButton(left + w - 162, by, 54, 18, Text.literal(hours + "h"), () -> {
            hours = hours == 12 ? 24 : hours == 24 ? 48 : 12;
            refresh();
        })).setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("How long the auction runs (buy-now only listings last 3 days)")));
        addDrawableChild(new AotButton(left + w - 104, by, 94, 18, Ui.heading("List item"), () -> {
            long p = 0, st = 0;
            try {
                if (!price.getText().isEmpty()) p = Long.parseLong(price.getText());
                if (!startBid.getText().isEmpty()) st = Long.parseLong(startBid.getText());
            } catch (NumberFormatException ignored) { }
            if (listSlot >= 0 && (p > 0 || st > 0)) send("list", st > 0 ? st + ":" + hours : "", listSlot, p);
            listSlot = -1;
        }));
    }

    private void initGear() {
        Net.MarketView v = ClientState.market;
        if (v == null) return;
        int y = top + 30;
        for (Net.GearOffer g : v.gear()) {
            if (y > top + h - 26) break;
            addDrawableChild(new AotButton(left + w - 110, y + 2, 100, 18, Text.literal("Sell " + g.value() + " M"),
                () -> send("sellgear", "", g.slot(), 0)));
            y += ROW;
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        int size = tab == 0 ? goods().size() : tab == 1 ? ClientState.exchange.size() : 0;
        int max = Math.max(0, size - rows() + (tab == 1 ? 4 : 0));
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(vy)));
        clearAndInit();
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // Exchange tab: click one of your inventory items to choose it for listing.
        if (tab == 1 && client.player != null) {
            int gx = left + 10, gy = top + h - 52;
            List<Integer> items = listable();
            for (int i = 0; i < Math.min(36, items.size()); i++) {
                int x = gx + (i % 18) * 18, y = gy + (i / 18) * 18 - 18;
                if (mx >= x && mx < x + 16 && my >= y && my < y + 16) {
                    listSlot = com.pglol.aotrpg.Satchel.BAG + items.get(i);
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Net.MarketView v = ClientState.market;
        Ui.text(c, Ui.title(global ? "GLOBAL MARKET" : v == null ? "MARKET" : v.town().toUpperCase(Locale.ROOT) + " MARKET"), width / 2f, top - 44, 1.2f, Ui.GOLD, true);
        if (v != null && !global) {
            String sub = v.sector() + (v.controller().isEmpty() ? "  ·  contested" : "  ·  held by the " + v.controller())
                + (v.discount() > 0 ? "  ·  your faction: " + v.discount() + "% better prices" : "");
            Ui.text(c, Text.literal(sub), width / 2f, top - 32, 0.75f, Ui.MUTED, true);
        }
        Ui.panel(c, left, top, w, h);
        String purse = String.format(Locale.ROOT, "%,d Marks", ClientState.marks);
        Ui.text(c, Text.literal(purse), left + w - 10 - Ui.font().getWidth(purse), top + 8, 1f, Ui.GOLD, false);
        if (tab == 0) drawGoods(c, mouseX, mouseY);
        else if (tab == 1) drawExchange(c, mouseX, mouseY);
        else drawGear(c, mouseX, mouseY);
    }

    private static ItemStack stack(String id) {
        Identifier i = Identifier.tryParse(id);
        return i == null ? ItemStack.EMPTY : new ItemStack(Registries.ITEM.get(i));
    }

    private void drawGoods(DrawContext c, int mx, int my) {
        Ui.text(c, Ui.heading("Goods"), left + 10, top + 8, 1f, Ui.GOLD, false);
        List<Net.MarketGood> list = goods();
        int y = top + 48;
        Ui.text(c, Text.literal("Item"), left + 32, y - 8, 0.6f, Ui.MUTED, false);
        Ui.text(c, Text.literal("Stock"), left + w - 330, y - 8, 0.6f, Ui.MUTED, false);
        Ui.text(c, Text.literal("Buy"), left + w - 280, y - 8, 0.6f, Ui.MUTED, false);
        Ui.text(c, Text.literal("Sell"), left + w - 232, y - 8, 0.6f, Ui.MUTED, false);
        for (int i = scroll; i < Math.min(list.size(), scroll + rows()); i++) {
            Net.MarketGood g = list.get(i);
            if ((i & 1) == 0) c.fill(left + 6, y, left + w - 6, y + ROW, 0x22000000);
            ItemStack st = stack(g.item());
            c.drawItem(st, left + 10, y + 3);
            Ui.text(c, st.getName(), left + 32, y + 3, 0.85f, Ui.CREAM, false);
            Ui.text(c, Text.literal("You have " + g.have()), left + 32, y + 12, 0.6f, Ui.MUTED, false);
            Ui.text(c, Text.literal(String.valueOf(g.stock())), left + w - 330, y + 7, 0.8f, g.stock() < 16 ? Ui.RED : Ui.CREAM, false);
            int trendCol = g.trend() > 15 ? Ui.RED : g.trend() < -15 ? 0xFF5BD35B : Ui.MUTED;
            Ui.text(c, Text.literal(g.buy() + " M"), left + w - 280, y + 4, 0.85f, Ui.GOLD, false);
            Ui.text(c, Text.literal((g.trend() >= 0 ? "▲" : "▼") + Math.abs(g.trend()) + "%"), left + w - 280, y + 13, 0.55f, trendCol, false);
            Ui.text(c, Text.literal(g.sell() + " M"), left + w - 232, y + 7, 0.85f, 0xFF9FD8A0, false);
            if (mx >= left + 10 && mx < left + 26 && my >= y + 3 && my < y + 19) c.drawItemTooltip(textRenderer, st, mx, my);
            y += ROW;
        }
        Ui.text(c, Text.literal("Shift: 16 at a time. Prices rise as stock runs low and fall when it floods. Every town is different."),
            left + 10, top + h - 12, 0.6f, Ui.MUTED, false);
    }

    private void drawExchange(DrawContext c, int mx, int my) {
        Ui.text(c, Ui.heading("Player listings"), left + 10, top + 8, 1f, Ui.GOLD, false);
        Ui.text(c, Text.literal("Bid or buy now. 5% fee on sales. Outbid Marks come straight back; wins and sales arrive when you open this."),
            left + 120, top + 10, 0.6f, Ui.MUTED, false);
        List<Net.ExchangeEntry> list = ClientState.exchange;
        int y = top + 30;
        int n = rows() - 4;
        int cBid = left + w - 330, cBuy = left + w - 236, cTime = left + w - 168;
        Ui.text(c, Text.literal("Bids"), cBid, y - 8, 0.6f, Ui.MUTED, false);
        Ui.text(c, Text.literal("Buy now"), cBuy, y - 8, 0.6f, Ui.MUTED, false);
        Ui.text(c, Text.literal("Ends"), cTime, y - 8, 0.6f, Ui.MUTED, false);
        if (list.isEmpty()) Ui.text(c, Text.literal("Nothing listed yet."), left + 14, y + 6, 0.8f, Ui.MUTED, false);
        for (int i = scroll; i < Math.min(list.size(), scroll + n); i++) {
            Net.ExchangeEntry e = list.get(i);
            if (e.id() == selectedId) c.fill(left + 6, y, left + w - 6, y + ROW, 0x40E0B96A);
            else if ((i & 1) == 0) c.fill(left + 6, y, left + w - 6, y + ROW, 0x22000000);
            GearUi.backing(c, e.item(), left + 10, y + 3);
            c.drawItem(e.item(), left + 10, y + 3);
            c.drawItemInSlot(textRenderer, e.item(), left + 10, y + 3);
            Ui.text(c, Text.literal(textRenderer.trimToWidth(e.item().getName().getString(), (int) ((cBid - left - 40) / 0.85f))),
                left + 32, y + 3, 0.85f, Ui.CREAM, false);
            Ui.text(c, Text.literal("by " + e.seller() + (e.mine() ? " (you)" : "")), left + 32, y + 12, 0.6f, Ui.MUTED, false);
            if (e.startBid() > 0) {
                if (e.bids() > 0) {
                    Ui.text(c, Text.literal(String.format(Locale.ROOT, "%,d M", e.bid())), cBid, y + 3, 0.8f, Ui.GOLD, false);
                    Ui.text(c, Text.literal(e.leading() ? "you lead" : e.bids() + (e.bids() == 1 ? " bid" : " bids") + " · " + e.bidder()),
                        cBid, y + 12, 0.55f, e.leading() ? 0xFF5BD35B : Ui.MUTED, false);
                } else {
                    Ui.text(c, Text.literal(String.format(Locale.ROOT, "%,d M", e.startBid())), cBid, y + 3, 0.8f, Ui.CREAM, false);
                    Ui.text(c, Text.literal("no bids yet"), cBid, y + 12, 0.55f, Ui.MUTED, false);
                }
            } else {
                Ui.text(c, Text.literal("—"), cBid, y + 7, 0.8f, Ui.DIM, false);
            }
            Ui.text(c, Text.literal(e.price() > 0 ? String.format(Locale.ROOT, "%,d M", e.price()) : "—"), cBuy, y + 7, 0.8f,
                e.price() > 0 ? 0xFF9FD8A0 : Ui.DIM, false);
            Ui.text(c, Text.literal(left(e.endsIn())), cTime, y + 7, 0.7f, e.endsIn() < 600 ? Ui.RED : Ui.CREAM, false);
            if (mx >= left + 10 && mx < left + 26 && my >= y + 3 && my < y + 19) c.drawItemTooltip(textRenderer, e.item(), mx, my);
            y += ROW;
        }
        Net.ExchangeEntry sel = selected();
        if (sel != null && !sel.mine() && sel.startBid() > 0) {
            int ry = top + 30 + n * ROW + 4;
            Ui.text(c, Text.literal("Your bid on " + sel.item().getName().getString() + "  ·  at least "
                + String.format(Locale.ROOT, "%,d", sel.minBid()) + " M"), left + 10, ry + 5, 0.75f, Ui.CREAM, false);
        }
        // Your inventory, to pick something to list.
        if (client.player == null) return;
        int gx = left + 10, gy = top + h - 52;
        Ui.text(c, Text.literal("Pick an item from your satchel, then set a start bid, a buy-now price, or both:"), gx, gy - 28, 0.65f, Ui.MUTED, false);
        List<Integer> items = listable();
        for (int i = 0; i < 36; i++) {
            int x = gx + (i % 18) * 18, yy = gy + (i / 18) * 18 - 18;
            Ui.slot(c, x - 1, yy - 1);
            ItemStack s = i < items.size() ? ClientState.bag.get(items.get(i)) : ItemStack.EMPTY;
            if (i < items.size() && com.pglol.aotrpg.Satchel.BAG + items.get(i) == listSlot) c.drawBorder(x - 1, yy - 1, 18, 18, Ui.GOLD);
            if (!s.isEmpty()) {
                c.drawItem(s, x, yy);
                c.drawItemInSlot(textRenderer, s, x, yy);
                if (mx >= x && mx < x + 16 && my >= yy && my < yy + 16) c.drawItemTooltip(textRenderer, s, mx, my);
            }
        }
    }

    private void drawGear(DrawContext c, int mx, int my) {
        Ui.text(c, Ui.heading("Sell gear"), left + 10, top + 8, 1f, Ui.GOLD, false);
        Net.MarketView v = ClientState.market;
        if (v == null || client.player == null) return;
        int y = top + 30;
        if (v.gear().isEmpty()) Ui.text(c, Text.literal("No gear in your backpack."), left + 14, y + 6, 0.8f, Ui.MUTED, false);
        for (Net.GearOffer g : v.gear()) {
            if (y > top + h - 26) break;
            ItemStack s = ClientState.stackAt(g.slot());
            GearUi.backing(c, s, left + 10, y + 3);
            c.drawItem(s, left + 10, y + 3);
            Ui.text(c, s.getName(), left + 32, y + 7, 0.85f, Ui.CREAM, false);
            if (mx >= left + 10 && mx < left + 26 && my >= y + 3 && my < y + 19) c.drawItemTooltip(textRenderer, s, mx, my);
            y += ROW;
        }
    }
}
