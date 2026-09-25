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

    public static Net.PartyMember partyMember(java.util.UUID id) {
        for (Net.PartyMember m : party) if (m.id().equals(id)) return m;
        return null;
    }

    public static void reset() {
        profile = null;
        stamina = -1;
        maxStamina = 100;
        exhausted = false;
        party = java.util.List.of();
    }
}
