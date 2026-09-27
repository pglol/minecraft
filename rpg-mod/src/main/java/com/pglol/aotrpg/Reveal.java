package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The big reward moments (level up, a rare drop, a pass tier, an achievement, a raid cleared...)
 * play an animated reveal on screen, sized to the moment: rarity 0 is a quick flourish, 5 (Mythic)
 * stops you in your tracks. Players without the mod get a plain title.
 */
public final class Reveal {
    private Reveal() {}

    public static void show(ServerPlayerEntity p, String title, String sub, String icon, int rarity) {
        if (ServerPlayNetworking.canSend(p, Net.RewardReveal.ID)) {
            ServerPlayNetworking.send(p, new Net.RewardReveal(title, sub, icon, Math.max(0, Math.min(5, rarity))));
            return;
        }
        Titles.show(p, Text.literal(title).formatted(Formatting.GOLD, Formatting.BOLD), Text.literal(sub).formatted(Formatting.YELLOW), 5, 50, 15);
        p.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.7f, 1.1f);
    }
}
