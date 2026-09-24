package com.pglol.aotrpg;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

/** Character attributes that players raise with points. */
public enum Stat {
    STRENGTH("Strength", Items.IRON_SWORD, "+0.5 melee damage per point"),
    AGILITY("Agility", Items.FEATHER, "+1.5% movement speed per point"),
    ENDURANCE("Endurance", Items.GOLDEN_APPLE, "+2 health (1 heart) per point"),
    RESOLVE("Resolve", Items.SHIELD, "+1 armor per point");

    public final String title, effect;
    public final Item icon;

    Stat(String title, Item icon, String effect) {
        this.title = title;
        this.icon = icon;
        this.effect = effect;
    }
}
