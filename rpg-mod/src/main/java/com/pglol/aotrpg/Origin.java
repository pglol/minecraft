package com.pglol.aotrpg;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

/** Where a character grew up: a starting town and a small bonus. */
public enum Origin {
    SHIGANSHINA("Shiganshina", "shiganshina-district", Items.BRICKS, Stat.RESOLVE,
        "The southern district of Wall Maria.", "Where it all began. +1 Resolve"),
    TROST("Trost", "trost-district", Items.OAK_DOOR, Stat.AGILITY,
        "A busy gate town on Wall Rose.", "Quick on your feet. +1 Agility"),
    RAGAKO("Ragako Village", "ragako-village", Items.WHEAT, Stat.ENDURANCE,
        "A quiet farming village inside Wall Rose.", "Raised on hard work. +1 Endurance"),
    STOHESS("Stohess", "stohess-district", Items.IRON_BARS, Stat.STRENGTH,
        "A wealthy trade district of Wall Sina.", "Well fed and strong. +1 Strength"),
    MITRAS("Mitras", "mitras", Items.GOLD_INGOT, null,
        "The royal capital, home of the nobility.", "Born to privilege. Start with 200 Marks"),
    UNDERGROUND("The Underground", "underground-city", Items.LANTERN, Stat.AGILITY,
        "The lawless city beneath Mitras.", "Survived by wits alone. +1 Agility, +1 Strength");

    public final String title, placeId, blurb, bonus;
    public final Item icon;
    public final Stat bonusStat;

    Origin(String title, String placeId, Item icon, Stat bonusStat, String blurb, String bonus) {
        this.title = title;
        this.placeId = placeId;
        this.icon = icon;
        this.bonusStat = bonusStat;
        this.blurb = blurb;
        this.bonus = bonus;
    }
}
