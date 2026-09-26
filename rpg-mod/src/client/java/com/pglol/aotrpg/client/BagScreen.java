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
    private static final String[] TABS = {"Weapons", "Armor", "Supplies", "Food", "Materials", "Gear & Mounts", "Quest"};
    private static final Item[] TAB_ICONS = {Items.IRON_SWORD, Items.IRON_CHESTPLATE, Items.ARROW, Items.BREAD, Items.IRON_INGOT,
        Items.SADDLE, Items.WRITABLE_BOOK};
    private static final String[] SORTS = {"Quality", "Level", "Name", "Count"};
    private static int tab, sort;
    private int selected = -1, scroll;
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
        if (Loadout.isMelee(s) || Loadout.isRanged(s) || i instanceof net.minecraft.item.ShieldItem) return 0;
        if (AotItems.isSupply(s) || AotItems.isGas(s) || i == Items.ARROW || i == Items.SPECTRAL_ARROW) return 2;
        if (s.contains(DataComponentTypes.FOOD) || i instanceof PotionItem || Loadout.isHeal(s)) return 3;
        if (Loadout.fits(Loadout.Kind.MOUNT, s) || Loadout.fits(Loadout.Kind.TOOL, s) || Loadout.fits(Loadout.Kind.SIGNAL, s)) return 5;
        return 4;
    }

    private static int quality(ItemStack s) {
        int r = GearUi.rarity(s);
        if (r >= 0) return r;
        var rar = s.getRarity();
        return rar == net.minecraft.util.Rarity.EPIC ? 3 : rar == net.minecraft.util.Rarity.RARE ? 2 : rar == net.minecraft.util.Rarity.UNCOMMON ? 1 : 0;
    }

    private static int level(ItemStack s) {
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
        gy = 44;
        cw = 36;
        ch = 46;
        cols = Math.max(3, (detailX - 10 - gx) / (cw + 5));
        rows = Math.max(1, (height - 66 - gy) / (ch + 5));
        // What you wear and carry: click to put it back in the satchel.
        int[] order = {103, 102, 101, 100, Satchel.OFF, 0, 1, 2, 3, 4, 5, 6, 7, 8};
        int ts = 24, sx = gx, sy = height - 40;
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
        int tw = 22, tx = width / 2 - (TABS.length * (tw + 4)) / 2;
        for (int i = 0; i < TABS.length; i++) {
            int t = i;
            AotButton b = addDrawableChild(new AotButton(tx + i * (tw + 4), 8, tw, 22, Text.empty(), () -> {
                tab = t;
                scroll = 0;
                selected = -1;
                clearAndInit();
            }));
            b.icon(new ItemStack(TAB_ICONS[i]));
            b.selected = tab == i;
            b.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(TABS[i])));
        }
        addDrawableChild(new AotButton(width - 30, 8, 22, 22, Text.literal("✕"), this::close));
        addDrawableChild(new AotButton(150, 11, 90, 16, Text.literal("Sort: " + SORTS[sort]), () -> {
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
            int rx = bx + bw;
            if (usable) {
                addDrawableChild(new AotButton(rx - 44, by, 44, 18, Text.literal("Use"), () -> act("use", slot, 0)));
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

    private static int rarityBg(int q) {
        return switch (q) {
            case 1 -> 0xFF3F7A4E;
            case 2 -> 0xFF4468A8;
            case 3 -> 0xFF7A4CA8;
            case 4 -> 0xFFB8792E;
            default -> 0xFF6A6860;
        };
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        c.fill(0, 0, width, 36, 0xC0101410);
        c.fill(0, 36, width, 37, 0x80B8955A);
        Ui.text(c, Ui.title("SATCHEL"), 14, 10, 1.1f, Ui.GOLD, false);
        Ui.text(c, Text.literal("/ " + TABS[tab]), 14, 23, 0.8f, Ui.CREAM, false);
        String load = "Load " + ClientState.bag.size() + " / " + ClientState.bagSize;
        Ui.text(c, Text.literal(load), width - 38 - textRenderer.getWidth(load), 15, 1f,
            ClientState.bag.size() >= ClientState.bagSize ? Ui.RED : Ui.GOLD, false);

        List<Integer> list = shown();
        if (list.isEmpty()) Ui.text(c, Text.literal("Nothing here yet."), gx + 4, gy + 6, 0.9f, Ui.MUTED, false);
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
            c.fill(bx, gy, bx + 2, gy + bh, 0x40FFFFFF);
            int th = Math.max(10, bh * rows / total), ty = gy + (bh - th) * scroll / Math.max(1, total - rows);
            c.fill(bx, ty, bx + 2, ty + th, Ui.GOLD);
        }
        detail(c);
        // The worn & loadout band along the bottom, with its three groups named.
        c.fill(0, height - 58, detailX - 8, height, 0xB0101410);
        c.fill(0, height - 58, detailX - 8, height - 57, 0x80B8955A);
        if (stripX.length == 14) {
            Ui.text(c, Ui.heading("Armor"), stripX[0], height - 53, 0.7f, Ui.GOLD, false);
            Ui.text(c, Ui.heading("Hand"), stripX[4], height - 53, 0.7f, Ui.GOLD, false);
            Ui.text(c, Ui.heading("Loadout  ·  click a slot to put it back in the satchel"), stripX[5], height - 53, 0.7f, Ui.GOLD, false);
        }
        if (equipRow >= 0) Ui.text(c, Ui.heading("Equip to"), detailX + 8, equipRow - 11, 0.8f, 0xFF3A3020, false);
    }

    private void card(DrawContext c, ItemStack s, int x, int y, boolean sel, boolean hov) {
        int q = quality(s);
        c.fill(x, y, x + cw, y + ch - 12, rarityBg(q));
        c.fillGradient(x, y, x + cw, y + ch - 12, 0x30FFFFFF, 0x00000000);
        c.fill(x, y + ch - 12, x + cw, y + ch, 0xFFE8DFC8);
        var m = c.getMatrices();
        m.push();
        m.translate(x + cw / 2f - 12, y + 5, 0);
        m.scale(1.5f, 1.5f, 1);
        c.drawItem(s, 0, 0);
        m.pop();
        String foot = Gear.isGear(s) ? "Lv. " + level(s) : s.getCount() > 1 ? String.valueOf(s.getCount()) : "";
        if (!foot.isEmpty()) Ui.text(c, Text.literal(foot), x + cw / 2f, y + ch - 10, 0.7f, 0xFF3A3020, true);
        if (GearUi.rarity(s) >= 0) {
            String stars = "★".repeat(GearUi.rarity(s) + 1);
            Ui.text(c, Text.literal(stars), x + cw / 2f, y + ch - 20, 0.55f, 0xFFFFD76A, true);
        }
        if (GearUi.locked(s)) {
            c.fill(x, y, x + cw, y + ch, 0x60801010);
            Ui.text(c, Text.literal("LOCKED"), x + cw / 2f, y + 2, 0.5f, 0xFFFF8A8A, true);
        }
        if (sel) c.drawBorder(x - 1, y - 1, cw + 2, ch + 2, 0xFFFFFFFF);
        else if (hov) c.drawBorder(x - 1, y - 1, cw + 2, ch + 2, 0xA0FFFFFF);
    }

    private void detail(DrawContext c) {
        ItemStack s = selected >= 0 ? ClientState.bag.getOrDefault(selected, ItemStack.EMPTY) : ItemStack.EMPTY;
        int x = detailX, y = gy, w = detailW, h = height - gy - 8;
        if (s.isEmpty()) {
            Ui.panel(c, x, y, w, 60);
            Ui.text(c, Text.literal("Pick an item to see it."), x + w / 2f, y + 26, 0.8f, Ui.MUTED, true);
        equipRow = -1;
            return;
        }
        int q = quality(s);
        c.fill(x, y, x + w, y + 22, rarityBg(q));
        Ui.text(c, Text.literal(textRenderer.trimToWidth(s.getName().getString(), (int) ((w - 12) / 1.05f))), x + 6, y + 6, 1.05f, 0xFFFFFFFF, false);
        c.fill(x, y + 22, x + w, y + h, 0xF0E8DFC8);
        // Big picture and the headline facts.
        c.fill(x, y + 22, x + w, y + 80, (rarityBg(q) & 0x00FFFFFF) | 0x90000000);
        var m = c.getMatrices();
        m.push();
        m.translate(x + w - 56, y + 28, 0);
        m.scale(3f, 3f, 1);
        c.drawItem(s, 0, 0);
        m.pop();
        Ui.text(c, Text.literal(TABS[category(s)]), x + 6, y + 27, 0.75f, 0xFFEDE3C8, false);
        if (Gear.isGear(s)) {
            Ui.text(c, Ui.title("Lv. " + level(s)), x + 6, y + 40, 1.3f, GearUi.locked(s) ? 0xFFFF6A6A : 0xFFFFFFFF, false);
            Ui.text(c, Text.literal("★".repeat(GearUi.rarity(s) + 1)), x + 6, y + 58, 1f, 0xFFFFD76A, false);
        } else if (s.getCount() > 1) {
            Ui.text(c, Ui.title("x" + s.getCount()), x + 6, y + 40, 1.3f, 0xFFFFFFFF, false);
        }
        // Everything else the item says about itself.
        List<Text> lines = client.player == null ? List.of()
            : s.getTooltip(net.minecraft.item.Item.TooltipContext.create(client.world), client.player, TooltipType.BASIC);
        int ly = y + 86;
        int bottom = equipRow >= 0 ? equipRow - 14 : height - 64;
        for (int i = 1; i < lines.size() && ly < bottom; i++) {
            for (var ord : textRenderer.wrapLines(lines.get(i), (int) ((w - 12) / 0.75f))) {
                if (ly >= bottom) break;
                var mm = c.getMatrices();
                mm.push();
                mm.translate(x + 6, ly, 0);
                mm.scale(0.75f, 0.75f, 1);
                c.drawText(textRenderer, ord, 0, 0, 0xFF3A3020, false);
                mm.pop();
                ly += 8;
            }
        }
    }
}
