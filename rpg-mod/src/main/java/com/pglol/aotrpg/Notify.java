package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** Slide-in notifications on the player's screen (falls back to chat without the mod). */
public final class Notify {
    private Notify() {}

    public static void toast(ServerPlayerEntity p, Text title, Text sub, int color, String icon, String key) {
        if (ServerPlayNetworking.canSend(p, Net.Toast.ID)) {
            ServerPlayNetworking.send(p, new Net.Toast(title, sub == null ? Text.empty() : sub, color, icon == null ? "" : icon, key == null ? "" : key));
        } else {
            p.sendMessage(sub == null ? title : title.copy().append(Text.literal("  ")).append(sub), false);
        }
    }

    public static void toast(ServerPlayerEntity p, Text title, Text sub, int color) {
        toast(p, title, sub, color, null, null);
    }
}
