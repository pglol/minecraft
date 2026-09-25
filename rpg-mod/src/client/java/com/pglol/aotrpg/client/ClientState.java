package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;

/** What the server last told us about our character. */
public final class ClientState {
    private ClientState() {}

    public static Net.Sync profile;
    public static float stamina = -1, maxStamina = 100;
    public static boolean exhausted;

    public static void reset() {
        profile = null;
        stamina = -1;
        maxStamina = 100;
        exhausted = false;
    }
}
