package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The main story, one objective at a time. Small for now; chapters are added here.
 * Profile.chapter is the current step, Profile.questBase a counter snapshot for kill goals.
 */
public final class Story {
    private record Step(String chapter, String text, String place, int radius, int kills, long xp) { }

    private static final Step[] STEPS = {
        null,
        new Step("Chapter 1: The Recruit", "Report to the Cadet Training Camp", "cadet-training-camp", 28, 0, 120),
        new Step("Chapter 1: The Recruit", "Slay 3 titans beyond the walls", null, 0, 3, 250),
        new Step("Chapter 2: Wings of Freedom", "Visit the Survey Corps HQ", "survey-corps-hq", 30, 0, 300),
        new Step("Chapter 2: Wings of Freedom", "More of the story is coming soon", null, 0, 0, 0),
    };

    private static Step step(Profile pr) {
        int c = Math.max(1, Math.min(STEPS.length - 1, pr.chapter));
        return STEPS[c];
    }

    public void tick(ServerPlayerEntity p, Profile pr, int ticks) {
        if (!pr.created || ticks % 20 != 0) return;
        if (pr.chapter <= 0) pr.chapter = 1;
        Step s = step(pr);
        boolean done = false;
        if (s.place != null) {
            int[] at = AotRpg.PLACES.get(s.place);
            if (at == null) done = true; // place missing from this world: skip
            else {
                double dx = p.getX() - at[0], dz = p.getZ() - at[2];
                done = dx * dx + dz * dz < s.radius * s.radius;
            }
        } else if (s.kills > 0) {
            done = pr.titanKills - pr.questBase >= s.kills;
        }
        if (done && pr.chapter < STEPS.length - 1) complete(p, pr, s);
        else if (ticks % 40 == 0) AotRpg.QUESTS.sendObjective(p);
    }

    private void complete(ServerPlayerEntity p, Profile pr, Step s) {
        pr.chapter++;
        pr.questBase = pr.titanKills;
        Titles.show(p, Text.literal("OBJECTIVE COMPLETE").formatted(Formatting.GOLD, Formatting.BOLD),
            Text.literal(s.text + "  ·  +" + s.xp + " XP").formatted(Formatting.YELLOW), 10, 60, 20);
        p.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.8f, 1f);
        Step next = step(pr);
        p.sendMessage(Text.literal("▶ " + next.chapter + ": ").formatted(Formatting.GOLD, Formatting.BOLD)
            .append(Text.literal(next.text).formatted(Formatting.WHITE)));
        if (s.xp > 0) AotRpg.PROGRESSION.addXp(p, pr, s.xp);
        AotRpg.PROFILES.save(p.getUuid());
        send(p, pr);
    }

    public record View(String chapter, String text, String progress, boolean hasTarget, int x, int y, int z, long xp) { }

    public View view(Profile pr) {
        Step s = step(pr);
        String progress = s.kills > 0 ? Math.min(s.kills, pr.titanKills - pr.questBase) + " / " + s.kills : "";
        int[] at = s.place == null ? null : AotRpg.PLACES.get(s.place);
        return new View(s.chapter, s.text, progress, at != null, at == null ? 0 : at[0], at == null ? 0 : at[1], at == null ? 0 : at[2], s.xp);
    }

    public void send(ServerPlayerEntity p, Profile pr) {
        if (!pr.created) return;
        AotRpg.QUESTS.sendObjective(p);
        AotRpg.QUESTS.send(p);
        AotRpg.QUESTS.markers(p, true);
    }
}
