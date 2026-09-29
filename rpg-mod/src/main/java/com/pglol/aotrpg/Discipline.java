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

    /** The mark a cadet draws on their first day, which reads as this strength. */
    public String markName() {
        return switch (this) {
            case SCOUT -> "Wings of Freedom";
            case VANGUARD -> "The Fang";
            case GUARDIAN -> "The Bulwark";
            case MARKSMAN -> "Hawk's Eye";
            case MEDIC -> "The Ember";
        };
    }

    public int markColor() {
        return switch (this) {
            case SCOUT -> 0x5FB8FF;
            case VANGUARD -> 0xE0463A;
            case GUARDIAN -> 0xD8A850;
            case MARKSMAN -> 0x7FD06A;
            case MEDIC -> 0xFF8A3A;
        };
    }

    /** What the mark says about you. */
    public String markLore() {
        return switch (this) {
            case SCOUT -> "Sweeping and open. You were never meant to stay inside the Walls.";
            case VANGUARD -> "Sharp, jagged, all edges. You go straight at whatever's in front of you.";
            case GUARDIAN -> "Closed and square. Nothing gets past you, and nobody behind you falls.";
            case MARKSMAN -> "Clean lines and still points. You see it before anyone else does.";
            case MEDIC -> "Round and whole. You hold the squad together when it's coming apart.";
        };
    }

    Discipline(String title, Item icon, String blurb, String perks) {
        this.title = title;
        this.icon = icon;
        this.blurb = blurb;
        this.perks = perks;
    }
}
