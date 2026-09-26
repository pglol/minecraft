package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Loadout;
import com.pglol.aotrpg.Satchel;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.function.Supplier;

/**
 * A worn or loadout place drawn as a proper slot: a framed tile in the place's colour, what is in
 * it (or a faint picture of what belongs there), and its name underneath.
 */
public final class PlaceTile extends PressableWidget {
    private final Runnable action;
    private final Supplier<ItemStack> stack;
    private final String label;
    private final int color;
    private final ItemStack ghost;
    public boolean highlight;

    public PlaceTile(int x, int y, int size, int target, Supplier<ItemStack> stack, Runnable action) {
        super(x, y, size, size, Text.empty());
        this.action = action;
        this.stack = stack;
        this.label = shortName(target);
        this.color = color(target);
        this.ghost = ghost(target);
    }

    public static String shortName(int t) {
        return switch (t) {
            case 103 -> "HEAD";
            case 102 -> "CHEST";
            case 101 -> "LEGS";
            case 100 -> "FEET";
            case Satchel.OFF -> "OFF";
            default -> switch (Loadout.SLOTS[t]) {
                case MELEE -> "MELEE";
                case RANGED -> "RANGED";
                case SIDEARM -> "SIDE";
                case TOOL -> "TOOL";
                case HEAL -> "HEAL";
                case MOUNT -> "MOUNT";
                case SIGNAL -> "SIGNAL";
                case FREE -> "FREE";
            };
        };
    }

    public static int color(int t) {
        if (t >= Satchel.ARMOR) return 0xFFB8955A;
        if (t == Satchel.OFF) return LoadoutUi.color(Loadout.Kind.MELEE);
        return LoadoutUi.color(Loadout.SLOTS[t]);
    }

    private static ItemStack ghost(int t) {
        return switch (t) {
            case 103 -> new ItemStack(Items.LEATHER_HELMET);
            case 102 -> new ItemStack(Items.LEATHER_CHESTPLATE);
            case 101 -> new ItemStack(Items.LEATHER_LEGGINGS);
            case 100 -> new ItemStack(Items.LEATHER_BOOTS);
            case Satchel.OFF -> LoadoutUi.ghost(Loadout.Kind.MELEE);
            default -> LoadoutUi.ghost(Loadout.SLOTS[t]);
        };
    }

    @Override
    public void onPress() {
        action.run();
    }

    @Override
    protected void renderWidget(DrawContext c, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), s = width;
        boolean hov = isHovered() && active;
        int col = color | 0xFF000000;
        // Tile: dark well, coloured frame (thicker when hovered or offered), colour band on top.
        c.fill(x, y, x + s, y + s, hov ? 0xF0262E24 : 0xF0141914);
        c.fillGradient(x + 1, y + 1, x + s - 1, y + s - 1, (col & 0x00FFFFFF) | 0x30000000, 0x00000000);
        c.drawBorder(x, y, s, s, hov || highlight ? Ui.GOLD : col);
        if (hov || highlight) c.drawBorder(x - 1, y - 1, s + 2, s + 2, (Ui.GOLD & 0x00FFFFFF) | 0x80000000);
        c.fill(x + 1, y + 1, x + s - 1, y + 3, col);
        ItemStack st = stack.get();
        float sc = (s - 6) / 16f;
        var m = c.getMatrices();
        m.push();
        m.translate(x + (s - 16 * sc) / 2f, y + (s - 16 * sc) / 2f + 1, 0);
        m.scale(sc, sc, 1);
        if (!st.isEmpty()) {
            c.drawItem(st, 0, 0);
            c.drawItemInSlot(Ui.font(), st, 0, 0);
        } else {
            LoadoutUi.drawGhost(c, ghost, 0, 0);
        }
        m.pop();
        if (!active) c.fill(x, y, x + s, y + s, 0x90000000);
        Ui.text(c, Text.literal(label), x + s / 2f, y + s + 2, 0.55f, hov ? Ui.GOLD : (col & 0x00FFFFFF) | 0xE0000000, true);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder b) {
        appendDefaultNarrations(b);
    }
}
