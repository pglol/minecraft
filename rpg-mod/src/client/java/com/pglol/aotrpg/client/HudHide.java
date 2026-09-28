package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;

import java.util.HashSet;
import java.util.Set;

/**
 * Hiding the HUD for a while (a cutscene, the lobby) without losing the player's own F1 choice:
 * each asks under its own reason, and the HUD comes back as the player had it once none remain.
 * (Two of them each remembering and restoring the other's "hidden" is what left it stuck off.)
 */
public final class HudHide {
    private HudHide() {}

    private static final Set<String> reasons = new HashSet<>();
    private static boolean userHidden;

    public static void hide(String reason) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (reasons.isEmpty()) userHidden = mc.options.hudHidden;
        reasons.add(reason);
        mc.options.hudHidden = true;
    }

    public static void show(String reason) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!reasons.remove(reason)) return;
        if (reasons.isEmpty()) mc.options.hudHidden = userHidden;
    }
}
