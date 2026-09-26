package com.pglol.aotrpg.client;

import com.pglol.aotrpg.PlayerClass;
import net.minecraft.text.Text;

/** Role badges for names and lineups. */
public final class RoleUi {
    private RoleUi() {}

    public static PlayerClass of(int ordinal) {
        return ordinal >= 0 && ordinal < PlayerClass.values().length ? PlayerClass.values()[ordinal] : null;
    }

    public static Text badge(int ordinal) {
        PlayerClass c = of(ordinal);
        return c == null ? Text.empty() : Text.literal(c.tag() + " " + c.title).withColor(c.color);
    }
}
