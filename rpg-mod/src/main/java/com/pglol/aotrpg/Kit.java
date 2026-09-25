package com.pglol.aotrpg;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/** The cadet starter kit handed out when a character is created. */
public final class Kit {
    private Kit() {}

    private static ItemStack named(Item item, int count, String name, Formatting color, String... lore) {
        ItemStack s = new ItemStack(item, count);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name).formatted(color).styled(st -> st.withItalic(false)));
        if (lore.length > 0) {
            List<Text> lines = new ArrayList<>();
            for (String l : lore) lines.add(Text.literal(l).formatted(Formatting.GRAY).styled(st -> st.withItalic(false)));
            s.set(DataComponentTypes.LORE, new LoreComponent(lines));
        }
        return s;
    }

    private static ItemStack uniform(Item item, int rgb, String name, String lore) {
        ItemStack s = named(item, 1, name, Formatting.GOLD, lore);
        s.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(rgb, false));
        s.set(DataComponentTypes.UNBREAKABLE, new UnbreakableComponent(false));
        return s;
    }

    public static void give(ServerPlayerEntity p, Profile pr, int[] camp) {
        // Uniform: the AoT mod's uniform when installed.
        Item uniformItem = AotItems.exact("uniform");
        if (uniformItem != null) p.giveItemStack(new ItemStack(uniformItem));
        else p.giveItemStack(uniform(Items.LEATHER_CHESTPLATE, 0x6B4F2A, "Cadet Jacket", "Standard issue, 104th Cadet Corps"));
        p.giveItemStack(uniform(Items.LEATHER_LEGGINGS, 0xE8E0C8, "Training Trousers", "Standard issue, 104th Cadet Corps"));
        p.giveItemStack(uniform(Items.LEATHER_BOOTS, 0x3B2A1A, "ODM Boots",
            "Strapped boots for ODM gear. Standard issue, 104th Cadet Corps"));
        if (AotItems.present()) {
            // Standard ODM gear: one grip for each hand, a gas canister, and supplies in the satchel.
            ItemStack odm = AotItems.bestStack(1, AotItems.ODM, "handle", "blade", "gas", "boot", "uniform");
            if (odm.isEmpty()) odm = AotItems.bestStack(1, AotItems.GRIP, "blade");
            if (!odm.isEmpty()) {
                p.giveItemStack(odm.copy());
                p.giveItemStack(odm.copy());
            }
            ItemStack gas = AotItems.bestStack(1, AotItems.GAS);
            if (!gas.isEmpty()) p.giveItemStack(gas);
            ItemStack clusters = AotItems.bestStack(16, AotItems.CLUSTER);
            if (!clusters.isEmpty()) AotRpg.SATCHEL.add(p, clusters);
            Item blade = AotItems.exact("blade_component");
            if (blade != null) AotRpg.SATCHEL.add(p, new ItemStack(blade, Math.min(16, blade.getMaxCount())));
            else {
                ItemStack blades = AotItems.bestStack(8, AotItems.BLADE, "grip", "handle");
                if (!blades.isEmpty()) AotRpg.SATCHEL.add(p, blades);
            }
            if (odm.isEmpty()) p.giveItemStack(named(Items.IRON_SWORD, 1, "Training Blade", Formatting.WHITE, "Dull, but it will do."));
        } else {
            p.giveItemStack(named(Items.IRON_SWORD, 1, "Training Blade", Formatting.WHITE, "Dull, but it will do."));
        }
        p.giveItemStack(new ItemStack(Items.BREAD, 12));
        switch (pr.discipline) {
            case SCOUT -> p.giveItemStack(named(Items.FIREWORK_ROCKET, 4, "Signal Flare", Formatting.GREEN, "Fire to signal your squad."));
            case VANGUARD -> p.giveItemStack(named(Items.IRON_SWORD, 1, "Spare Blade", Formatting.WHITE, "Blades dull fast against titans."));
            case GUARDIAN -> p.giveItemStack(named(Items.SHIELD, 1, "Garrison Shield", Formatting.WHITE));
            case MARKSMAN -> {
                p.giveItemStack(named(Items.BOW, 1, "Hunting Bow", Formatting.WHITE));
                p.giveItemStack(new ItemStack(Items.ARROW, 48));
            }
            case MEDIC -> {
                p.giveItemStack(named(Items.GOLDEN_APPLE, 3, "Field Rations", Formatting.GOLD, "Restores health quickly."));
                p.giveItemStack(named(Items.GLISTERING_MELON_SLICE, 6, "Medicinal Herbs", Formatting.GREEN));
            }
        }
        if (pr.origin == Origin.MITRAS) p.giveItemStack(new ItemStack(Items.EMERALD, 15));
        p.giveItemStack(new ItemStack(Items.EMERALD, 5));
        String where = camp == null ? "the Cadet Training Camp inside Wall Rose" : "the Cadet Training Camp (x " + camp[0] + ", z " + camp[2] + ")";
        AotRpg.SATCHEL.add(p, Satchel.markStory(named(Items.PAPER, 1, "Recruitment Letter", Formatting.YELLOW,
            "To: " + pr.name,
            "You have been accepted into the",
            "104th Cadet Corps. Report to",
            where + ".",
            "",
            "Dedicate your heart.",
            "",
            "Story item: kept in your satchel.")));
        // A few supplies to start cooking with.
        AotRpg.SATCHEL.add(p, new ItemStack(Items.BEEF, 3));
        AotRpg.SATCHEL.add(p, new ItemStack(Items.POTATO, 4));
    }
}
