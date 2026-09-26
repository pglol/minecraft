package com.pglol.aotrpg;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps wandering titans out of the safe areas (inside Wall Rose, the districts, Mitras and the
 * story sites) so towns and new players are not overrun. Off during a breach event, and never
 * touches the Nine Titans (shifters may be players).
 */
public final class TitanGuard {
    private static final String[] SHIFTERS = {"attack", "armored", "armoured", "colossal", "female", "beast", "cart",
        "jaw", "warhammer", "founding"};
    private int rose;
    private final List<int[]> zones = new ArrayList<>();

    public void set(int rose, List<int[]> zones) {
        this.rose = rose;
        this.zones.clear();
        this.zones.addAll(zones);
    }

    private static boolean breach(MinecraftServer server) {
        var sb = server.getScoreboard();
        ScoreboardObjective o = sb.getNullableObjective("aot_titans");
        if (o == null) return false;
        var score = sb.getScore(ScoreHolder.fromName("#breach"), o);
        return score != null && score.getScore() == 1;
    }

    /** Where titans aren't allowed to stay (inside Wall Rose and the safe zones, unless the walls are breached). */
    public boolean safeAt(MinecraftServer server, double x, double z) {
        return (rose != 0 || !zones.isEmpty()) && !breach(server) && protectedAt(x, z);
    }

    private boolean protectedAt(double x, double z) {
        if (rose > 0 && x * x + z * z < (double) rose * rose) return true;
        for (int[] c : zones) {
            double dx = x - c[0], dz = z - c[1];
            if (dx * dx + dz * dz < (double) c[2] * c[2]) return true;
        }
        return false;
    }

    /** One of the Nine: a shifter titan. */
    public static boolean isShifter(Entity e) {
        String path = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
        for (String s : SHIFTERS) if (path.contains(s)) return true;
        return false;
    }

    private static boolean wanderingTitan(Entity e) {
        if (!(e instanceof LivingEntity) || !AotRpg.isTitan(e) || HomeRaids.raider(e) || FactionWar.eventTitan(e) || Raids.raidMob(e) || TitanActivity.managed(e)) return false;
        String path = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
        for (String s : SHIFTERS) if (path.contains(s)) return false;
        for (Entity p : e.getPassengerList()) if (p instanceof PlayerEntity) return false;
        return !(e.getControllingPassenger() instanceof PlayerEntity);
    }

    public void tick(MinecraftServer server, int ticks) {
        if (ticks % 20 != 7) return;
        boolean walls = (rose != 0 || !zones.isEmpty()) && !breach(server);
        ServerWorld w = server.getOverworld();
        List<Entity> gone = new ArrayList<>();
        for (Entity e : w.iterateEntities()) {
            // Homes stay safe even during a breach (their raids are separate).
            if (wanderingTitan(e) && ((walls && protectedAt(e.getX(), e.getZ())) || AotRpg.RAIDS.guarded(e.getX(), e.getZ()))) gone.add(e);
        }
        for (Entity e : gone) {
            w.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, e.getX(), e.getY() + e.getHeight() / 2, e.getZ(), 20,
                e.getWidth() / 2, e.getHeight() / 3, e.getWidth() / 2, 0.02);
            e.discard();
        }
    }
}
