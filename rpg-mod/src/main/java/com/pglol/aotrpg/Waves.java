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

    private record Bar(ServerBossBar bar, int[] max) { }

    private final Map<UUID, Bar> bars = new HashMap<>();

    public void tick(ServerPlayerEntity p, int ticks) {
        if (ticks % 20 != 0) return;
        Box box = p.getBoundingBox().expand(RANGE, 96, RANGE);
        int n = p.getServerWorld().getEntitiesByClass(LivingEntity.class, box,
            e -> e.isAlive() && e.getCommandTags().contains(TAG)).size();
        Bar b = bars.get(p.getUuid());
        if (n == 0) {
            if (b != null) {
                b.bar().removePlayer(p);
                bars.remove(p.getUuid());
                p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 50, 20));
                p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("The titans near you have fallen").formatted(Formatting.GRAY)));
                p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("Wave cleared").formatted(Formatting.GOLD, Formatting.BOLD)));
                AotRpg.TASKS.count(p, Tasks.WAVES, 1);
                p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.7f, 1f);
            }
            return;
        }
        if (b == null) {
            ServerBossBar bar = new ServerBossBar(Text.empty(), BossBar.Color.RED, BossBar.Style.NOTCHED_10);
            bar.addPlayer(p);
            b = new Bar(bar, new int[] {n});
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
