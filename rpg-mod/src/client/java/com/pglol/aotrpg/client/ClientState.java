package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;

/** What the server last told us about our character. */
public final class ClientState {
    private ClientState() {}

    public static Net.Sync profile;
    public static float stamina = -1, maxStamina = 100;
    public static boolean exhausted;
    /** The other members of our party (empty = no party). */
    public static java.util.List<Net.PartyMember> party = java.util.List.of();

    /** Character names of online players, for name plates. */
    public static java.util.Map<java.util.UUID, Net.RosterEntry> roster = new java.util.HashMap<>();
    /** Current story objective, or null. */
    public static Net.Objective objective;
    public static boolean minimap = true;
    /** Cooking fire positions as x,y,z triples. */
    public static int[] campfires = new int[0];
    public static Net.DeathInfo death;
    public static java.util.List<Net.Area> areas = java.util.List.of();
    public static java.util.List<Net.Marker> markers = java.util.List.of();
    public static java.util.List<Net.QuestView> quests = java.util.List.of();

    public static java.util.List<String> cosmetics = java.util.List.of("trail_tracer");
    public static String trail = "trail_tracer";
    public static boolean cosmeticsAll;
    public static long marks, gold;
    public static Net.CharacterList characters;
    public static Net.MarketView market;
    public static java.util.List<Net.ExchangeEntry> exchange = java.util.List.of();
    public static Net.FactionView factions;
    public static java.util.Map<java.util.UUID, Net.SheathState> sheaths = new java.util.HashMap<>();

    public static Net.PartyMember partyMember(java.util.UUID id) {
        for (Net.PartyMember m : party) if (m.id().equals(id)) return m;
        return null;
    }

    /** Forget the character only (a reset or a new character); world data stays. */
    public static void resetCharacter() {
        profile = null;
        stamina = -1;
        maxStamina = 100;
        exhausted = false;
        objective = null;
        death = null;
    }

    public static void reset() {
        profile = null;
        stamina = -1;
        maxStamina = 100;
        exhausted = false;
        party = java.util.List.of();
        roster = new java.util.HashMap<>();
        objective = null;
        campfires = new int[0];
        death = null;
        areas = java.util.List.of();
        markers = java.util.List.of();
        quests = java.util.List.of();
    }
}
