package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

/**
 * Ferries: quick travel between the big places. A Ferryman stands at every district, village and
 * port, Mitras, the Underground City, and across the sea at Liberio and the Marleyan port. He takes
 * you to stations you've already found on foot, and only where you're strong enough to go (your
 * level must reach the place's). Fares grow with distance, cost more inside Wall Sina, and a sea
 * crossing is dear; Charisma haggles them down. He can also take you home. Camps, caves and
 * landmarks along the way have no ferry: you travel to those yourself.
 */
public final class Ferries {
    public static final String NPC = "aot_ferryman";
    private MinecraftServer server;

    public void open(MinecraftServer server) {
        this.server = server;
    }

    public static boolean ferryman(Entity e) {
        return e.getCommandTags().contains(NPC);
    }

    /** A place with a ferry station. */
    public static boolean station(Net.Area a) {
        return a.look().equals("town") || a.name().equals("Underground City")
            || a.look().equals("marley") && a.sub().equals("Marley");
    }

    private static boolean overseas(Net.Area a) {
        return a.look().equals("marley");
    }

    private static boolean interior(Net.Area a) {
        return a.sub().equals("Wall Sina") || a.name().equals("Mitras") || a.name().equals("Underground City");
    }

    private static boolean underground(Net.Area a) {
        return a.name().equals("Underground City");
    }

    /** The station spot for a place: beside its centre, on real ground (underground ones stay below). */
    private static BlockPos spot(ServerWorld w, Net.Area a) {
        return Safe.landing(w, a.x() - 3, a.y(), a.z() - 3);
    }

    private Net.Area stationNear(ServerPlayerEntity p, double r) {
        Net.Area best = null;
        double bd = r * r;
        for (Net.Area a : AotRpg.PLACES.areas()) {
            if (!station(a)) continue;
            if (underground(a) && Math.abs(p.getY() - a.y()) > 30) continue;
            if (!underground(a) && p.getY() < 40) continue;
            double d = (a.x() - p.getX()) * (a.x() - p.getX()) + (a.z() - p.getZ()) * (a.z() - p.getZ());
            if (d < bd) {
                bd = d;
                best = a;
            }
        }
        return best;
    }

    private static long fare(ServerPlayerEntity p, Net.Area from, Net.Area to) {
        double dist = Math.hypot(to.x() - from.x(), to.z() - from.z());
        double f = 10 + dist / 25;
        if (interior(to) || interior(from)) f *= 1.4;
        if (overseas(to) != overseas(from)) f += 300;
        f *= 1 - Math.min(0.3, 0.02 * AotRpg.PROFILES.get(p.getUuid()).total(Stat.CHARISMA));
        return Math.max(5, Math.round(f));
    }

    private static long homeFare(ServerPlayerEntity p, Net.Area from) {
        return Math.round(25 * (1 - Math.min(0.3, 0.02 * AotRpg.PROFILES.get(p.getUuid()).total(Stat.CHARISMA))));
    }

    /** Why this player can't sail there ("" if they can). */
    private static String locked(Profile pr, Net.Area a) {
        if (!pr.discovered.contains(a.id())) return "Undiscovered: find it on foot first";
        if (pr.level < a.min()) return "Requires level " + a.min();
        return "";
    }

    // ------------------------------------------------------------------ the ferrymen and discovery

    public void tick(int ticks) {
        if (server == null) return;
        ServerWorld w = server.getOverworld();
        if (ticks % 40 == 0) {
            for (ServerPlayerEntity p : w.getPlayers()) discover(p);
        }
        if (ticks % 600 == 321) place(w);
    }

    /** Stations found on foot: being at a town, port or city marks it for the ferry. */
    private void discover(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created) return;
        Net.Area a = stationNear(p, 90);
        if (a == null || !pr.discovered.add(a.id())) return;
        AotRpg.PROFILES.save(p.getUuid());
        Notify.toast(p, Text.literal("Ferry station found").formatted(Formatting.AQUA),
            Text.literal(a.name() + " · the Ferryman can now take you here"), 0x4AA8C0, "minecraft:oak_boat", "ferry");
    }

    /** Every 30 s: a Ferryman stands at each station near a player. */
    private void place(ServerWorld w) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            for (Net.Area a : AotRpg.PLACES.areas()) {
                if (!station(a) || Math.hypot(a.x() - p.getX(), a.z() - p.getZ()) > 96) continue;
                if (!w.isChunkLoaded((a.x() - 3) >> 4, (a.z() - 3) >> 4)) continue;
                int ylo = underground(a) ? a.y() - 40 : 40, yhi = underground(a) ? a.y() + 40 : 400;
                Box box = new Box(a.x() - 48, ylo, a.z() - 48, a.x() + 48, yhi, a.z() + 48);
                if (!w.getEntitiesByClass(VillagerEntity.class, box, Ferries::ferryman).isEmpty()) continue;
                BlockPos at = spot(w, a);
                VillagerEntity v = EntityType.VILLAGER.create(w);
                if (v == null) continue;
                v.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                v.setAiDisabled(true);
                v.setInvulnerable(true);
                v.setPersistent();
                v.setSilent(true);
                v.setCustomName(Text.literal("Ferryman").formatted(Formatting.AQUA, Formatting.BOLD));
                v.setCustomNameVisible(true);
                v.addCommandTag(NPC);
                w.spawnEntity(v);
            }
        }
    }

    /** Found ferry stations on the map. */
    public void markers(ServerPlayerEntity p, List<Net.Marker> list) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        for (Net.Area a : AotRpg.PLACES.areas()) {
            if (station(a) && pr.discovered.contains(a.id())) list.add(new Net.Marker("ferry", "Ferry · " + a.name(), a.x() - 3, a.y(), a.z() - 3, 0x4AA8C0));
        }
    }

    // ------------------------------------------------------------------ the screen

    public void talk(ServerPlayerEntity p) {
        send(p, true);
    }

    public void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.FerryView.ID)) {
            p.sendMessage(Text.literal("The Ferryman needs the AoT RPG client mod.").formatted(Formatting.GRAY), true);
            return;
        }
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        Net.Area here = stationNear(p, 90);
        if (here == null) return;
        pr.discovered.add(here.id());
        List<Net.FerryStop> stops = new ArrayList<>();
        for (Net.Area a : AotRpg.PLACES.areas()) {
            if (!station(a) || a.id().equals(here.id())) continue;
            stops.add(new Net.FerryStop(a.id(), a.name(), a.sub(), a.min(), a.max(), fare(p, here, a), locked(pr, a),
                (int) Math.round(Math.hypot(a.x() - here.x(), a.z() - here.z())), overseas(a) != overseas(here)));
        }
        stops.sort((x, y) -> {
            int lx = x.locked().isEmpty() ? 0 : 1, ly = y.locked().isEmpty() ? 0 : 1;
            return lx != ly ? Integer.compare(lx, ly) : Integer.compare(x.distance(), y.distance());
        });
        boolean hasHome = Estate.ownPlot(p) >= 0 || !AotRpg.HOMES.deeds(p).isEmpty();
        ServerPlayNetworking.send(p, new Net.FerryView(here.name(), stops, hasHome ? homeFare(p, here) : -1, pr.marks, open));
    }

    /** Sail to a station (or "home"). */
    public void go(ServerPlayerEntity p, String id) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        Net.Area here = stationNear(p, 24);
        if (!pr.created || here == null) {
            p.sendMessage(Text.literal("Speak to a Ferryman at a station").formatted(Formatting.GRAY), true);
            return;
        }
        String why = AotRpg.DOWNED.isDowned(p) ? "You're down" : Classes.inCombat(p) ? "Not in the middle of a fight"
            : AotRpg.RAID_BOSSES.inRaid(p.getUuid()) ? "Not during a raid" : null;
        if (why != null) {
            Notify.toast(p, Text.literal("The ferry won't leave now").formatted(Formatting.RED), Text.literal(why), 0xC0463A, null, "ferry");
            return;
        }
        ServerWorld ow = server.getOverworld();
        if (id.equals("home")) {
            long cost = homeFare(p, here);
            if (!AotRpg.WALLET.spendMarks(p, cost)) {
                Notify.toast(p, Text.literal("The fare home is " + cost + " Marks").formatted(Formatting.RED), null, 0xC0463A, null, "ferry");
                return;
            }
            board(p, "Home", () -> {
                if (!AotRpg.RECOVERY.home(p, ow)) AotRpg.WALLET.earn(p, cost, "an unused fare");
            });
            return;
        }
        Net.Area to = null;
        for (Net.Area a : AotRpg.PLACES.areas()) if (a.id().equals(id) && station(a)) to = a;
        if (to == null || to.id().equals(here.id())) return;
        String lock = locked(pr, to);
        if (!lock.isEmpty()) {
            Notify.toast(p, Text.literal("Can't sail to " + to.name()).formatted(Formatting.RED), Text.literal(lock), 0xC0463A, null, "ferry");
            return;
        }
        long cost = fare(p, here, to);
        if (!AotRpg.WALLET.spendMarks(p, cost)) {
            Notify.toast(p, Text.literal("The fare is " + cost + " Marks").formatted(Formatting.RED), null, 0xC0463A, null, "ferry");
            return;
        }
        Net.Area dest = to;
        board(p, dest.name(), () -> {
            BlockPos at = spot(ow, dest);
            p.teleport(ow, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, p.getYaw(), 0);
            ow.playSound(null, at, SoundEvents.ENTITY_BOAT_PADDLE_WATER, SoundCategory.PLAYERS, 1f, 0.9f);
            Notify.toast(p, Text.literal("Arrived at " + dest.name()).formatted(Formatting.AQUA, Formatting.BOLD),
                Text.literal("-" + cost + " Marks"), 0x4AA8C0, "minecraft:oak_boat", "ferry");
        });
    }

    /** A short fade while the ferry crosses, then the landing. */
    private void board(ServerPlayerEntity p, String where, Runnable arrive) {
        p.stopRiding();
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 50, 0, false, false, false));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 5, false, false, false));
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BOAT_PADDLE_WATER, SoundCategory.PLAYERS, 1f, 1f);
        p.sendMessage(Text.literal("⛵ Sailing to " + where + "…").formatted(Formatting.AQUA), true);
        AotRpg.SCHEDULER.later(30, () -> {
            if (p.isRemoved() || !p.isAlive()) return;
            arrive.run();
        });
    }
}
