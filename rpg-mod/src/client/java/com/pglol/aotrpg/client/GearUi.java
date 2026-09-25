package com.pglol.aotrpg.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;

/** Rarity colours for gear on the client: slot backing and the glow of dropped gear. */
public final class GearUi {
    private GearUi() {}

    /** Rarity ordinal (0 Common .. 4 Legendary), or -1 for items that are not gear. */
    public static int rarity(ItemStack s) {
        if (s == null || s.isEmpty()) return -1;
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        if (c == null) return -1;
        var tag = c.copyNbt();
        if (!tag.contains("aot_gear")) return -1;
        return switch (tag.getCompound("aot_gear").getString("rarity")) {
            case "UNCOMMON" -> 1;
            case "RARE" -> 2;
            case "EPIC" -> 3;
            case "LEGENDARY" -> 4;
            default -> 0;
        };
    }

    public static int color(int rarity) {
        return switch (rarity) {
            case 1 -> 0x55FF55;
            case 2 -> 0x5599FF;
            case 3 -> 0xC055FF;
            case 4 -> 0xFFB020;
            default -> 0xDDDDDD;
        };
    }

    /** A soft glow behind gear in a slot, stronger (and pulsing) the rarer it is. */
    public static void backing(DrawContext c, ItemStack s, int x, int y) {
        int r = rarity(s);
        if (r < 1) return;
        int rgb = color(r);
        float pulse = r >= 3 ? (float) (0.75 + 0.25 * Math.sin(System.currentTimeMillis() / 300.0)) : 1f;
        int a = (int) ((40 + r * 22) * pulse);
        c.fillGradient(x, y, x + 16, y + 16, (a / 3 << 24) | rgb, (a << 24) | rgb);
        c.drawBorder(x, y, 16, 16, ((int) (a * 1.4f) << 24) | rgb);
    }
}
