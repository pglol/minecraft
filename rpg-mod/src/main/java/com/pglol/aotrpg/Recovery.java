package com.pglol.aotrpg;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where you wake after falling: at the recovery post (every town and Survey Corps camp) nearest
 * to where you fell, so you're never sent all the way back, or at home if you chose that in your
 * Estate (your property, or your town house).
 */
public final class Recovery {
    private final Map<UUID, Vec3d> fellAt = new HashMap<>();

    public void onDeath(ServerPlayerEntity p) {
        if (p.getWorld() == p.getServer().getOverworld()) fellAt.put(p.getUuid(), p.getPos());
    }

    public static boolean post(Net.Area a) {
        return a.look().equals("town") || a.look().equals("camp");
    }

    public void respawn(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        Vec3d at = fellAt.remove(p.getUuid());
        if (!pr.created) return;
        ServerWorld ow = p.getServer().getOverworld();
        // Fell in a boss raid: back to the Raid Commander you left from.
        Vec3d raid = AotRpg.RAID_BOSSES.takeReturn(p.getUuid());
        if (raid != null) {
            p.teleport(ow, raid.x, raid.y, raid.z, p.getYaw(), 0);
            Notify.toast(p, Text.literal("Carried back from the raid").formatted(Formatting.GOLD), null, 0xE0B96A, "minecraft:red_bed", null);
            return;
        }
        if ("home".equals(pr.respawn) && home(p, ow)) return;
        if (at == null) return;
        Net.Area best = null;
        double bd = Double.MAX_VALUE;
        for (Net.Area a : AotRpg.PLACES.areas()) {
            if (!post(a)) continue;
            double d = (a.x() - at.x) * (a.x() - at.x) + (a.z() - at.z) * (a.z() - at.z);
            if (d < bd) {
                bd = d;
                best = a;
            }
        }
        if (best == null) return;
        int x = best.x() + 2, z = best.z() + 2;
        net.minecraft.util.math.BlockPos land = Safe.landing(ow, x, best.y(), z);
        p.teleport(ow, land.getX() + 0.5, land.getY(), land.getZ() + 0.5, p.getYaw(), 0);
        Notify.toast(p, Text.literal("Recovered at " + best.name()).formatted(Formatting.GOLD),
            Text.literal("The nearest recovery post · set home as your respawn in Estate"), 0xE0B96A, "minecraft:red_bed", null);
    }

    /** Your property's gate, or your town house. */
    private boolean home(ServerPlayerEntity p, ServerWorld ow) {
        int idx = Estate.ownPlot(p);
        if (idx >= 0) {
            Places.PlotInfo pl = AotRpg.PLACES.plots.get(idx);
            int mx = (pl.x0() + pl.x1()) / 2, mz = (pl.z0() + pl.z1()) / 2;
            int x = mx, z = mz;
            switch (pl.gate()) {
                case "north" -> z = pl.z0() - 1;
                case "west" -> x = pl.x0() - 1;
                case "east" -> x = pl.x1() + 1;
                default -> z = pl.z1() + 1;
            }
            // Underground plots keep their own height (not the surface above the Underground City).
            net.minecraft.util.math.BlockPos land = Safe.landing(ow, x, pl.y(), z);
            p.teleport(ow, land.getX() + 0.5, land.getY(), land.getZ() + 0.5, p.getYaw(), 0);
            Notify.toast(p, Text.literal("You wake at home").formatted(Formatting.GOLD), null, 0xE0B96A, "minecraft:red_bed", null);
            return true;
        }
        List<Homes.Deed> deeds = AotRpg.HOMES.deeds(p);
        if (!deeds.isEmpty()) {
            AotRpg.HOMES.enter(p, deeds.get(0));
            return true;
        }
        return false;
    }
}
