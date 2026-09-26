package com.pglol.aotrpg;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Titan waves have an end: titans from a wave or horde carry the aot_wave_live tag (set by the
 * titan datapack), and a boss bar counts the ones near you down until the wave is cleared.
 */
public final class Waves {
    public static final String TAG = "aot_wave_live";
    private static final double RANGE = 160;

    /** A wave as one player sees it: the titans that were in it, and how many they (or their party) felled. */
    private record Bar(ServerBossBar bar, int[] max, java.util.Set<UUID> seen, int[] felled) { }

    private final Map<UUID, Bar> bars = new HashMap<>();
    /** Wave titans that died (not wandered off or were left behind). */
    private final java.util.Set<UUID> fallen = new java.util.HashSet<>();

    /** A wave titan died: it counts for everyone whose wave it was, and as a kill for its slayer and their party. */
    public void onKill(ServerPlayerEntity killer, LivingEntity dead) {
        if (!dead.getCommandTags().contains(TAG)) return;
        fallen.add(dead.getUuid());
        if (fallen.size() > 5000) fallen.clear();
        for (var e : bars.entrySet()) {
            if (!e.getValue().seen().contains(dead.getUuid())) continue;
            if (killer != null && (e.getKey().equals(killer.getUuid()) || AotRpg.PARTIES.same(killer.getUuid(), e.getKey()))) {
                e.getValue().felled()[0]++;
            }
        }
    }

    public void tick(ServerPlayerEntity p, int ticks) {
        if (ticks % 20 != 0) return;
        Box box = p.getBoundingBox().expand(RANGE, 96, RANGE);
        var live = p.getServerWorld().getEntitiesByClass(LivingEntity.class, box,
            e -> e.isAlive() && e.getCommandTags().contains(TAG));
        int n = live.size();
        Bar b = bars.get(p.getUuid());
        if (b != null) for (LivingEntity e : live) b.seen().add(e.getUuid());
        if (n == 0) {
            if (b != null) {
                b.bar().removePlayer(p);
                bars.remove(p.getUuid());
                // Cleared only if every titan of the wave is dead and you fought: running off or
                // leaving them behind just ends the wave quietly, with nothing earned.
                boolean allDead = !b.seen().isEmpty() && fallen.containsAll(b.seen());
                if (!allDead || b.felled()[0] <= 0) return;
                p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 50, 20));
                p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("The titans near you have fallen").formatted(Formatting.GRAY)));
                p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("Wave cleared").formatted(Formatting.GOLD, Formatting.BOLD)));
                AotRpg.TASKS.count(p, Tasks.WAVES, 1);
                AotRpg.REGIMENTS.gain(p, 30);
                p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.7f, 1f);
            }
            return;
        }
        if (b == null) {
            ServerBossBar bar = new ServerBossBar(Text.empty(), BossBar.Color.RED, BossBar.Style.NOTCHED_10);
            bar.addPlayer(p);
            b = new Bar(bar, new int[] {n}, new java.util.HashSet<>(), new int[] {0});
            for (LivingEntity e : live) b.seen().add(e.getUuid());
            bars.put(p.getUuid(), b);
        }
        b.max()[0] = Math.max(b.max()[0], n);
        b.bar().setName(Text.literal("Titan wave · ").formatted(Formatting.DARK_RED, Formatting.BOLD)
            .append(Text.literal(n + (n == 1 ? " titan" : " titans") + " remaining").formatted(Formatting.RED)));
        b.bar().setPercent(Math.max(0.02f, n / (float) b.max()[0]));
    }

    public void forget(ServerPlayerEntity p) {
        Bar b = bars.remove(p.getUuid());
        if (b != null) b.bar().removePlayer(p);
    }
}
