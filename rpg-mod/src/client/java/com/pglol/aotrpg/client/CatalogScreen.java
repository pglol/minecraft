package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The staff catalog (/catalog): pick a tab, set rarity, element, level and amount, choose who it's
 * for, then click an item to hand it over (or send it to their Inbox with a tag).
 */
public final class CatalogScreen extends Screen {
    public static Net.CatalogView view;

    private static final String[] TABS = {"Weapons", "Armor", "Items", "Crates", "Titles", "Cosmetics", "Money"};
    private static final String[] RARITY = {"Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic"};
    private static final int[] RARITY_COL = {0xFFEDE3C8, 0xFF5BD35B, 0xFF4A90FF, 0xFFB04AFF, 0xFFFFB020, 0xFFFF3A3A};
    private static final String[] INFUSIONS = {"", "random", "FROST", "EMBER", "VOID", "STORM", "VENOM", "RADIANT", "BLOOD"};
    private static final String[] INFUSION_NAMES = {"No element", "Random element", "Frostborne", "Emberheart", "Voidborn",
        "Stormcaller", "Viperfang", "Dawnlight", "Bloodsworn"};

    private static int tab, rarity = 5, infusion, level = 30, amount = 1, target;
    private static boolean inbox, jackpot = true;
    private static String tag = "", search = "";
    private int scroll;
    private TextFieldWidget searchBox, tagBox;

    public CatalogScreen() {
        super(Text.literal("Catalog"));
    }

    public static void on(Net.CatalogView v) {
        view = v;
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.currentScreen instanceof CatalogScreen s) s.clearAndInit();
        else mc.setScreen(new CatalogScreen());
    }

    private int pw() { return Math.min(width - 20, 540); }
    private int ph() { return Math.min(height - 16, 350); }
    private int px() { return width / 2 - pw() / 2; }
    private int py() { return Math.max(8, height / 2 - ph() / 2); }
    private int gridTop() { return py() + (tab == 3 ? 118 : 108); }

    private boolean gear() { return tab <= 1; }

    @Override
    protected void init() {
        if (view == null) return;
        int x = px(), y = py(), w = pw();
        addDrawableChild(new AotButton(x + w - 20, y, 20, 20, Text.literal("✕"), this::close));
        // Tabs.
        int tw = (w - 20 - 4 * (TABS.length - 1)) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            int k = i;
            addDrawableChild(new AotButton(x + 10 + i * (tw + 4), y + 26, tw, 16, Text.literal(TABS[i]), () -> {
                tab = k;
                scroll = 0;
                clearAndInit();
            })).selected(tab == i);
        }
        // Who it's for, and how.
        List<String> players = view.players();
        target = Math.max(0, Math.min(target, players.size() - 1));
        String[] who = players.isEmpty() ? new String[] {"", "Nobody", "0"} : players.get(target).split("\\|");
        addDrawableChild(new AotButton(x + 10, y + 46, 190, 16, Text.literal("For: " + who[1]), () -> {
            if (!players.isEmpty()) target = (target + (hasShiftDown() ? players.size() - 1 : 1)) % players.size();
            clearAndInit();
        })).selected(true);
        boolean far = who.length > 2 && who[2].equals("0");
        boolean mail = inbox || far || tab == 3;
        AotButton mode = addDrawableChild(new AotButton(x + 204, y + 46, 130, 16, Text.literal(mail ? "Send to Inbox" : "Give now"), () -> {
            inbox = !inbox;
            clearAndInit();
        }));
        mode.selected(mail);
        mode.active = !far && tab != 3;
        tagBox = new TextFieldWidget(textRenderer, x + 338, y + 46, w - 348, 16, Text.literal("Tag"));
        tagBox.setMaxLength(60);
        tagBox.setText(tag);
        tagBox.setPlaceholder(Text.literal("Gift tag").withColor(Ui.DIM));
        tagBox.setChangedListener(s -> tag = s);
        tagBox.active = mail;
        tagBox.visible = mail;
        addDrawableChild(tagBox);
        // What it'll be.
        int oy = y + 66;
        int ox = x + 10;
        if (gear()) {
            for (int i = 0; i < RARITY.length; i++) {
                int k = i;
                AotButton b = addDrawableChild(new AotButton(ox + i * 58, oy, 56, 16, Text.literal(RARITY[i]), () -> {
                    rarity = k;
                    clearAndInit();
                }));
                b.accent = RARITY_COL[i];
                b.selected(rarity == i);
            }
            ox += 6 * 58 + 4;
            if (tab == 0) {
                addDrawableChild(new AotButton(ox, oy, w - (ox - x) - 10, 16, Text.literal(INFUSION_NAMES[infusion]), () -> {
                    infusion = (infusion + (hasShiftDown() ? INFUSIONS.length - 1 : 1)) % INFUSIONS.length;
                    clearAndInit();
                })).selected(infusion > 0);
            }
            oy += 20;
            ox = x + 10;
            ox = stepper(ox, oy, "Level", () -> level, v -> level = Math.max(1, Math.min(100, v)));
        }
        if (tab == 3) {
            addDrawableChild(new AotButton(ox, oy, 120, 16, Text.literal("Regular crate"), () -> {
                jackpot = false;
                clearAndInit();
            })).selected(!jackpot);
            AotButton j = addDrawableChild(new AotButton(ox + 124, oy, 150, 16, Text.literal("Jackpot: Legendary+"), () -> {
                jackpot = true;
                clearAndInit();
            }));
            j.accent = 0xFFFF3A3A;
            j.selected(jackpot);
            oy += 20;
        }
        if (tab != 4 && tab != 5) {
            int ax = gear() ? ox + 8 : x + 10;
            int ay = gear() || tab == 3 ? oy : y + 66;
            stepper(ax, ay, tab == 6 ? "Amount" : "Count", () -> amount, v -> amount = Math.max(1, Math.min(tab == 6 ? 10_000_000 : 64 * 36, v)));
        }
        searchBox = new TextFieldWidget(textRenderer, x + w - 170, y + 86, 160, 16, Text.literal("Search"));
        searchBox.setText(search);
        searchBox.setPlaceholder(Text.literal("Search").withColor(Ui.DIM));
        searchBox.setChangedListener(s -> {
            search = s;
            scroll = 0;
        });
        searchBox.visible = tab != 6;
        addDrawableChild(searchBox);
        if (tab == 6) {
            addDrawableChild(new AotButton(x + 10, gridTop(), 140, 20, Text.literal("Give Marks"), () -> give("marks", "")));
            addDrawableChild(new AotButton(x + 156, gridTop(), 140, 20, Text.literal("Give Gold"), () -> give("gold", "")));
        }
    }

    private int stepper(int x, int y, String label, java.util.function.IntSupplier get, java.util.function.IntConsumer set) {
        int big = label.equals("Amount") ? 1000 : 10;
        addDrawableChild(new AotButton(x, y, 16, 16, Text.literal("-"), () -> {
            set.accept(get.getAsInt() - (hasShiftDown() ? big : 1));
            clearAndInit();
        }));
        addDrawableChild(new AotButton(x + 18, y, 90, 16, Text.literal(label + " " + get.getAsInt()), () -> { })).active = false;
        addDrawableChild(new AotButton(x + 110, y, 16, 16, Text.literal("+"), () -> {
            set.accept(get.getAsInt() + (hasShiftDown() ? big : 1));
            clearAndInit();
        }));
        return x + 130;
    }

    /** The entries of this tab, filtered: [id, label, colour]. */
    private List<String[]> entries() {
        List<String[]> out = new ArrayList<>();
        if (view == null) return out;
        String q = search.toLowerCase(Locale.ROOT);
        List<String> src = switch (tab) {
            case 0 -> view.weapons();
            case 1 -> view.armor();
            case 2 -> view.items();
            case 3 -> view.crates();
            case 4 -> view.titles();
            case 5 -> view.cosmetics();
            default -> List.of();
        };
        for (String e : src) {
            String[] a = e.split("\\|");
            String label;
            int col = Ui.CREAM;
            if (tab <= 2) {
                Item it = item(a[0]);
                if (it == null) continue;
                label = it.getName().getString();
            } else if (tab == 4) {
                label = a[1];
                try {
                    col = 0xFF000000 | Integer.parseInt(a[2]);
                } catch (Exception ignored) { }
            } else label = a.length > 1 ? a[1] : a[0];
            if (!q.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(q) && !a[0].contains(q)) continue;
            out.add(new String[] {a[0], label, Integer.toString(col)});
        }
        return out;
    }

    private static Item item(String id) {
        Identifier i = Identifier.tryParse(id);
        return i == null || !Registries.ITEM.containsId(i) ? null : Registries.ITEM.get(i);
    }

    private boolean icons() { return tab <= 2; }
    private int cell() { return icons() ? 24 : 0; }
    private int cols() { return icons() ? (pw() - 20) / 24 : Math.max(1, (pw() - 20) / 170); }
    private int rowH() { return icons() ? 24 : 18; }
    private int rows() { return Math.max(1, (py() + ph() - 10 - gridTop()) / rowH()); }

    private void give(String kind, String id) {
        if (view == null || view.players().isEmpty()) return;
        String who = view.players().get(target).split("\\|")[0];
        boolean far = view.players().get(target).endsWith("|0");
        ClientPlayNetworking.send(new Net.CatalogGive(kind, id, rarity, INFUSIONS[infusion], level, amount, who,
            inbox || far || kind.equals("crate"), jackpot, tag));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (tab == 6) return false;
        int i = at(mx, my);
        List<String[]> list = entries();
        if (i >= 0 && i < list.size()) {
            String kind = switch (tab) {
                case 0 -> "weapon";
                case 1 -> "armor";
                case 2 -> "item";
                case 3 -> "crate";
                case 4 -> "title";
                default -> "cosmetic";
            };
            give(kind, list.get(i)[0]);
            client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
                net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.2f));
            return true;
        }
        return false;
    }

    private int at(double mx, double my) {
        int x0 = px() + 10, y0 = gridTop();
        if (mx < x0 || my < y0) return -1;
        int cw = icons() ? 24 : (pw() - 20) / cols();
        int c = (int) ((mx - x0) / cw), r = (int) ((my - y0) / rowH());
        if (c >= cols() || r >= rows()) return -1;
        return (scroll + r) * cols() + c;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        int total = (entries().size() + cols() - 1) / cols();
        scroll = Math.max(0, Math.min(Math.max(0, total - rows()), scroll - (int) Math.signum(vy)));
        return true;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        int x = px(), y = py(), w = pw();
        Ui.panel(c, x, y, w, ph());
        Ui.text(c, Ui.title("CATALOG"), x + 12, y + 7, 1.3f, Ui.GOLD, false);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        super.render(c, mouseX, mouseY, delta);
        if (view == null || tab == 6) return;
        List<String[]> list = entries();
        int x0 = px() + 10, y0 = gridTop(), cols = cols(), rows = rows();
        int cw = icons() ? 24 : (pw() - 20) / cols;
        int hover = at(mouseX, mouseY);
        for (int k = 0; k < rows * cols; k++) {
            int i = scroll * cols + k;
            if (i >= list.size()) break;
            String[] e = list.get(i);
            int cx = x0 + (k % cols) * cw, cy = y0 + (k / cols) * rowH();
            if (icons()) {
                Ui.well(c, cx, cy, 22, 22);
                if (gear()) c.fill(cx + 1, cy + 20, cx + 21, cy + 21, RARITY_COL[rarity]);
                if (i == hover) c.fill(cx + 1, cy + 1, cx + 21, cy + 21, 0x40FFFFFF);
                Item it = item(e[0]);
                if (it != null) c.drawItem(new ItemStack(it), cx + 3, cy + 3);
            } else {
                c.fill(cx, cy, cx + cw - 4, cy + 16, i == hover ? 0x60FFFFFF : 0x60000000);
                int col = Integer.parseInt(e[2]);
                if (tab == 3) col = jackpot ? 0xFFFF6A5A : Ui.GOLD;
                Ui.text(c, Text.literal(e[1]), cx + 5, cy + 4, 0.8f, col, false);
            }
        }
        if (hover >= 0 && hover < list.size() && icons()) {
            c.drawTooltip(textRenderer, Text.literal(list.get(hover)[1]), mouseX, mouseY);
        }
        int total = (list.size() + cols - 1) / cols;
        if (total > rows) {
            String s = (scroll + 1) + "-" + Math.min(total, scroll + rows) + " of " + total;
            Ui.text(c, Text.literal(s), px() + pw() - 12 - Ui.font().getWidth(s) * 0.7f, py() + ph() - 10, 0.7f, Ui.MUTED, false);
        }
    }

    @Override
    public boolean shouldPause() { return false; }
}
