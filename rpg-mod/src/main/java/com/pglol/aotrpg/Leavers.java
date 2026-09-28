package com.pglol.aotrpg;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Leaving matches early, over and over, earns a warning (a dropped connection you come back from doesn't count). */
public final class Leavers {
    private Leavers() {}

    private static final long DAY = 24 * 60 * 60_000L;

    public static void record(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        long now = System.currentTimeMillis();
        if (pr.leaves == null) pr.leaves = new java.util.ArrayList<>();
        pr.leaves.removeIf(t -> now - t > DAY);
        pr.leaves.add(now);
        AotRpg.PROFILES.save(p.getUuid());
        int n = pr.leaves.size();
        if (n < 2) return;
        String msg = n == 2 ? "That's twice today you've left a match early. Your squad was counting on you."
            : n == 3 ? "You keep leaving matches. Squads remember who runs." : "Warning: you've abandoned " + n + " matches today. This is being noted.";
        Titles.show(p, Text.literal("LEAVER WARNING").formatted(Formatting.GOLD, Formatting.BOLD), Text.literal(msg).formatted(Formatting.YELLOW), 5, 80, 20);
        p.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.MASTER, 1f, 0.5f);
        AotRpg.LOG.info("[leavers] {} has left {} matches in the last day", p.getName().getString(), n);
    }
}
