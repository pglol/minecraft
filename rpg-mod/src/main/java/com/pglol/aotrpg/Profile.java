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
    public int skillPoints;
    public java.util.Set<Skill> skills = new java.util.HashSet<>();
    public int chapter;
    /** Titan kills when the current objective started (for kill goals). */
    public int questBase;
    /** Set when a self-reset should not hand out another starter kit. */
    public boolean kitGiven;
    /** Side quest progress: id -> {state (1 active, 2 done), progress}. */
    public java.util.Map<String, int[]> quests = new java.util.HashMap<>();
    /** The quest shown under the minimap and marked on the map ("main" = the story). */
    public String tracked = "main";
    /** "story" (gear protected on death) or "extraction" (items drop). */
    public String mode = "story";
    public int titanKills;
    /** Wallet: Marks, the in-game currency earned by playing. */
    public long marks;
    /** Times this character used a bloodline reroll (at most Characters.MAX_REROLLS). */
    public int bloodlineRerolls;
    /** Faction (Factions.Faction name, empty for none), reputation, when joined. */
    public String faction = "";
    public int factionRep;
    public long factionJoined;
    /** Work orders this cycle: id -> progress (-1 done). */
    public java.util.Map<String, Integer> orders = new java.util.HashMap<>();
    /** Roleplay: participating, rank points, operator-given role title and colour. */
    public boolean rp;
    public long rpPoints;
    public String role = "";
    public int roleColor;
    /** Game modes unlocked by an operator (beyond what the story unlocks). */
    public java.util.Set<String> unlockedModes = new java.util.HashSet<>();
    /** Lifestyle skills (smithing, fishing, cooking): skill -> xp. */
    public java.util.Map<String, Long> lifestyle = new java.util.HashMap<>();

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

    public boolean has(Skill s) {
        return skills.contains(s);
    }

    public float maxStamina() {
        return 100 + 5 * total(Stat.ENDURANCE) + (has(Skill.SECOND_WIND) ? 30 : 0);
    }

    public static long xpForNext(int level) {
        return Math.round(40 * Math.pow(level, 1.35));
    }
}
