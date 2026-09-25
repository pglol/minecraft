package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Loadout;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/** Shared look of the loadout slots: colours and ghost icons for each kind. */
public final class LoadoutUi {
    private LoadoutUi() {}

    public static int color(Loadout.Kind k) {
        return switch (k) {
            case MELEE -> 0xFFC0463A;
            case RANGED -> 0xFFD98A3A;
            case SIDEARM -> 0xFFB8606A;
            case TOOL -> 0xFFA88A5A;
            case HEAL -> 0xFF5BD35B;
            case MOUNT -> 0xFF9A7650;
            case SIGNAL -> 0xFF5A9FD0;
            case FREE -> 0xFF8F8A7A;
        };
    }

    private static ItemStack aot(String path, Item fallback) {
        Item i = Registries.ITEM.get(Identifier.of("dannys-aot", path));
        return new ItemStack(i == Items.AIR ? fallback : i);
    }

    private static ItemStack[] ghosts;

    /** The faint picture in an empty slot showing what goes there. */
    public static ItemStack ghost(Loadout.Kind k) {
        if (ghosts == null) {
            ghosts = new ItemStack[] {aot("blade", Items.IRON_SWORD), aot("apg_gun", Items.BOW), new ItemStack(Items.SHIELD),
                new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.BREAD), new ItemStack(Items.SADDLE), aot("flare_gun", Items.TORCH),
                ItemStack.EMPTY};
        }
        return ghosts[k.ordinal()];
    }

    /** Draws a ghost item, dimmed so it reads as a placeholder. */
    public static void drawGhost(DrawContext c, ItemStack g, int x, int y) {
        if (g.isEmpty()) return;
        c.drawItem(g, x, y);
        c.getMatrices().push();
        c.getMatrices().translate(0, 0, 250);
        c.fill(x, y, x + 16, y + 16, 0xB8101410);
        c.getMatrices().pop();
    }

    /** A downward point w pixels wide (the bottom of a shield). */
    public static void chevronDown(DrawContext c, int x, int y, int w, int color) {
        for (int r = 0; r < w / 2; r++) c.fill(x + r, y + r, x + w - r, y + r + 1, color);
    }

    /** A chevron pointing left (dir -1) or right (dir 1), h pixels tall. */
    public static void chevron(DrawContext c, int x, int y, int h, int dir, int color) {
        int half = h / 2;
        for (int r = 0; r < h; r++) {
            int d = half - Math.abs(r - half);
            if (dir > 0) c.fill(x, y + r, x + d, y + r + 1, color);
            else c.fill(x - d, y + r, x, y + r + 1, color);
        }
    }
}
