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
    /** Up- or downgrade against what's here (1 better, -1 worse, 0 even), or null for none. */
    public Integer badge;

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
                case GAS -> "GAS";
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
        // A socket in the leather, the place's colour as a thin line under it, brass when offered.
        Ui.socket(c, x, y, s);
        c.fill(x + 2, y + s - 2, x + s - 2, y + s - 1, (col & 0x00FFFFFF) | 0xC0000000);
        if (hov || highlight) c.drawBorder(x - 1, y - 1, s + 2, s + 2, hov ? Ui.GOLD : 0xB08C7248);
        ItemStack st = stack.get();
        float sc = (s - 6) / 16f;
        var m = c.getMatrices();
        m.push();
        m.translate(x + (s - 16 * sc) / 2f, y + (s - 16 * sc) / 2f, 0);
        m.scale(sc, sc, 1);
        if (!st.isEmpty()) {
            c.drawItem(st, 0, 0);
            c.drawItemInSlot(Ui.font(), st, 0, 0);
        } else {
            LoadoutUi.drawGhost(c, ghost, 0, 0);
        }
        m.pop();
        if (!active) c.fill(x, y, x + s, y + s, 0x90000000);
        if (badge != null) {
            m.push();
            m.translate(0, 0, 300);
            String b = badge > 0 ? "▲" : badge < 0 ? "▼" : "≈";
            Ui.text(c, Text.literal(b), x + s - 7, y + 1, 0.8f, badge > 0 ? 0xFF5BD35B : badge < 0 ? 0xFFE0463A : Ui.CREAM, false);
            m.pop();
        }
        Ui.text(c, Text.literal(label), x + s / 2f, y + s + 3, 0.55f, hov ? Ui.GOLD : (col & 0x00FFFFFF) | 0xE0000000, true);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder b) {
        appendDefaultNarrations(b);
    }
}
