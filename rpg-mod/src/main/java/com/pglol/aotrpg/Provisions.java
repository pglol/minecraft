package com.pglol.aotrpg;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.Map;

/**
 * One food system. Ready-to-eat vanilla food (bread, cooked meat, pies...) from loot, drops, trades
 * or crafting becomes a named RPG provision the moment it reaches a player's inventory or satchel,
 * tagged like campfire meals, so it counts for the heal slot and [H]. Raw meat, fish and
 * vegetables stay cooking ingredients. Rotten or poisonous food is never a provision.
 */
public final class Provisions {
    private Provisions() {}

    public static final Map<Item, String> PREPARED = Map.ofEntries(
        Map.entry(Items.BREAD, "Field Bread"),
        Map.entry(Items.COOKED_BEEF, "Grilled Steak"),
        Map.entry(Items.COOKED_PORKCHOP, "Roast Pork"),
        Map.entry(Items.COOKED_CHICKEN, "Roast Chicken"),
        Map.entry(Items.COOKED_MUTTON, "Roast Mutton"),
        Map.entry(Items.COOKED_RABBIT, "Roast Rabbit"),
        Map.entry(Items.COOKED_COD, "Smoked Cod"),
        Map.entry(Items.COOKED_SALMON, "Smoked Salmon"),
        Map.entry(Items.BAKED_POTATO, "Baked Potato"),
        Map.entry(Items.PUMPKIN_PIE, "Pumpkin Pie"),
        Map.entry(Items.COOKIE, "Biscuit"),
        Map.entry(Items.MUSHROOM_STEW, "Mushroom Stew"),
        Map.entry(Items.BEETROOT_SOUP, "Beet Soup"),
        Map.entry(Items.RABBIT_STEW, "Rabbit Stew"),
        Map.entry(Items.APPLE, "Apple"),
        Map.entry(Items.CARROT, "Carrot"),
        Map.entry(Items.MELON_SLICE, "Melon Slice"),
        Map.entry(Items.SWEET_BERRIES, "Sweet Berries"),
        Map.entry(Items.GLOW_BERRIES, "Glow Berries"),
        Map.entry(Items.DRIED_KELP, "Dried Kelp"),
        Map.entry(Items.HONEY_BOTTLE, "Honey"),
        Map.entry(Items.GOLDEN_CARROT, "Golden Carrot"));

    public static boolean isMeal(ItemStack s) {
        NbtComponent cd = s.get(DataComponentTypes.CUSTOM_DATA);
        return cd != null && cd.copyNbt().contains("aot_meal");
    }

    /** A campfire recipe meal (not a field provision). */
    public static boolean isCooked(ItemStack s) {
        NbtComponent cd = s.get(DataComponentTypes.CUSTOM_DATA);
        return cd != null && cd.copyNbt().contains("aot_meal") && !cd.copyNbt().getString("aot_meal").startsWith("field");
    }

    /** Food that heals: meals, provisions, or vanilla prepared food about to become one. */
    public static boolean isProvision(ItemStack s) {
        return !s.isEmpty() && (isMeal(s) || PREPARED.containsKey(s.getItem()));
    }

    /** Turns a plain prepared food stack into a provision in place. Returns true if changed. */
    public static boolean convert(ItemStack s) {
        if (s.isEmpty() || isMeal(s) || !PREPARED.containsKey(s.getItem())) return false;
        var food = s.get(DataComponentTypes.FOOD);
        int n = food != null ? food.nutrition() : 0;
        if (!s.contains(DataComponentTypes.CUSTOM_NAME)) {
            s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(PREPARED.get(s.getItem())).formatted(Formatting.YELLOW)
                .styled(st -> st.withItalic(false)));
        }
        s.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            Text.literal("+" + n + " hunger").formatted(Formatting.GRAY).styled(st -> st.withItalic(false)),
            Text.literal("Provision · heals in combat [H]").formatted(Formatting.DARK_GREEN).styled(st -> st.withItalic(false)))));
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, s, c -> c.putString("aot_meal", "field"));
        return true;
    }

    /** Converts every prepared food in an inventory. */
    public static boolean convertAll(Inventory inv) {
        boolean changed = false;
        for (int i = 0; i < inv.size(); i++) changed |= convert(inv.getStack(i));
        if (changed) inv.markDirty();
        return changed;
    }

    /** A ready-made kit ration. */
    public static ItemStack of(Item item, int count) {
        ItemStack s = new ItemStack(item, count);
        convert(s);
        return s;
    }
}
