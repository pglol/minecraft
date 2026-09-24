package com.pglol.aotrpg;

import java.util.EnumMap;
import java.util.Map;

/** Everything the mod remembers about one player's character. Saved as JSON. */
public final class Profile {
    public boolean created;
    public String name = "";
    public String firstName = "";
    public String familyName = "";
    public Origin origin;
    public Discipline discipline;
    public int level = 1;
    public long xp;
    public int points;
    public Map<Stat, Integer> stats = new EnumMap<>(Stat.class);
    public int chapter;
    public int titanKills;

    public int stat(Stat s) {
        return stats.getOrDefault(s, 0);
    }

    /** Stat including the origin bonus. */
    public int total(Stat s) {
        int v = stat(s);
        if (origin != null && origin.bonusStat == s) v++;
        if (origin == Origin.UNDERGROUND && s == Stat.STRENGTH) v++;
        return v;
    }

    public static long xpForNext(int level) {
        return Math.round(40 * Math.pow(level, 1.35));
    }
}
