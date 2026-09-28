package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Gear;
import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The stash, side by side with the satchel, in the satchel's own style: the same categories
 * across the top (plus All), a search box and a sort. Click a stash card to wear or equip it (the
 * loadout strip along the bottom shows what you'll carry; click a place there to send it back),
 * right-click to put it in the satchel; shift-click sends everything showing on that side.
 */
public final class StashScreen extends Screen {
    private static final String[] SORTS = {"Quality", "Level", "Name", "Count"};
    private static int tab = -1, sort;
    /** Disassemble mode: clicking gear breaks it down into Salvage and metal instead of moving it. */
    private static boolean scrap;
    private static Map<Integer, ItemStack> stash = new TreeMap<>();
    private static Net.StashView view;
    private int scrollL, scrollR;
    private int top, panelW, cols, rows;
    private static final int CW = 30, GAP = 4;
    /** The loadout strip along the bottom: armor, off hand, then the nine loadout slots. */
    private static final int LS = 22, LOAD_H = 40;
    private static final int[] PLACES = {103, 102, 101, 100, 40, 0, 1, 2, 3, 4, 5, 6, 7, 8};
    private TextFieldWidget search;
    private String query = "";

    public StashScreen(Net.StashView v) {
        super(Text.literal("Stash"));
        take(v);
    }

    private static void take(Net.StashView v) {
        view = v;
        Map<Integer, ItemStack> m = new TreeMap<>();
        for (Net.BagEntry e : v.items()) m.put(e.slot(), e.stack());
        stash = m;
    }

    public static void update(Net.StashView v) {
        take(v);
        if (MinecraftClient.getInstance().currentScreen instanceof StashScreen s) s.refresh();
    }

    public void refresh() {
        String q = search == null ? query : search.getText();
        clearAndInit();
        if (search != null) search.setText(q);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        ClientPlayNetworking.send(new Net.StashAction("close", 0));
    }

    private static void act(String a, int slot) {
        ClientPlayNetworking.send(new Net.StashAction(a, slot));
    }

    // ------------------------------------------------------------------ what shows

    private boolean passes(ItemStack s) {
        if (tab >= 0 && BagScreen.category(s) != tab) return false;
        return query.isEmpty() || s.getName().getString().toLowerCase(Locale.ROOT).contains(query);
    }

    private List<Integer> shown(Map<Integer, ItemStack> src) {
        List<Integer> out = new ArrayList<>();
        for (var e : src.entrySet()) if (passes(e.getValue())) out.add(e.getKey());
        Comparator<Integer> c = switch (sort) {
            case 1 -> Comparator.comparingInt((Integer k) -> -BagScreen.level(src.get(k)));
            case 2 -> Comparator.comparing((Integer k) -> src.get(k).getName().getString());
            case 3 -> Comparator.comparingInt((Integer k) -> -src.get(k).getCount());
            default -> Comparator.comparingInt((Integer k) -> -BagScreen.quality(src.get(k))).thenComparingInt(k -> -BagScreen.level(src.get(k)));
        };
        out.sort(c);
        return out;
    }

    // ------------------------------------------------------------------ layout

    private int leftX() { return 14; }
    private int rightX() { return width - 14 - panelW; }

    @Override
    protected void init() {
        top = 78;
        panelW = (width - 14 * 2 - 24) / 2;
        cols = Math.max(3, (panelW - 8) / (CW + GAP));
        rows = Math.max(1, (height - top - 34 - LOAD_H) / (CW + GAP));
        // Category tabs: All, then the satchel's own.
        int n = BagScreen.TABS.length + 1, tw = Math.min(110, (width - 28) / n);
        for (int i = -1; i < BagScreen.TABS.length; i++) {
            int t = i;
            String name = i < 0 ? "All" : BagScreen.TABS[i];
            AotButton b = addDrawableChild(new AotButton(14 + (i + 1) * tw, 34, tw - 3, 18, Text.literal(name), () -> {
                tab = t;
                scrollL = scrollR = 0;
                refresh();
            }));
            b.textScale = 0.75f;
            b.selected(tab == t);
            if (i >= 0) b.icon(new ItemStack(BagScreen.TAB_ICONS[i]));
        }
        search = addDrawableChild(new TextFieldWidget(textRenderer, 14, 56, 150, 16, Text.literal("Search")));
        search.setPlaceholder(Text.literal("Search..."));
        search.setText(query);
        search.setChangedListener(q -> {
            query = q.toLowerCase(Locale.ROOT).trim();
            scrollL = scrollR = 0;
        });
        AotButton so = addDrawableChild(new AotButton(170, 56, 96, 16, Text.literal("Sort: " + SORTS[sort]), () -> {
            sort = (sort + 1) % SORTS.length;
            refresh();
        }));
        so.textScale = 0.75f;
        AotButton tidy = addDrawableChild(new AotButton(leftX() + panelW - 70, 56, 70, 16, Text.literal("Tidy"), () -> act("sort", 0)));
        tidy.textScale = 0.75f;
        AotButton sc = addDrawableChild(new AotButton(rightX() + panelW - 110, 56, 110, 16, Text.literal(scrap ? "Disassembling" : "Disassemble"), () -> {
            scrap = !scrap;
            refresh();
        }));
        sc.textScale = 0.75f;
        sc.selected(scrap);
        sc.accent = Ui.RED;
        if (view != null && view.rowCost() >= 0) {
            AotButton ex = addDrawableChild(new AotButton(leftX() + panelW - 200, 56, 126, 16,
                Text.literal("+9 slots · " + String.format(Locale.ROOT, "%,d", view.rowCost()) + " Salvage"), () -> act("expand", 0)));
            ex.textScale = 0.7f;
            ex.active = view.salvage() >= view.rowCost();
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        int d = -(int) Math.signum(vy);
        if (mx < width / 2.0) scrollL = clamp(scrollL + d, shown(stash).size());
        else scrollR = clamp(scrollR + d, shown(ClientState.bag).size());
        return true;
    }

    private int clamp(int s, int count) {
        int total = (count + cols - 1) / cols;
        return Math.max(0, Math.min(Math.max(0, total - rows), s));
    }

    /** Which card is under the mouse: {side (0 stash, 1 satchel), key} or null. */
    private int[] at(double mx, double my) {
        for (int side = 0; side < 2; side++) {
            int x0 = side == 0 ? leftX() : rightX();
            List<Integer> list = shown(side == 0 ? stash : ClientState.bag);
            int scroll = side == 0 ? scrollL : scrollR;
            int cx = (int) ((mx - x0 - 4) / (CW + GAP)), cy = (int) ((my - top) / (CW + GAP));
            if (mx < x0 + 4 || cx >= cols || my < top || cy >= rows) continue;
            int idx = (scroll + cy) * cols + cx;
            if (idx >= 0 && idx < list.size()) return new int[] {side, list.get(idx)};
        }
        return null;
    }

    private int loadY() { return height - LOAD_H + 8; }

    private int placeX(int i) {
        int total = PLACES.length * (LS + 3) + 2 * 8;
        int x0 = width / 2 - total / 2;
        // A gap after the armor and after the off hand.
        return x0 + i * (LS + 3) + (i >= 4 ? 8 : 0) + (i >= 5 ? 8 : 0);
    }

    /** Which worn / loadout place is under the mouse, or -1. */
    private int placeAt(double mx, double my) {
        int y = loadY();
        if (my < y || my >= y + LS) return -1;
        for (int i = 0; i < PLACES.length; i++) if (mx >= placeX(i) && mx < placeX(i) + LS) return PLACES[i];
        return -1;
    }

    /** What's in a place as this client sees it (the sheathed grip when the hand is empty). */
    private ItemStack placed(int place) {
        var pl = client == null ? null : client.player;
        if (pl == null) return ItemStack.EMPTY;
        ItemStack s = switch (place) {
            case 100 -> pl.getInventory().armor.get(0);
            case 101 -> pl.getInventory().armor.get(1);
            case 102 -> pl.getInventory().armor.get(2);
            case 103 -> pl.getInventory().armor.get(3);
            case 40 -> pl.getOffHandStack();
            default -> pl.getInventory().main.get(place);
        };
        if (s.isEmpty() && (place == 0 || place == 40)) {
            Net.SheathState st = ClientState.sheaths.get(pl.getUuid());
            if (st != null) s = place == 0 ? st.a() : st.b();
        }
        return s;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        int place = placeAt(mx, my);
        if (place >= 0) {
            if (!placed(place).isEmpty()) {
                act("unequip", place);
                if (client != null && client.player != null) client.player.playSound(net.minecraft.sound.SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(), 0.5f, 0.9f);
            }
            return true;
        }
        int[] hit = at(mx, my);
        if (hit == null) return false;
        if (scrap) {
            ItemStack s = (hit[0] == 0 ? stash : ClientState.bag).get(hit[1]);
            if (s == null || !Gear.isGear(s)) return true;
            act(hit[0] == 0 ? "scrap_stash" : "scrap_bag", hit[1]);
            return true;
        }
        // A stash item: click to wear or equip it, right-click to put it in the satchel.
        String a = hit[0] == 0 ? (button == 1 ? "take" : "equip") : "put";
        if (hasShiftDown()) {
            if (hit[0] == 0) a = "take";
            // Everything showing on that side goes across.
            for (int k : shown(hit[0] == 0 ? stash : ClientState.bag)) act(a, k);
        } else act(a, hit[1]);
        if (client != null && client.player != null) client.player.playSound(net.minecraft.sound.SoundEvents.ITEM_BUNDLE_INSERT, 0.5f, hit[0] == 0 ? 1.2f : 0.9f);
        return true;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.planks(c, width, height);
        c.fill(0, 0, width, 30, Ui.LEATHER);
        c.fill(0, 30, width, 31, 0xFF0A0706);
        for (int i = 4; i < width - 4; i += 4) c.fill(i, 26, i + 2, 27, Ui.STITCH);
        c.drawText(textRenderer, Ui.title("Stash"), leftX(), 11, Ui.GOLD, false);
        if (view != null) {
            String load = stash.size() + " / " + view.capacity();
            c.drawText(textRenderer, load, leftX() + 50, 11, stash.size() >= view.capacity() ? Ui.RED : Ui.CREAM, false);
        }
        Text sat = Ui.title("Satchel");
        c.drawText(textRenderer, sat, rightX(), 11, Ui.GOLD, false);
        String bl = ClientState.bag.size() + " / " + ClientState.bagSize;
        c.drawText(textRenderer, bl, rightX() + textRenderer.getWidth(sat) + 10, 11, ClientState.bag.size() >= ClientState.bagSize ? Ui.RED : Ui.CREAM, false);
        // The two wells.
        int bh = rows * (CW + GAP) + 6;
        Ui.well(c, leftX(), top - 4, panelW, bh);
        Ui.well(c, rightX(), top - 4, panelW, bh);
        // The arrows between them.
        int mx = width / 2;
        Ui.text(c, Text.literal("⇄"), mx, top + bh / 2f - 6, 1.4f, Ui.TRIM, true);
        drawSide(c, 0, mouseX, mouseY);
        drawSide(c, 1, mouseX, mouseY);
        drawLoadout(c, mouseX, mouseY);
    }

    /** What you'll carry: armor, off hand and the loadout bar, straight from the stash. */
    private void drawLoadout(DrawContext c, int mouseX, int mouseY) {
        int y = loadY();
        int x0 = placeX(0) - 8, x1 = placeX(PLACES.length - 1) + LS + 8;
        c.fill(x0, y - 6, x1, y + LS + 5, 0xC80B0F0C);
        c.fill(x0, y - 7, x1, y - 6, Ui.TRIM);
        Ui.text(c, Ui.heading("LOADOUT"), x0 - 6 - textRenderer.getWidth("LOADOUT") * 0.75f, y + LS / 2f - 3, 0.75f, Ui.GOLD, false);
        for (int i = 0; i < PLACES.length; i++) {
            int place = PLACES[i], x = placeX(i);
            boolean hov = mouseX >= x && mouseX < x + LS && mouseY >= y && mouseY < y + LS;
            int col = place < 9 ? LoadoutUi.color(com.pglol.aotrpg.Loadout.SLOTS[place]) : place == 40 ? 0xFF8F8A7A : Ui.TRIM;
            c.fill(x, y, x + LS, y + LS, 0xE0121612);
            c.drawBorder(x, y, LS, LS, (hov ? 0xFF : 0x90) << 24 | (col & 0xFFFFFF));
            ItemStack s = placed(place);
            if (!s.isEmpty()) {
                GearUi.backing(c, s, x + 3, y + 3);
                c.drawItem(s, x + 3, y + 3);
                c.drawItemInSlot(textRenderer, s, x + 3, y + 3);
            } else if (place < 9) {
                LoadoutUi.drawGhost(c, LoadoutUi.ghost(com.pglol.aotrpg.Loadout.SLOTS[place]), x + 3, y + 3);
            }
        }
    }

    private void drawSide(DrawContext c, int side, int mouseX, int mouseY) {
        Map<Integer, ItemStack> src = side == 0 ? stash : ClientState.bag;
        List<Integer> list = shown(src);
        int x0 = side == 0 ? leftX() : rightX(), scroll = side == 0 ? scrollL : scrollR;
        if (list.isEmpty()) c.drawText(textRenderer, "Nothing here", x0 + 8, top + 6, Ui.MUTED, false);
        for (int i = 0; i < rows * cols; i++) {
            int idx = scroll * cols + i;
            if (idx >= list.size()) break;
            ItemStack s = src.get(list.get(idx));
            int x = x0 + 4 + (i % cols) * (CW + GAP), y = top + (i / cols) * (CW + GAP);
            card(c, s, x, y, mouseX >= x && mouseX < x + CW && mouseY >= y && mouseY < y + CW);
        }
        int total = (list.size() + cols - 1) / cols;
        if (total > rows) {
            int bx = x0 + panelW - 4, bh = rows * (CW + GAP);
            c.fill(bx, top, bx + 2, top + bh, 0x30FFE8C0);
            int th = Math.max(10, bh * rows / total), ty = top + (bh - th) * scroll / Math.max(1, total - rows);
            c.fill(bx, ty, bx + 2, ty + th, 0xFF8C7248);
        }
    }

    /** The satchel's card: a socket, the item, a line of its rarity underneath. */
    private void card(DrawContext c, ItemStack s, int x, int y, boolean hov) {
        int q = BagScreen.quality(s);
        int tone = Ui.rarityTone(q);
        Ui.socket(c, x, y, CW);
        if (q > 0) c.fillGradient(x + 1, y + 1, x + CW - 1, y + CW - 1, 0x00000000, (tone & 0xFFFFFF) | 0x28000000);
        c.fill(x + 3, y + CW - 3, x + CW - 3, y + CW - 2, tone);
        var m = c.getMatrices();
        m.push();
        m.translate(x + (CW - 24) / 2f, y + (CW - 24) / 2f - 1, 0);
        m.scale(1.5f, 1.5f, 1);
        c.drawItem(s, 0, 0);
        m.pop();
        m.push();
        m.translate(0, 0, 200);
        if (Gear.isGear(s)) {
            Ui.text(c, Text.literal(String.valueOf(BagScreen.level(s))), x + 4, y + 4, 0.6f, 0xFFD8CFB8, false);
        } else if (s.getCount() > 1) {
            String n = String.valueOf(s.getCount());
            Ui.text(c, Text.literal(n), x + CW - 4 - textRenderer.getWidth(n) * 0.7f, y + CW - 10, 0.7f, 0xFFEDE3C8, false);
        }
        m.pop();
        if (scrap && Gear.isGear(s)) c.fill(x + 1, y + 1, x + CW - 1, y + CW - 1, hov ? 0x70E03A3A : 0x30E03A3A);
        else if (scrap) c.fill(x + 1, y + 1, x + CW - 1, y + CW - 1, 0x60000000);
        if (hov) c.drawBorder(x - 1, y - 1, CW + 2, CW + 2, scrap ? 0xFFE03A3A : 0xFFE0B96A);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        super.render(c, mouseX, mouseY, delta);
        int[] hit = at(mouseX, mouseY);
        if (hit != null) {
            ItemStack s = (hit[0] == 0 ? stash : ClientState.bag).get(hit[1]);
            if (s != null) c.drawItemTooltip(textRenderer, s, mouseX, mouseY);
        }
        int place = placeAt(mouseX, mouseY);
        if (place >= 0 && !placed(place).isEmpty()) c.drawItemTooltip(textRenderer, placed(place), mouseX, mouseY);
    }
}
