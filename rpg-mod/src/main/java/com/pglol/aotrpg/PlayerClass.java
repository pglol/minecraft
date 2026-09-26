package com.pglol.aotrpg;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

/**
 * The role you play and show: others see it by your name, in your party and in raid lineups.
 * Each class has its own skill tree (open to everyone) with two actives and an ultimate; your
 * chosen role puts its class's actives on Z and X and its ultimate on V. Change it any time out
 * of combat and outside a boss raid.
 */
public enum PlayerClass {
    INFANTRY("Infantry", "DPS", Items.IRON_SWORD, 0xFFD0563A,
        "Front-line blade. Dashes through titans, rallies the squad, and tears napes apart.",
        new String[] {"Blade Rush", "War Cry", "Humanity's Strongest"}),
    TANK("Tank", "Defender", Items.SHIELD, 0xFF4A7AC0,
        "Holds the line. Draws titans onto itself and shields everyone nearby.",
        new String[] {"Provoke", "Bulwark", "Armored Resolve"}),
    MEDIC("Medic", "Healer", Items.GOLDEN_APPLE, 0xFF5BC06A,
        "Keeps the squad breathing. Heals, revives, and turns a lost fight around.",
        new String[] {"Field Dressing", "Sanctuary", "Blessing of the Walls"}),
    RECON("Recon", "Scout", Items.SPYGLASS, 0xFFC9A53A,
        "Eyes of the Survey Corps. Marks weak points, vanishes into smoke, and hunts napes.",
        new String[] {"Hunter's Mark", "Smoke Bomb", "Hunter's Eye"});

    public final String title, role, blurb;
    public final Item icon;
    public final int color;
    /** Names of the Z ability, the X ability and the ultimate. */
    public final String[] abilities;

    PlayerClass(String title, String role, Item icon, int color, String blurb, String[] abilities) {
        this.title = title;
        this.role = role;
        this.icon = icon;
        this.color = color;
        this.blurb = blurb;
        this.abilities = abilities;
    }

    /** The class a character started with, going by the discipline chosen at creation. */
    public static PlayerClass of(Discipline d) {
        if (d == null) return INFANTRY;
        return switch (d) {
            case VANGUARD -> INFANTRY;
            case GUARDIAN -> TANK;
            case MEDIC -> MEDIC;
            case SCOUT, MARKSMAN -> RECON;
        };
    }

    /** Short tag shown by names and in lineups. */
    public String tag() {
        return switch (this) {
            case INFANTRY -> "⚔";
            case TANK -> "⛨";
            case MEDIC -> "✚";
            case RECON -> "◎";
        };
    }
}
