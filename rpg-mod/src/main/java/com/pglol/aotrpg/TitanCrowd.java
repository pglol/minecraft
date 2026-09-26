package com.pglol.aotrpg;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps titan numbers sane around players. The world's titan pack sends waves on a timer; here a
 * new wave waits until the last one near you is dealt with, nothing new comes while you are AFK,
 * and titans crowding a player beyond a fair number (or idling around an AFK player) fade away,
 * farthest first.
 */
public final class TitanCrowd {
    /** Most wandering titans kept within CROWD_R of an active player (a live wave may add its own). */
    private static final int MAX_NEAR = 8, MAX_NEAR_AFK = 2, CROWD_R = 96;
    private static final long AFK_MS = 2 * 60_000L;

    private final Map<UUID, double[]> last = new HashMap<>();
    private final Map<UUID, Long> activeAt = new HashMap<>();

    public boolean afk(ServerPlayerEntity p) {
        return System.currentTimeMillis() - activeAt.getOrDefault(p.getUuid(), System.currentTimeMillis()) > AFK_MS;
    }

    public void tick(MinecraftServer server, int ticks) {
        if (ticks % 20 != 13) return;
        long now = System.currentTimeMillis();
        ServerWorld w = server.getOverworld();
        ScoreboardObjective waves = server.getScoreboard().getNullableObjective("aot_wave");
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            // Activity: moving or looking around.
            double[] was = last.get(p.getUuid());
            double[] is = {p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch()};
            boolean moved = was == null || Math.abs(was[0] - is[0]) + Math.abs(was[1] - is[1]) + Math.abs(was[2] - is[2]) > 0.3
                || Math.abs(was[3] - is[3]) + Math.abs(was[4] - is[4]) > 2;
            last.put(p.getUuid(), is);
            if (moved || !activeAt.containsKey(p.getUuid())) activeAt.put(p.getUuid(), now);
            if (p.getWorld() != w || p.isSpectator()) continue;
            boolean afk = afk(p);

            List<Entity> near = new ArrayList<>();
            int liveWave = 0;
            for (Entity e : w.getOtherEntities(p, new Box(p.getBlockPos()).expand(CROWD_R + 64, 128, CROWD_R + 64), e -> AotRpg.isTitan(e))) {
                if (e.getCommandTags().contains(Waves.TAG)) liveWave++;
                if (e.squaredDistanceTo(p) > CROWD_R * CROWD_R) continue;
                if (TitanGuard.isShifter(e) || HomeRaids.raider(e) || FactionWar.eventTitan(e) || Raids.raidMob(e) || e.hasPassengers() || e.getControllingPassenger() instanceof PlayerEntity) continue;
                near.add(e);
            }
            // No new wave while one is still around you, or while you are AFK.
            if (waves != null && (afk || liveWave > 0)) server.getScoreboard().getOrCreateScore(ScoreHolder.fromName(p.getNameForScoreboard()), waves).setScore(0);

            int keep = afk ? MAX_NEAR_AFK : MAX_NEAR + Math.min(liveWave, 8);
            if (near.size() <= keep) continue;
            // Titans that are hurt (in a fight) stay; the idle ones farthest away go first.
            near.sort(Comparator.comparingDouble((Entity e) -> -e.squaredDistanceTo(p)));
            int extra = near.size() - keep;
            for (Entity e : near) {
                if (extra <= 0) break;
                if (e instanceof net.minecraft.entity.LivingEntity le && le.getHealth() < le.getMaxHealth() && !afk) continue;
                w.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, e.getX(), e.getY() + e.getHeight() / 2, e.getZ(), 12,
                    e.getWidth() / 2, e.getHeight() / 3, e.getWidth() / 2, 0.02);
                e.discard();
                extra--;
            }
        }
    }

    public void forget(UUID id) {
        last.remove(id);
        activeAt.remove(id);
    }
}
