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
    /** Lifetime counters for tasks and achievements (Tasks.TITANS, ...). */
    public java.util.Map<String, Long> counters = new java.util.HashMap<>();
    /** Task boards: period -> the period key they belong to; progress and claims by "PERIOD:task". */
    public java.util.Map<String, String> taskKeys = new java.util.HashMap<>();
    public java.util.Map<String, Long> taskProgress = new java.util.HashMap<>();
    public java.util.Set<String> taskClaimed = new java.util.HashSet<>();
    /** Achievements earned, and the one whose title is worn by the name ("" for none). */
    public java.util.Set<String> achievements = new java.util.HashSet<>();
    public String title = "";
    /** Skill tree resets used (at most Skill.MAX_RESETS), and whether the rebuilt trees were applied. */
    public int skillResets;
    /** Horses: owned horses, the ride-out one, and the first-ride quest (0 not started, 1 riding, 2 done). */
    public java.util.List<Horses.Horse> horses = new java.util.ArrayList<>();
    public String activeHorse = "";
    public int starterQuest;
    public double starterDist;
    public boolean skillsV2;
    /** Pets owned (Estate.PETS ids) and the one brought along on adventures ("" for none). */
    public java.util.List<String> pets = new java.util.ArrayList<>();
    public String companion = "";
    /** Where you wake after falling: "" the nearest recovery post, "home" your property (or house). */
    public String respawn = "";
    /** The role shown to others and put on Z/X/V (Infantry, Tank, Medic, Recon); whether class trees granted points. */
    public PlayerClass cls;
    public boolean skillsV3;

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

    public PlayerClass cls() {
        return cls != null ? cls : PlayerClass.of(discipline);
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
