package com.pglol.aotrpg;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.DonkeyEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Escort work. A merchant in every town needs a wagon's worth of goods (a laden pack donkey)
 * brought safely to another town. Take the job and the donkey follows you; titans come at it on
 * the road, more the more dangerous the country. Deliver it and you're paid by what arrives:
 * every wound it took cost goods, and a flawless delivery earns a bonus on top. Longer and
 * deadlier roads pay more, 6,000 to 20,000 Marks.
 */
public final class Escorts {
    public static final String MERCHANT = "aot_escort", CARAVAN = "aot_caravan", AMBUSH = "aot_ambush";
    private static final String[] NAMES = {"Ruprecht", "Adelheid", "Konstantin", "Mechthild", "Leopold", "Walburga", "Siegfried", "Ottilie"};

    private static final class Job {
        UUID player, donkey;
        String from, to;
        int tx, tz;
        long pay;
        float maxHealth;
        boolean hurt;
        long nextAmbush;
    }

    private final Map<UUID, Job> jobs = new HashMap<>();
    /** Offers made in conversation: player -> (merchant town, destination). */
    private final Map<UUID, String[]> offered = new HashMap<>();
    private final Map<String, BlockPos> spots = new HashMap<>();
    private MinecraftServer server;

    public static boolean escort(Entity e) {
        return e.getCommandTags().contains(MERCHANT);
    }

    private List<Net.Area> towns() {
        List<Net.Area> out = new ArrayList<>();
        for (Net.Area a : AotRpg.PLACES.areas()) if (a.look().equals("town")) out.add(a);
        return out;
    }

    // ------------------------------------------------------------------ the merchants

    private static UUID id(Net.Area t) {
        return UUID.nameUUIDFromBytes(("aot_escort:" + t.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void tick(MinecraftServer server, int ticks) {
        this.server = server;
        ServerWorld w = server.getOverworld();
        if (ticks % 40 == 31) placeMerchants(w);
        if (ticks % 20 == 3) runJobs(w, ticks);
    }

    private void placeMerchants(ServerWorld w) {
        List<ServerPlayerEntity> players = new ArrayList<>(w.getPlayers());
        for (Net.Area t : towns()) {
            boolean near = false;
            for (ServerPlayerEntity p : players) if ((p.getX() - t.x()) * (p.getX() - t.x()) + (p.getZ() - t.z()) * (p.getZ() - t.z()) < 100 * 100) near = true;
            if (!near || !w.isChunkLoaded(t.x() >> 4, t.z() >> 4)) continue;
            Stalls.Spot spot = Stalls.place(w, "escort:" + t.id(), t, "MERCHANT", 10, 28, t.id().hashCode() * 31L + 5);
            if (spot == null) continue;
            BlockPos at = spot.pos();
            float yaw = spot.facing().asRotation();
            if (w.getEntity(id(t)) instanceof VillagerEntity v && v.isAlive()) {
                if (v.squaredDistanceTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5) > 0.5) v.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, 0);
                v.setHeadYaw(yaw);
                v.setBodyYaw(yaw);
                continue;
            }
            VillagerEntity v = EntityType.VILLAGER.create(w);
            if (v == null) continue;
            v.setUuid(id(t));
            v.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, 0);
            v.initialize(w, w.getLocalDifficulty(at), SpawnReason.EVENT, null);
            v.setVillagerData(v.getVillagerData().withProfession(VillagerProfession.NONE));
            v.setSilent(true);
            v.setAiDisabled(true);
            v.setInvulnerable(true);
            v.addCommandTag(MERCHANT);
            v.addCommandTag(MERCHANT + ":" + t.id());
            v.setCustomName(Text.literal(NAMES[Math.floorMod(t.id().hashCode(), NAMES.length)] + ", Travelling Merchant"));
            v.setCustomNameVisible(true);
            w.spawnEntity(v);
        }
        if (w.getTime() % 200 < 40) {
            List<VillagerEntity> ours = new ArrayList<>();
            for (Entity e : w.iterateEntities()) if (e instanceof VillagerEntity v && escort(v)) ours.add(v);
            for (VillagerEntity v : ours) {
                boolean close = false;
                for (ServerPlayerEntity p : players) if (p.squaredDistanceTo(v) < 130 * 130) close = true;
                if (!close) v.discard();
            }
        }
    }

    /** By the town's gate side of the square: open ground a little way from the centre. */
    private BlockPos spot(ServerWorld w, Net.Area t) {
        java.util.Random r = new java.util.Random(t.id().hashCode() * 31L + 5);
        int base = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, t.x(), t.z());
        for (int tries = 0; tries < 80; tries++) {
            double a = r.nextDouble() * Math.PI * 2, rad = 12 + r.nextDouble() * 12;
            int x = t.x() + (int) Math.round(Math.cos(a) * rad), z = t.z() + (int) Math.round(Math.sin(a) * rad);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(y - base) > 3) continue;
            BlockPos feet = new BlockPos(x, y, z);
            if (w.getBlockState(feet.down()).getCollisionShape(w, feet.down()).isEmpty() || !w.getFluidState(feet.down()).isEmpty()) continue;
            if (!w.getBlockState(feet).isAir() || !w.getBlockState(feet.up()).isAir()) continue;
            return feet;
        }
        return null;
    }

    private Net.Area townOf(Entity e) {
        for (String tag : e.getCommandTags()) if (tag.startsWith(MERCHANT + ":")) return AotRpg.PLACES.area(tag.substring(MERCHANT.length() + 1));
        return null;
    }

    // ------------------------------------------------------------------ the offer

    /** The job this town's merchant has today: a destination and the pay. */
    private Object[] offer(ServerWorld w, Net.Area from) {
        List<Net.Area> all = towns();
        List<Net.Area> fit = new ArrayList<>();
        for (Net.Area t : all) {
            double d = Math.hypot(t.x() - from.x(), t.z() - from.z());
            if (t != from && d > 350 && d < 2600) fit.add(t);
        }
        if (fit.isEmpty()) return null;
        java.util.Random r = new java.util.Random((from.id() + (w.getTimeOfDay() / 24000)).hashCode());
        Net.Area to = fit.get(r.nextInt(fit.size()));
        double dist = Math.hypot(to.x() - from.x(), to.z() - from.z());
        int danger = Math.max(Math.max(from.max(), to.max()), 1) + Math.max(from.titans(), to.titans());
        long pay = Math.round(Math.min(20000, Math.max(6000, 4500 + dist * 4.2 + danger * 90)) / 100.0) * 100;
        return new Object[] {to, pay, (int) dist};
    }

    public boolean use(ServerPlayerEntity p, Entity e) {
        if (!escort(e)) return false;
        Net.Area from = townOf(e);
        if (from == null) return true;
        String name = e.getCustomName() == null ? "Merchant" : e.getCustomName().getString();
        Job mine = jobs.get(p.getUuid());
        if (mine != null) {
            talk(p, e, name, "You're already guarding a load for someone. Get it to " + area(mine.to) + " first.", List.of("Right."));
            return true;
        }
        Object[] o = offer(p.getServerWorld(), from);
        if (o == null) {
            talk(p, e, name, "Nothing for you today, friend. The roads are closed.", List.of("Goodbye."));
            return true;
        }
        Net.Area to = (Net.Area) o[0];
        long pay = (long) o[1];
        offered.put(p.getUuid(), new String[] {from.id(), to.id(), String.valueOf(pay)});
        talk(p, e, name, "I've a load bound for " + to.name() + ", " + String.format("%,d", (int) o[2]) + " blocks from here. Titans on the road, they say. "
            + String.format("%,d", pay) + " Marks if my goods get there, less for every crate I lose, and a bonus if not a scratch is on the donkey.",
            List.of("I'll take it.", "Not now."));
        return true;
    }

    private void talk(ServerPlayerEntity p, Entity e, String name, String line, List<String> options) {
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Net.Talk(e.getId(), name, "Hires guards for the roads", line, options));
    }

    private static String area(String id) {
        Net.Area a = AotRpg.PLACES.area(id);
        return a == null ? id : a.name();
    }

    /** A reply in a merchant conversation. True when it was one. */
    public boolean choose(ServerPlayerEntity p, int entityId, String option) {
        Entity e = p.getServerWorld().getEntityById(entityId);
        if (e == null || !escort(e)) return false;
        String[] o = offered.remove(p.getUuid());
        String name = e.getCustomName() == null ? "Merchant" : e.getCustomName().getString();
        if (o == null || !option.equals("I'll take it.")) {
            talk(p, e, name, "Suit yourself. I'll be here.", List.of());
            return true;
        }
        start(p, e, o[0], o[1], Long.parseLong(o[2]));
        talk(p, e, name, "Good. She's called Liesl, and she's carrying everything I own. Don't let the big ones near her.", List.of());
        return true;
    }

    private void start(ServerPlayerEntity p, Entity merchant, String from, String to, long pay) {
        ServerWorld w = p.getServerWorld();
        DonkeyEntity d = EntityType.DONKEY.create(w);
        if (d == null) return;
        d.refreshPositionAndAngles(merchant.getX() + 1.5, merchant.getY(), merchant.getZ() + 1.5, 0, 0);
        d.initialize(w, w.getLocalDifficulty(merchant.getBlockPos()), SpawnReason.EVENT, null);
        d.setTame(true);
        d.setHasChest(true);
        d.addCommandTag(CARAVAN);
        d.setCustomName(Text.literal("Liesl (" + area(to) + ")"));
        d.setCustomNameVisible(true);
        var hp = d.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (hp != null) hp.setBaseValue(80);
        d.setHealth(80);
        var sp = d.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (sp != null) sp.setBaseValue(0.3);
        w.spawnEntity(d);
        Job j = new Job();
        j.player = p.getUuid();
        j.donkey = d.getUuid();
        j.from = from;
        j.to = to;
        Net.Area t = AotRpg.PLACES.area(to);
        j.tx = t == null ? 0 : t.x();
        j.tz = t == null ? 0 : t.z();
        j.pay = pay;
        j.maxHealth = 80;
        j.nextAmbush = w.getTime() + 20 * 60;
        jobs.put(p.getUuid(), j);
        Notify.toast(p, Text.literal("Escort: " + area(to)).formatted(Formatting.GOLD, Formatting.BOLD),
            Text.literal(String.format("%,d Marks · keep the donkey alive", pay)), 0xE0B96A, "minecraft:chest", "escort");
        AotRpg.QUESTS.markers(p, true);
    }

    // ------------------------------------------------------------------ on the road

    private void runJobs(ServerWorld w, int ticks) {
        for (Job j : new ArrayList<>(jobs.values())) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(j.player);
            Entity e = w.getEntity(j.donkey);
            if (p == null) {
                fail(j, null, "the merchant gave up on you");
                continue;
            }
            if (!(e instanceof DonkeyEntity d) || !d.isAlive()) {
                fail(j, p, "the donkey and its goods were lost");
                continue;
            }
            if (d.getHealth() < j.maxHealth - 0.5f) j.hurt = true;
            // Follows you: walks after you, and catches up if you've got far ahead (unseen).
            double dist = d.squaredDistanceTo(p);
            if (p.getServerWorld() != w) continue;
            if (dist > 48 * 48) {
                BlockPos near = ground(w, p.getBlockX() - 3, p.getBlockZ() - 3);
                if (near != null) d.refreshPositionAndAngles(near.getX() + 0.5, near.getY(), near.getZ() + 0.5, d.getYaw(), 0);
            } else if (dist > 5 * 5) {
                d.getNavigation().startMovingTo(p, 1.4);
            }
            // Arrived.
            if (Math.hypot(d.getX() - j.tx, d.getZ() - j.tz) < 45) {
                deliver(j, p, d);
                continue;
            }
            // Titans on the road: now and then, more often in dangerous country.
            if (w.getTime() >= j.nextAmbush) {
                Net.Area here = AotRpg.PLACES.areaAt(d.getX(), d.getZ());
                boolean safe = AotRpg.PLACES.nearest(d.getX(), d.getZ(), 90, "town", "safe") != null;
                int danger = here == null ? 1 : Math.max(1, here.titans());
                j.nextAmbush = w.getTime() + 20L * (safe ? 60 : Math.max(35, 110 - danger * 12));
                if (!safe) ambush(w, d, p, Math.min(4, 1 + danger / 2));
            }
        }
    }

    private void ambush(ServerWorld w, DonkeyEntity d, ServerPlayerEntity p, int n) {
        var kinds = TitanTypes.ordinary();
        if (kinds.isEmpty()) return;
        int made = 0;
        for (int i = 0; i < n; i++) {
            double a = w.getRandom().nextDouble() * Math.PI * 2;
            BlockPos at = ground(w, (int) (d.getX() + Math.cos(a) * 34), (int) (d.getZ() + Math.sin(a) * 34));
            if (at == null) continue;
            Entity t = kinds.get(w.getRandom().nextInt(kinds.size())).create(w);
            if (t == null) continue;
            t.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, w.getRandom().nextFloat() * 360, 0);
            if (t instanceof net.minecraft.entity.mob.MobEntity mob) {
                mob.initialize(w, w.getLocalDifficulty(at), SpawnReason.EVENT, null);
                mob.setTarget(d);
            }
            t.addCommandTag(AMBUSH);
            t.addCommandTag("aot_titan");
            if (w.spawnEntity(t)) made++;
        }
        if (made > 0) {
            Notify.toast(p, Text.literal("Titans on the road!").formatted(Formatting.RED, Formatting.BOLD), Text.literal("Protect the donkey"), 0xE03A3A,
                "minecraft:skeleton_skull", "escort");
            p.playSoundToPlayer(SoundEvents.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 0.4f, 0.7f);
        }
    }

    private static BlockPos ground(ServerWorld w, int x, int z) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos feet = new BlockPos(x, y, z);
        if (!w.getFluidState(feet.down()).isEmpty()) return null;
        return feet;
    }

    private void deliver(Job j, ServerPlayerEntity p, DonkeyEntity d) {
        jobs.remove(j.player);
        double kept = Math.max(0.25, d.getHealth() / j.maxHealth);
        long pay = Math.round(j.pay * kept / 10.0) * 10;
        boolean flawless = !j.hurt;
        if (flawless) pay = Math.round(pay * 1.25);
        d.discard();
        AotRpg.WALLET.addMarks(p, pay, "escort to " + area(j.to));
        Titles.show(p, Text.literal(flawless ? "FLAWLESS DELIVERY" : "DELIVERED").formatted(Formatting.GOLD, Formatting.BOLD),
            Text.literal(String.format("+%,d Marks", pay) + (flawless ? " · +25% for not a scratch" : " · " + Math.round(kept * 100) + "% of the goods arrived")), 5, 60, 15);
        p.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.8f, 1f);
        AotRpg.QUESTS.markers(p, true);
    }

    private void fail(Job j, ServerPlayerEntity p, String why) {
        jobs.remove(j.player);
        if (server != null) {
            Entity e = server.getOverworld().getEntity(j.donkey);
            if (e != null) e.discard();
        }
        if (p != null) {
            Notify.toast(p, Text.literal("Escort failed").formatted(Formatting.RED, Formatting.BOLD), Text.literal(cap(why)), 0xC0463A, "minecraft:chest", "escort");
            AotRpg.QUESTS.markers(p, true);
        }
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public void onPlayerDeath(ServerPlayerEntity p) {
        Job j = jobs.get(p.getUuid());
        if (j != null) fail(j, p, "you fell, and the merchant's donkey bolted");
    }

    /** Your delivery on the map. */
    public void markers(ServerPlayerEntity p, List<Net.Marker> list) {
        Job j = jobs.get(p.getUuid());
        if (j == null) return;
        int y = server == null ? 64 : server.getOverworld().getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, j.tx, j.tz);
        list.add(new Net.Marker("quest", "Deliver to " + area(j.to), j.tx, y, j.tz, 0xE0B96A));
    }

    public String rumour(ServerPlayerEntity p) {
        return "The travelling merchant by the square is hiring guards. Pays well, if you can keep titans off a donkey.";
    }
}
