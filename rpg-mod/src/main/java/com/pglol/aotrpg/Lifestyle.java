package com.pglol.aotrpg;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Lifestyle skills: smithing, fishing, cooking. Levels come from practice (the minigames). */
public final class Lifestyle {
    public static final String SMITHING = "smithing", FISHING = "fishing", COOKING = "cooking";
    public static final int MAX = 50;

    public static int level(Profile pr, String skill) {
        long xp = pr.lifestyle.getOrDefault(skill, 0L);
        return (int) Math.min(MAX, Math.floor(Math.sqrt(xp / 40.0)));
    }

    public static void add(ServerPlayerEntity p, String skill, long xp) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        int before = level(pr, skill);
        pr.lifestyle.merge(skill, xp, Long::sum);
        int after = level(pr, skill);
        if (after > before) {
            p.sendMessage(Text.literal(Character.toUpperCase(skill.charAt(0)) + skill.substring(1) + " " + after).formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal("  ·  your craft improves").formatted(Formatting.GRAY)), false);
            p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.5f, 1.6f);
        }
        AotRpg.PROFILES.save(p.getUuid());
        AotRpg.SEASON.xp(p, xp / 2);
    }
}
