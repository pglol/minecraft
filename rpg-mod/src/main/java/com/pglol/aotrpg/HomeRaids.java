package com.pglol.aotrpg;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Homes are safe ground: wandering titans and waves never come onto a property's land. But once
 * the owner has played on seven different days since the last one, and at least a week has
 * passed, titans raid the home while the owner is there: a small pack that must be fought off.
 * About once every week or two for someone who plays daily.
 */
public final class HomeRaids {
    public static final String TAG = "aot_home_raid";
    /** Titans are kept this far out around a property's land. */
    public static final int GUARD = 48;
    private static final long DAY = 86_400_000L, WEEK = 7 * DAY, RAID_MS = 10 * 60_000L;

    /** A raid on a plot (key = plot index) or a town house (key = -1 - instance); bandits raid homes where titans can't reach. */
    private record Raid(int plot, UUID owner, long until, List<UUID> titans, ServerWorld world, boolean bandits) { }

    private final Map<Integer, Raid> active = new HashMap<>();

    static long today() {
        return System.currentTimeMillis() / DAY;
    }

    /** Is (x, z) inside a guarded home area with no raid going on there? */
    public boolean guarded(double x, double z) {
        Homes.Data d = AotRpg.HOMES.data;
        if (d.plots.isEmpty()) return false;
        var plots = AotRpg.PLACES.plots;
        for (int idx : d.plots.keySet()) {
            if (idx >= plots.size() || active.containsKey(idx)) continue;
            Places.PlotInfo p = plots.get(idx);
            int m = HomePlots.LAND + GUARD;
            if (x >= p.x0() - m && x <= p.x1() + m && z >= p.z0() - m && z <= p.z1() + m) return true;
        }
        return false;
    }

    /** Once a minute. */
    public void tick(MinecraftServer server, int ticks) {
        if (ticks % 1200 != 600) return;
        long now = System.currentTimeMillis();
        Homes.Data d = AotRpg.HOMES.data;
        boolean dirty = false;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            String stem = AotRpg.HOMES.stem(p);
            for (var e : d.plots.entrySet()) {
                Homes.PlotDeed deed = e.getValue();
                if (!stem.equals(deed.stem)) continue;
                // A new day played counts towards the next raid.
                if (deed.lastDay != today()) {
                    deed.lastDay = today();
                    deed.daysPlayed++;
                    if (deed.lastRaid == 0) deed.lastRaid = now; // the first week is always quiet
                    dirty = true;
                }
                if (active.containsKey(e.getKey()) || deed.daysPlayed < 7 || now - deed.lastRaid < WEEK) continue;
                if (p.getWorld() != server.getOverworld() || e.getKey() >= AotRpg.PLACES.plots.size()) continue;
                Places.PlotInfo plot = AotRpg.PLACES.plots.get(e.getKey());
                double cx = (plot.x0() + plot.x1()) / 2.0, cz = (plot.z0() + plot.z1()) / 2.0;
                boolean home = Math.abs(p.getX() - cx) < 60 && Math.abs(p.getZ() - cz) < 60;
                // Spread out over the days after it is due: a 1 in 8 chance each minute at home.
                if (home && !AotRpg.CROWD.afk(p) && p.getRandom().nextInt(8) == 0) {
                    start(server.getOverworld(), p, e.getKey(), plot);
                    dirty = true;
                }
            }
        }
        // Town houses (in the home world): bandits break in now and then while you're home.
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            Homes.Deed deed = AotRpg.HOMES.deedHere(p);
            if (deed == null) continue;
            int key = -1 - deed.instance;
            if (deed.lastDay != today()) {
                deed.lastDay = today();
                deed.daysPlayed++;
                if (deed.lastRaid == 0) deed.lastRaid = now;
                dirty = true;
            }
            if (active.containsKey(key) || deed.daysPlayed < 7 || now - deed.lastRaid < WEEK || AotRpg.CROWD.afk(p) || p.getRandom().nextInt(8) != 0) continue;
            startBandits(p.getServerWorld(), p, key, p.getBlockPos(), 1, false);
            dirty = true;
        }
        // Raids end when their titans are dead, or after ten minutes.
        for (var it = active.entrySet().iterator(); it.hasNext(); ) {
            Raid r = it.next().getValue();
            ServerWorld w = r.world();
            boolean alive = false;
            for (UUID id : r.titans()) {
                Entity t = w.getEntity(id);
                if (t != null && t.isAlive()) alive = true;
            }
            if (alive && now < r.until()) continue;
            it.remove();
            Homes.PlotDeed deed = r.plot() >= 0 ? d.plots.get(r.plot()) : null;
            if (deed != null) {
                deed.lastRaid = now;
                deed.daysPlayed = 0;
                dirty = true;
            }
            if (r.plot() < 0) {
                Homes.Deed hd = AotRpg.HOMES.deedByInstance(-1 - r.plot());
                if (hd != null) {
                    hd.lastRaid = now;
                    hd.daysPlayed = 0;
                    dirty = true;
                }
            }
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(r.owner());
            if (!alive) {
                if (owner != null) {
                    Titles.show(owner, Text.literal("HOME DEFENDED").formatted(Formatting.GOLD, Formatting.BOLD),
                        Text.literal(r.bandits() ? "The bandits have fled" : "The titans are gone").formatted(Formatting.GRAY), 10, 50, 20);
                    AotRpg.WALLET.earn(owner, 150, "for defending your home");
                    AotRpg.SEASON.xp(owner, 200);
                    AotRpg.TASKS.count(owner, Tasks.DEFEND, 1);
                }
            } else {
                for (UUID id : r.titans()) {
                    Entity t = w.getEntity(id);
                    if (t != null) t.discard();
                }
                if (owner != null) owner.sendMessage(Text.literal(r.bandits() ? "The bandits slink away." : "The titans wander off from your home.")
                    .formatted(Formatting.GRAY), false);
            }
        }
        if (dirty) AotRpg.HOMES.save();
    }

    /** Ordinary titans of the AoT mod (not shifters, and not their body parts). */
    private static List<EntityType<?>> raiders() {
        return TitanTypes.ordinary();
    }

    /** Inside the walls or underground, titans can't come: bandits raid those homes instead. */
    private static boolean sheltered(MinecraftServer server, Places.PlotInfo plot) {
        double cx = (plot.x0() + plot.x1()) / 2.0, cz = (plot.z0() + plot.z1()) / 2.0;
        return AotRpg.GUARD.safeAt(server, cx, cz) || plot.y() < 45 || plot.region().toLowerCase(java.util.Locale.ROOT).contains("underground");
    }

    private void start(ServerWorld w, ServerPlayerEntity owner, int idx, Places.PlotInfo plot) {
        if (sheltered(w.getServer(), plot)) {
            BlockPos c = new BlockPos((plot.x0() + plot.x1()) / 2, plot.y(), (plot.z0() + plot.z1()) / 2);
            startBandits(w, owner, idx, c, Math.max(4, (plot.x1() - plot.x0()) / 2 + HomePlots.LAND + 6), true);
            return;
        }
        List<EntityType<?>> kinds = raiders();
        if (kinds.isEmpty()) return;
        // Walls thin the raid out: one titan fewer per wall tier.
        int fort = Estate.fortOf(idx);
        int n = Math.max(1, 3 + owner.getRandom().nextInt(3) - fort);
        double cx = (plot.x0() + plot.x1()) / 2.0, cz = (plot.z0() + plot.z1()) / 2.0;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = owner.getRandom().nextDouble() * Math.PI * 2;
            int x = (int) (cx + Math.cos(a) * 70), z = (int) (cz + Math.sin(a) * 70);
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            Entity t = kinds.get(owner.getRandom().nextInt(kinds.size())).create(w);
            if (t == null) continue;
            t.refreshPositionAndAngles(x + 0.5, y, z + 0.5, owner.getRandom().nextFloat() * 360, 0);
            if (t instanceof MobEntity mob) {
                mob.initialize(w, w.getLocalDifficulty(new BlockPos(x, y, z)), SpawnReason.EVENT, null);
                mob.setTarget(owner);
                mob.setPersistent();
            }
            t.addCommandTag(TAG);
            t.addCommandTag("aot_titan");
            if (fort >= 1 && t instanceof LivingEntity le) {
                le.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.SLOWNESS, 20 * 45, fort - 1));
                if (fort >= 2) le.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.WEAKNESS, 20 * 90, 0));
            }
            if (w.spawnEntity(t)) ids.add(t.getUuid());
        }
        if (ids.isEmpty()) return;
        active.put(idx, new Raid(idx, owner.getUuid(), System.currentTimeMillis() + RAID_MS, ids, w, false));
        Titles.show(owner, Text.literal("TITANS AT YOUR HOME").formatted(Formatting.RED, Formatting.BOLD),
            Text.literal(ids.size() + " titans are coming. Defend your property!").formatted(Formatting.GOLD), 10, 60, 20);
        owner.playSoundToPlayer(SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.HOSTILE, 1f, 0.8f);
        if (fort >= 1) {
            Notify.toast(owner, Text.literal(fort >= 3 ? "The watchtowers saw them coming" : "Your walls slow them").formatted(Formatting.GOLD),
                Text.literal(fort >= 2 ? "Fewer titans, slowed and weakened at the wall" : "Fewer titans, slowed at the palisade"), 0xE0B96A, "minecraft:stone_bricks", null);
        }
    }

    /**
     * Bandits: a gang of thieves (crossbows and axes) that comes for your home, from the streets
     * around a town property, down the tunnels to an underground one, or into your house's yard.
     */
    private void startBandits(ServerWorld w, ServerPlayerEntity owner, int key, BlockPos center, int dist, boolean plot) {
        int fort = key >= 0 ? Estate.fortOf(key) : 0;
        int n = Math.max(2, 3 + owner.getRandom().nextInt(3) - fort);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BlockPos at = null;
            for (int tries = 0; tries < 12 && at == null; tries++) {
                double a = owner.getRandom().nextDouble() * Math.PI * 2, d = dist + owner.getRandom().nextInt(8);
                int x = (int) (center.getX() + Math.cos(a) * d), z = (int) (center.getZ() + Math.sin(a) * d);
                for (int dy = 3; dy >= -3; dy--) {
                    BlockPos pos = new BlockPos(x, center.getY() + dy, z);
                    if (w.getBlockState(pos).isAir() && w.getBlockState(pos.up()).isAir() && w.getBlockState(pos.down()).isSolidBlock(w, pos.down())) {
                        at = pos;
                        break;
                    }
                }
            }
            if (at == null) at = owner.getBlockPos().add(owner.getRandom().nextInt(9) - 4, 0, owner.getRandom().nextInt(9) - 4);
            MobEntity m = (owner.getRandom().nextBoolean() ? EntityType.PILLAGER : EntityType.VINDICATOR).create(w);
            if (m == null) continue;
            m.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, owner.getRandom().nextFloat() * 360, 0);
            m.initialize(w, w.getLocalDifficulty(at), SpawnReason.EVENT, null);
            m.setCustomName(Text.literal(owner.getRandom().nextBoolean() ? "Bandit" : "Thief").formatted(Formatting.DARK_RED));
            m.setPersistent();
            m.setTarget(owner);
            m.addCommandTag(TAG);
            if (fort >= 1) m.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.SLOWNESS, 20 * 30, 0));
            if (w.spawnEntity(m)) ids.add(m.getUuid());
        }
        if (ids.isEmpty()) return;
        active.put(key, new Raid(key, owner.getUuid(), System.currentTimeMillis() + RAID_MS, ids, w, true));
        Titles.show(owner, Text.literal("BANDITS AT YOUR HOME").formatted(Formatting.RED, Formatting.BOLD),
            Text.literal(ids.size() + " thieves are breaking in. Drive them off!").formatted(Formatting.GOLD), 10, 60, 20);
        owner.playSoundToPlayer(SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.HOSTILE, 0.8f, 1.2f);
    }

    /** /aotrpg raid: starts a raid on the player's own plot now (for testing). */
    public boolean forceStart(ServerPlayerEntity p) {
        String stem = AotRpg.HOMES.stem(p);
        for (var e : AotRpg.HOMES.data.plots.entrySet()) {
            if (!stem.equals(e.getValue().stem) || e.getKey() >= AotRpg.PLACES.plots.size() || active.containsKey(e.getKey())) continue;
            start(p.getServer().getOverworld(), p, e.getKey(), AotRpg.PLACES.plots.get(e.getKey()));
            return true;
        }
        return false;
    }

    /** Raid titans are never cleared by the titan guard. */
    public static boolean raider(Entity e) {
        return e.getCommandTags().contains(TAG);
    }

    public static boolean isLiving(Entity e) {
        return e instanceof LivingEntity;
    }
}
