package com.pglol.aotrpg;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

/** A fighting style chosen at character creation. */
public enum Discipline {
    SCOUT("Scout", Items.FEATHER, "Fast and agile. Built for ODM gear.",
        "+8% speed, +10% jump, softer landings"),
    VANGUARD("Vanguard", Items.IRON_SWORD, "Front-line blade fighter.",
        "+2 melee damage, +10% attack speed"),
    GUARDIAN("Guardian", Items.SHIELD, "Holds the line when titans break through.",
        "+6 health, +2 armor, +20% knockback resistance"),
    MARKSMAN("Marksman", Items.BOW, "Strikes from range.",
        "+5% speed, starts with a bow and arrows"),
    MEDIC("Medic", Items.GOLDEN_APPLE, "Keeps the squad alive.",
        "+4 health, starts with healing supplies");

    public final String title, blurb, perks;
    public final Item icon;

    Discipline(String title, Item icon, String blurb, String perks) {
        this.title = title;
        this.icon = icon;
        this.blurb = blurb;
        this.perks = perks;
    }
}
