package com.pglol.aotrpg.client;

import com.pglol.aotrpg.AotItems;
import com.pglol.aotrpg.Gear;
import com.pglol.aotrpg.Loadout;
import com.pglol.aotrpg.Net;
import com.pglol.aotrpg.Satchel;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PotionItem;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The satchel: everything you carry, sorted into categories and laid out as cards (rarity colour,
 * level or count), with the chosen item's details on the right and what you can do with it.
 */
public final class BagScreen extends Screen {
    static final String[] TABS = {"Weapons", "Armor", "Ammo & Supplies", "Food", "Materials", "Gear & Mounts", "Quest"};
    static final Item[] TAB_ICONS = {Items.IRON_SWORD, Items.IRON_CHESTPLATE, Items.ARROW, Items.BREAD, Items.IRON_INGOT,
        Items.SADDLE, Items.WRITABLE_BOOK};
    private static final String[] SORTS = {"Quality", "Level", "Name", "Count"};
    private static int tab, sort;
    private int selected = -1, scroll;
    private static int scrapAsk = -1;
    private int gx, gy, cols, rows, cw, ch, detailX, detailW;
    private TextFieldWidget price;
    private int equipRow = -1;
    private int[] stripX = new int[0];

    private ItemStack placed(int t) {
        if (client == null || client.player == null) return ItemStack.EMPTY;
        if (t >= Satchel.ARMOR) return client.player.getEquippedStack(Satchel.armorSlot(t));
        if (t == Satchel.OFF) return client.player.getOffHandStack();
        return client.player.getInventory().main.get(t);
    }

    private static String placeName(int t) {
        return switch (t) {
            case 103 -> "Head";
            case 102 -> "Chest";
            case 101 -> "Legs";
            case 100 -> "Feet";
            case Satchel.OFF -> "Off hand";
            default -> Loadout.SLOTS[t].title + " (" + (t + 1) + ")";
        };
    }

    public BagScreen() {
        super(Text.literal("Satchel"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        if (selected >= 0 && !ClientState.bag.containsKey(selected)) selected = -1;
        clearAndInit();
    }

    // ------------------------------------------------------------------ categories

    public static int category(ItemStack s) {
        Item i = s.getItem();
        if (Satchel.isStory(s)) return 6;
        var pl = net.minecraft.client.MinecraftClient.getInstance().player;
        if (s.getItem() instanceof ArmorItem
            || (pl != null && pl.getPreferredEquipmentSlot(s).getType() == net.minecraft.entity.EquipmentSlot.Type.HUMANOID_ARMOR)) return 1;
        // Ammo first: thunder spears, blades, cartridges and arrows are never "weapons".
        if (Loadout.isAmmo(s)) return 2;
        if (Loadout.isMelee(s) || Loadout.isRanged(s) || i instanceof net.minecraft.item.ShieldItem) return 0;
        if (AotItems.isSupply(s) || AotItems.isGas(s) || i == Items.ARROW || i == Items.SPECTRAL_ARROW) return 2;
        if (s.contains(DataComponentTypes.FOOD) || i instanceof PotionItem || Loadout.isHeal(s)) return 3;
        if (Loadout.fits(Loadout.Kind.MOUNT, s) || Loadout.fits(Loadout.Kind.TOOL, s) || Loadout.fits(Loadout.Kind.SIGNAL, s)) return 5;
        return 4;
    }

    static int quality(ItemStack s) {
        int r = GearUi.rarity(s);
        if (r >= 0) return r;
        var rar = s.getRarity();
        return rar == net.minecraft.util.Rarity.EPIC ? 3 : rar == net.minecraft.util.Rarity.RARE ? 2 : rar == net.minecraft.util.Rarity.UNCOMMON ? 1 : 0;
    }

    static int level(ItemStack s) {
        return Gear.isGear(s) ? Gear.requiredLevel(s) : 0;
    }

    private List<Integer> shown() {
        List<Integer> out = new ArrayList<>();
        for (Map.Entry<Integer, ItemStack> e : ClientState.bag.entrySet()) if (category(e.getValue()) == tab) out.add(e.getKey());
        Comparator<Integer> c = switch (sort) {
            case 1 -> Comparator.comparingInt((Integer k) -> -level(ClientState.bag.get(k)));
            case 2 -> Comparator.comparing((Integer k) -> ClientState.bag.get(k).getName().getString());
            case 3 -> Comparator.comparingInt((Integer k) -> -ClientState.bag.get(k).getCount());
            default -> Comparator.comparingInt((Integer k) -> -quality(ClientState.bag.get(k)))
                .thenComparingInt(k -> -level(ClientState.bag.get(k)));
        };
        out.sort(c);
        return out;
    }

    private static void act(String a, int slot, long arg) {
        ClientPlayNetworking.send(new Net.BagAction(a, slot, arg));
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        detailW = Math.min(170, width / 3);
        detailX = width - detailW - 14;
        gx = 14;
        gy = 70;
        cw = 32;
        ch = 32;
        cols = Math.max(3, (detailX - 10 - gx) / (cw + 5));
        rows = Math.max(1, (height - 66 - gy) / (ch + 5));
        // What you wear and carry: click to put it back in the satchel.
        int[] order = {103, 102, 101, 100, Satchel.OFF, 0, 1, 2, 3, 4, 5, 6, 7, 8};
        // Tiles shrink on narrow screens so the band never runs under the detail page.
        int ts = Math.max(16, Math.min(24, (detailX - 12 - gx - 20 - 13 * 3) / 14)), sx = gx, sy = height - 40;
        stripX = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            int t = order[i];
            int x = sx + i * (ts + 3) + (i >= 4 ? 10 : 0) + (i >= 5 ? 10 : 0);
            stripX[i] = x;
            PlaceTile tile = addDrawableChild(new PlaceTile(x, sy, ts, t, () -> placed(t), () -> {
                if (!placed(t).isEmpty()) act("store", -1, t);
            }));
            ItemStack cur = placed(t);
            tile.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(placeName(t)
                + (cur.isEmpty() ? " (empty)" : ": " + cur.getName().getString() + "\nClick to put it back in the satchel"))));
        }
        // Category tabs across the top.
        // Row two: category tabs on the left, sort and mend on the right, never sharing space.
        int tw = 22, tx = gx;
        for (int i = 0; i < TABS.length; i++) {
            int t = i;
            AotButton b = addDrawableChild(new AotButton(tx + i * (tw + 4), 38, tw, 22, Text.empty(), () -> {
                tab = t;
                scroll = 0;
                selected = -1;
                clearAndInit();
            }));
            b.icon(new ItemStack(TAB_ICONS[i]));
            b.selected = tab == i;
            b.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(TABS[i])));
        }
        addDrawableChild(new AotButton(width - 30, 5, 20, 20, Text.literal("✕"), this::close));
        int tabsEnd = tx + TABS.length * (tw + 4);
        int hb = Math.max(44, Math.min(84, (width - 14 - tabsEnd - 16) / 2));
        int mx = width - 14 - hb;
        AotButton mendAll = addDrawableChild(new AotButton(mx, 41, hb, 16, Text.literal(hb >= 70 ? "Mend all" : "Mend"), () -> act("repairall", -1, 0)));
        mendAll.accent = 0xFF5BD35B;
        mendAll.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
            "At home: mend everything you carry and wear. Weapons take iron, armor and clothing take leather, plus a small fee.")));
        addDrawableChild(new AotButton(mx - hb - 6, 41, hb, 16, Text.literal(hb >= 70 ? "Sort: " + SORTS[sort] : SORTS[sort]), () -> {
            sort = (sort + 1) % SORTS.length;
            clearAndInit();
        }));

        ItemStack s = selected >= 0 ? ClientState.bag.getOrDefault(selected, ItemStack.EMPTY) : ItemStack.EMPTY;
        if (!s.isEmpty()) {
            int bx = detailX + 8, bw = detailW - 16, by = height - 30;
            boolean usable = s.contains(DataComponentTypes.FOOD) || s.getItem() instanceof PotionItem;
            int slot = selected;
            // Where it can go: pick the place (what is there now comes back to the satchel).
            java.util.List<Integer> places = new java.util.ArrayList<>();
            for (int t : new int[] {103, 102, 101, 100, Satchel.OFF, 0, 1, 2, 3, 4, 5, 6, 7, 8}) {
                if (client.player != null && Satchel.fitsPlace(client.player, s, t)) places.add(t);
            }
            int es = 30, per = Math.max(1, (bw + 4) / (es + 4));
            int lines = (Math.min(places.size(), per * 2) + per - 1) / per;
            int py = by - 36 - lines * (es + 12);
            for (int i = 0; i < places.size() && i < per * 2; i++) {
                int t = places.get(i);
                ItemStack cur = placed(t);
                PlaceTile tile = addDrawableChild(new PlaceTile(bx + (i % per) * (es + 4), py + (i / per) * (es + 12), es, t,
                    () -> placed(t), () -> act("equip", slot, t)));
                tile.highlight = true;
                tile.active = !GearUi.locked(s);
                tile.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Equip to " + placeName(t)
                    + (cur.isEmpty() ? "" : "\n(swaps with " + cur.getName().getString() + ")"))));
            }
            equipRow = places.isEmpty() ? -1 : py;
            if (!Satchel.isStory(s)) {
                AotButton drop = addDrawableChild(new AotButton(bx, by, 44, 18, Text.literal("Drop"), () -> act("drop", slot, 0)));
                drop.accent = Ui.RED;
            }
            if (Gear.isGear(s)) {
                // Two clicks: the first asks, the second breaks it down.
                boolean sure = scrapAsk == slot;
                AotButton sc = addDrawableChild(new AotButton(bx, by - 22, 92, 18, Text.literal(sure ? "Disassemble?" : "Disassemble"), () -> {
                    if (scrapAsk == slot) {
                        scrapAsk = -1;
                        act("scrap", slot, 0);
                    } else {
                        scrapAsk = slot;
                        clearAndInit();
                    }
                }));
                sc.accent = Ui.RED;
                sc.selected(sure);
            }
            int rx = bx + bw;
            if (usable) {
                addDrawableChild(new AotButton(rx - 44, by, 44, 18, Text.literal("Use"), () -> act("use", slot, 0)));
                rx -= 48;
            }
            com.pglol.aotrpg.Repair.Cost cost = com.pglol.aotrpg.Repair.cost(s);
            if (cost != null) {
                AotButton rp = addDrawableChild(new AotButton(rx - 56, by, 56, 18, Text.literal("Mend"), () -> act("repair", slot, 0)));
                rp.accent = 0xFF5BD35B;
                rp.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Mend at home: " + cost.marks() + " Marks"
                    + (cost.iron() > 0 ? ", " + cost.iron() + " iron ingot" + (cost.iron() == 1 ? "" : "s") : "")
                    + (cost.leather() > 0 ? ", " + cost.leather() + " leather" : ""))));
            }
            if (!Satchel.isStory(s)) {
                // List it on the Global Market.
                price = new TextFieldWidget(textRenderer, bx, by - 24, bw - 64, 18, Text.literal("Price"));
                price.setPlaceholder(Text.literal("Price in Marks").withColor(Ui.DIM));
                price.setTextPredicate(t -> t.matches("\\d{0,8}"));
                addDrawableChild(price);
                addDrawableChild(new AotButton(bx + bw - 60, by - 24, 60, 18, Text.literal("Sell"), () -> {
                    try {
                        long v = Long.parseLong(price.getText());
                        if (v > 0) act("list", slot, v);
                    } catch (NumberFormatException ignored) {
                        // no price typed
                    }
                }));
            }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        List<Integer> list = shown();
        for (int i = 0; i < rows * cols; i++) {
            int idx = scroll * cols + i;
            if (idx >= list.size()) break;
            int x = gx + (i % cols) * (cw + 5), y = gy + (i / cols) * (ch + 5);
            if (mx >= x && mx < x + cw && my >= y && my < y + ch) {
                selected = list.get(idx);
                clearAndInit();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        int total = (shown().size() + cols - 1) / cols;
        scroll = Math.max(0, Math.min(Math.max(0, total - rows), scroll - (int) Math.signum(vy)));
        return true;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.planks(c, width, height);
        // A leather strap across the top with the title and how full the satchel is.
        c.fill(0, 0, width, 30, Ui.LEATHER);
        c.fill(0, 30, width, 31, 0xFF0A0706);
        for (int i = 4; i < width - 4; i += 4) c.fill(i, 26, i + 2, 27, Ui.STITCH);
        Text title = Ui.title("Satchel");
        c.drawText(textRenderer, title, gx, 11, Ui.GOLD, false);
        c.drawText(textRenderer, Text.literal("·  " + TABS[tab]), gx + textRenderer.getWidth(title) + 8, 11, Ui.CREAM, false);
        String load = ClientState.bag.size() + " / " + ClientState.bagSize;
        int lx = width - 40 - textRenderer.getWidth(load);
        c.drawText(textRenderer, load, lx, 11, ClientState.bag.size() >= ClientState.bagSize ? Ui.RED : Ui.CREAM, false);
        c.drawText(textRenderer, "Load", lx - 6 - textRenderer.getWidth("Load"), 11, Ui.MUTED, false);

        List<Integer> list = shown();
        if (list.isEmpty()) c.drawText(textRenderer, "Nothing here yet.", gx + 4, gy + 6, Ui.MUTED, false);
        for (int i = 0; i < rows * cols; i++) {
            int idx = scroll * cols + i;
            if (idx >= list.size()) break;
            int slot = list.get(idx);
            ItemStack s = ClientState.bag.get(slot);
            int x = gx + (i % cols) * (cw + 5), y = gy + (i / cols) * (ch + 5);
            card(c, s, x, y, slot == selected, mouseX >= x && mouseX < x + cw && mouseY >= y && mouseY < y + ch);
        }
        int total = (list.size() + cols - 1) / cols;
        if (total > rows) {
            int bx = detailX - 8, bh = rows * (ch + 5);
            c.fill(bx, gy, bx + 2, gy + bh, 0x30FFE8C0);
            int th = Math.max(10, bh * rows / total), ty = gy + (bh - th) * scroll / Math.max(1, total - rows);
            c.fill(bx, ty, bx + 2, ty + th, 0xFF8C7248);
        }
        detail(c);
        // The worn & loadout band along the bottom, with its three groups named.
        int band = detailX - 8;
        c.fill(0, height - 60, band, height, Ui.LEATHER);
        c.fill(0, height - 60, band, height - 59, 0xFF0A0706);
        for (int i = 4; i < band - 4; i += 4) c.fill(i, height - 57, i + 2, height - 56, Ui.STITCH);
        if (stripX.length == 14) {
            c.drawText(textRenderer, Ui.heading("Worn"), stripX[0], height - 52, Ui.GOLD, false);
            c.drawText(textRenderer, Ui.heading("Hand"), stripX[4], height - 52, Ui.GOLD, false);
            Text lo = Ui.heading("Loadout");
            c.drawText(textRenderer, lo, stripX[5], height - 52, Ui.GOLD, false);
            String tip = "click to put back";
            if (stripX[5] + textRenderer.getWidth(lo) + 8 + textRenderer.getWidth(tip) < band - 6) {
                c.drawText(textRenderer, tip, stripX[5] + textRenderer.getWidth(lo) + 8, height - 52, Ui.MUTED, false);
            }
        }
        if (equipRow >= 0) c.drawText(textRenderer, Ui.heading("Equip to"), detailX + 8, equipRow - 12, Ui.GOLD, false);
    }

    /** An item in its socket: a dark recess, the item, a thin enamel line in its rarity underneath. */
    private void card(DrawContext c, ItemStack s, int x, int y, boolean sel, boolean hov) {
        int q = quality(s);
        int tone = Ui.rarityTone(q);
        Ui.socket(c, x, y, cw);
        if (q > 0) c.fillGradient(x + 1, y + 1, x + cw - 1, y + ch - 1, 0x00000000, (tone & 0xFFFFFF) | 0x28000000);
        c.fill(x + 3, y + ch - 3, x + cw - 3, y + ch - 2, tone);
        var m = c.getMatrices();
        m.push();
        m.translate(x + (cw - 24) / 2f, y + (ch - 24) / 2f - 1, 0);
        m.scale(1.5f, 1.5f, 1);
        c.drawItem(s, 0, 0);
        m.pop();
        m.push();
        m.translate(0, 0, 200);
        if (Gear.isGear(s)) {
            String lv = String.valueOf(level(s));
            Ui.text(c, Text.literal(lv), x + 4, y + 4, 0.6f, GearUi.locked(s) ? 0xFFE07A6A : 0xFFD8CFB8, false);
        } else if (s.getCount() > 1) {
            String n = String.valueOf(s.getCount());
            Ui.text(c, Text.literal(n), x + cw - 4 - textRenderer.getWidth(n) * 0.7f, y + ch - 10, 0.7f, 0xFFEDE3C8, false);
        }
        m.pop();
        if (GearUi.locked(s)) c.fill(x + 1, y + 1, x + cw - 1, y + ch - 1, 0x50601010);
        if (sel) c.drawBorder(x - 1, y - 1, cw + 2, ch + 2, 0xFFE0B96A);
        else if (hov) c.drawBorder(x - 1, y - 1, cw + 2, ch + 2, 0x908C7248);
    }

    /** The chosen item on a leather page: its name in its rarity, a picture, and what it says about itself. */
    private void detail(DrawContext c) {
        ItemStack s = selected >= 0 ? ClientState.bag.getOrDefault(selected, ItemStack.EMPTY) : ItemStack.EMPTY;
        int x = detailX, y = gy, w = detailW, h = height - y - 8;
        if (s.isEmpty()) {
            Ui.leather(c, x, y, w, 50);
            String t = "Pick an item to see it.";
            c.drawText(textRenderer, t, x + (w - textRenderer.getWidth(t)) / 2, y + 21, Ui.MUTED, false);
            equipRow = -1;
            return;
        }
        int q = quality(s);
        int tone = Ui.rarityTone(q);
        Ui.leather(c, x, y, w, h);
        // Name, trimmed to the page, with a rarity line under it.
        String name = textRenderer.trimToWidth(s.getName().getString(), w - 16);
        c.drawText(textRenderer, name, x + 8, y + 9, q > 0 ? brighten(tone) : Ui.CREAM, false);
        c.fill(x + 8, y + 20, x + w - 8, y + 21, tone);
        // Picture in a recess, facts beside it.
        Ui.well(c, x + w - 58, y + 26, 50, 50);
        var m = c.getMatrices();
        m.push();
        m.translate(x + w - 57, y + 27, 0);
        m.scale(3f, 3f, 1);
        c.drawItem(s, 0, 0);
        m.pop();
        c.drawText(textRenderer, TABS[category(s)], x + 8, y + 28, Ui.MUTED, false);
        if (Gear.isGear(s)) {
            Ui.text(c, Ui.title("Level " + level(s)), x + 8, y + 42, 1.1f, GearUi.locked(s) ? 0xFFE07A6A : Ui.CREAM, false);
            String grade = new String[] {"Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic"}[Math.max(0, Math.min(5, GearUi.rarity(s)))];
            c.drawText(textRenderer, grade, x + 8, y + 58, brighten(tone), false);
        } else if (s.getCount() > 1) {
            Ui.text(c, Ui.title("× " + s.getCount()), x + 8, y + 42, 1.1f, Ui.CREAM, false);
        }
        c.fill(x + 8, y + 82, x + w - 8, y + 83, 0x40000000);
        // Everything else the item says about itself.
        List<Text> lines = client.player == null ? List.of()
            : s.getTooltip(net.minecraft.item.Item.TooltipContext.create(client.world), client.player, TooltipType.BASIC);
        int ly = y + 88;
        int bottom = equipRow >= 0 ? equipRow - 16 : height - 64;
        for (int i = 1; i < lines.size() && ly < bottom; i++) {
            for (var ord : textRenderer.wrapLines(lines.get(i), (int) ((w - 16) / 0.75f))) {
                if (ly >= bottom) break;
                var mm = c.getMatrices();
                mm.push();
                mm.translate(x + 8, ly, 0);
                mm.scale(0.75f, 0.75f, 1);
                c.drawText(textRenderer, ord, 0, 0, 0xFFD8CFB8, false);
                mm.pop();
                ly += 8;
            }
        }
    }

    private static int brighten(int c) {
        int r = Math.min(255, (c >> 16 & 255) * 5 / 4 + 20), g = Math.min(255, (c >> 8 & 255) * 5 / 4 + 20), b = Math.min(255, (c & 255) * 5 / 4 + 20);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
