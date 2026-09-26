package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.mob.MobEntity;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Boss raids. Raid Commanders stand at every town and Survey Corps camp; talk to one to pick a
 * shifter to hunt and a difficulty. Your party (up to five, standing with you) is taken beyond
 * the walls: waves of titans first, then the shifter itself, whose nape takes many strikes. Win
 * and everyone who fought is paid in Marks, pass XP, regiment XP and guaranteed fine gear.
 */
public final class Raids {
    public static final String NPC = "aot_npc_raid", MOB = "aot_raid", BOSS = "aot_raid_boss", STRIKES = "aot_raidstrikes:";
    private static final long LIMIT_MS = 15 * 60_000L;

    public record Boss(String id, String name, String shifter, int level) { }

    public static final List<Boss> BOSSES = List.of(
        new Boss("cart", "Cart Titan", "cart", 12),
        new Boss("female", "Female Titan", "female", 20),
        new Boss("jaw", "Jaw Titan", "jaw", 25),
        new Boss("attack", "Attack Titan", "attack", 30),
        new Boss("armored", "Armored Titan", "armored", 38),
        new Boss("beast", "Beast Titan", "beast", 46),
        new Boss("warhammer", "War Hammer Titan", "warhammer", 54),
        new Boss("colossal", "Colossal Titan", "colossal", 64));

    /** Difficulty: nape strikes, waves before the boss, levels above the boss's own, reward multiplier. */
    public static final String[] DIFFS = {"Normal", "Hard", "Nightmare"};
    private static final int[] STRIKE_N = {6, 10, 15}, WAVES = {2, 3, 4}, LV_UP = {0, 10, 20};
    private static final double[] PAY = {1, 2.2, 4};

    private static final class Raid {
        Boss boss;
        int diff;
        Vec3d center;
        final List<UUID> players = new ArrayList<>();
        final Map<UUID, Vec3d> back = new HashMap<>();
        final List<UUID> mobs = new ArrayList<>();
        UUID bossId;
        int wave;
        long started, nextWave, endAt;
        boolean won, over;
        ServerBossBar bar;
    }

    private final List<Raid> raids = new ArrayList<>();
    /** Where raiders go back to (the Raid Commander they left from), claimed when they respawn after falling. */
    private final Map<UUID, Vec3d> returns = new HashMap<>();

    /** Is this player in a boss raid right now? */
    public boolean inRaid(UUID id) {
        for (Raid r : raids) if (!r.over && r.players.contains(id)) return true;
        return false;
    }

    /** A raider who fell: they wake back at the commander, with their team, not at a recovery post. */
    public Vec3d takeReturn(UUID id) {
        return returns.remove(id);
    }
    private MinecraftServer server;

    public void open(MinecraftServer server) {
        this.server = server;
        raids.clear();
    }

    public static boolean commander(Entity e) {
        return e.getCommandTags().contains(NPC);
    }

    public static boolean raidMob(Entity e) {
        return e.getCommandTags().contains(MOB);
    }

    /** Nape strikes set for a raid boss, or 0. */
    public static int strikes(Entity e) {
        for (String t : e.getCommandTags()) {
            if (t.startsWith(STRIKES)) {
                try {
                    return Integer.parseInt(t.substring(STRIKES.length()));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private Raid raidOf(UUID p) {
        for (Raid r : raids) if (!r.over && r.players.contains(p)) return r;
        return null;
    }

    // ------------------------------------------------------------------ commanders

    /** Every 30 s: a Raid Commander stands at each town and camp near a player. */
    private void placeCommanders(ServerWorld w) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            for (Net.Area a : AotRpg.PLACES.areas()) {
                if (!a.look().equals("town") && !a.look().equals("camp")) continue;
                if (Math.hypot(a.x() - p.getX(), a.z() - p.getZ()) > 96) continue;
                if (!w.isChunkLoaded(a.x() >> 4, a.z() >> 4)) continue;
                Box box = new Box(a.x() - 48, -64, a.z() - 48, a.x() + 48, 400, a.z() + 48);
                if (!w.getEntitiesByClass(VillagerEntity.class, box, Raids::commander).isEmpty()) continue;
                int x = a.x() + 3, z = a.z() + 3;
                int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                VillagerEntity v = EntityType.VILLAGER.create(w);
                if (v == null) continue;
                v.refreshPositionAndAngles(x + 0.5, y, z + 0.5, 0, 0);
                v.setAiDisabled(true);
                v.setInvulnerable(true);
                v.setPersistent();
                v.setSilent(true);
                v.setCustomName(Text.literal("Raid Commander").formatted(Formatting.RED, Formatting.BOLD));
                v.setCustomNameVisible(true);
                v.addCommandTag(NPC);
                w.spawnEntity(v);
            }
        }
    }

    public void talk(ServerPlayerEntity p) {
        send(p, true);
    }

    public void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.RaidView.ID)) return;
        List<Net.RaidBoss> list = new ArrayList<>();
        for (Boss b : BOSSES) list.add(new Net.RaidBoss(b.id(), b.name(), b.level(), TitanTypes.shifter(b.shifter()) != null));
        List<String> party = new ArrayList<>();
        List<ServerPlayerEntity> here = partyHere(p);
        for (ServerPlayerEntity m : here) {
            Profile mp = AotRpg.PROFILES.get(m.getUuid());
            party.add(mp.cls().tag() + " " + mp.name + " · Lv " + mp.level + " · " + mp.cls().title);
        }
        party.add("» " + Classes.lineup(here));
        Raid r = raidOf(p.getUuid());
        ServerPlayNetworking.send(p, new Net.RaidView(list, party, r == null ? "" : r.boss.name() + " (" + DIFFS[r.diff] + ")", open));
    }

    /** You and your party members standing within 24 blocks (at most five). */
    private List<ServerPlayerEntity> partyHere(ServerPlayerEntity p) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        out.add(p);
        Parties.Party party = AotRpg.PARTIES.of(p.getUuid());
        if (party == null) return out;
        for (UUID m : party.members) {
            if (m.equals(p.getUuid()) || out.size() >= Parties.MAX) continue;
            ServerPlayerEntity o = server.getPlayerManager().getPlayer(m);
            if (o != null && o.getWorld() == p.getWorld() && o.squaredDistanceTo(p) < 24 * 24 && raidOf(m) == null) out.add(o);
        }
        return out;
    }

    // ------------------------------------------------------------------ starting

    public void start(ServerPlayerEntity p, String bossId, int diff) {
        if (raidOf(p.getUuid()) != null) return;
        Boss boss = null;
        for (Boss b : BOSSES) if (b.id().equals(bossId)) boss = b;
        if (boss == null || diff < 0 || diff >= DIFFS.length) return;
        if (TitanTypes.shifter(boss.shifter()) == null) {
            Notify.toast(p, Text.literal("That shifter isn't in this world").formatted(Formatting.RED), null, 0xC0463A, null, null);
            return;
        }
        Parties.Party party = AotRpg.PARTIES.of(p.getUuid());
        if (party != null && !party.leader.equals(p.getUuid())) {
            Notify.toast(p, Text.literal("Only your party leader can start a raid").formatted(Formatting.RED), null, 0xC0463A, null, null);
            return;
        }
        ServerWorld w = server.getOverworld();
        Raid r = new Raid();
        r.boss = boss;
        r.diff = diff;
        r.center = arena(w);
        r.started = System.currentTimeMillis();
        r.nextWave = r.started + 12_000;
        r.endAt = r.started + LIMIT_MS;
        r.bar = new ServerBossBar(Text.literal(boss.name() + " raid"), BossBar.Color.RED, BossBar.Style.PROGRESS);
        for (ServerPlayerEntity m : partyHere(p)) {
            r.players.add(m.getUuid());
            r.back.put(m.getUuid(), m.getPos());
            double a = r.players.size() * 1.2;
            m.teleport(w, r.center.x + Math.cos(a) * 3, r.center.y, r.center.z + Math.sin(a) * 3, m.getYaw(), m.getPitch());
            r.bar.addPlayer(m);
            Notify.toast(m, Text.literal(boss.name().toUpperCase() + " RAID").formatted(Formatting.RED, Formatting.BOLD),
                Text.literal(DIFFS[diff] + " · clear the waves, then take its nape (" + STRIKE_N[diff] + " strikes)"), 0xE04A3A, "minecraft:bell", "raid");
            m.playSoundToPlayer(SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.MASTER, 1f, 0.9f);
        }
        raids.add(r);
    }

    /** A clear spot beyond Wall Maria, on the ground. */
    private Vec3d arena(ServerWorld w) {
        int[] walls = AotRpg.PLACES.walls;
        double rad = (walls == null ? 3000 : walls[2]) + 700 + w.getRandom().nextInt(400);
        double a = w.getRandom().nextDouble() * Math.PI * 2;
        int x = (int) (Math.cos(a) * rad), z = (int) (Math.sin(a) * rad);
        w.getChunk(x >> 4, z >> 4);
        int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new Vec3d(x + 0.5, y + 1, z + 0.5);
    }

    // ------------------------------------------------------------------ running

    public void tick(int ticks) {
        if (server == null) return;
        ServerWorld w = server.getOverworld();
        if (ticks % 600 == 123) placeCommanders(w);
        if (ticks % 10 != 0 || raids.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Raid r : new ArrayList<>(raids)) {
            if (r.over) {
                raids.remove(r);
                continue;
            }
            // Who is still in it: alive, here, and near the arena.
            r.players.removeIf(id -> {
                ServerPlayerEntity m = server.getPlayerManager().getPlayer(id);
                boolean out = m == null || m.isDead() || m.getWorld() != w || m.getPos().distanceTo(r.center) > 160;
                if (out) {
                    Vec3d back = r.back.get(id);
                    if (m != null) {
                        r.bar.removePlayer(m);
                        Notify.toast(m, Text.literal("You fell out of the raid").formatted(Formatting.RED), null, 0xC0463A, null, null);
                        // Alive but gone astray: straight back to the commander. Fallen: back there on respawn.
                        if (!m.isDead() && back != null) m.teleport(w, back.x, back.y, back.z, m.getYaw(), m.getPitch());
                    }
                    if ((m == null || m.isDead()) && back != null) returns.put(id, back);
                }
                return out;
            });
            if (r.players.isEmpty() || now > r.endAt) {
                finish(r, false);
                continue;
            }
            // The arena is the raid's alone: the region's own titans (far above the raid's level) are kept out.
            if (ticks % 40 == 0) {
                Box ring = new Box(r.center.x - 140, r.center.y - 60, r.center.z - 140, r.center.x + 140, r.center.y + 120, r.center.z + 140);
                for (Entity e : w.getOtherEntities(null, ring, e -> AotRpg.isTitan(e) && !raidMob(e) && !(e.getControllingPassenger() instanceof ServerPlayerEntity)
                    && !TitanLevels.part(e))) {
                    e.discard();
                }
            }
            // Keep everyone in the ring.
            for (UUID id : r.players) {
                ServerPlayerEntity m = server.getPlayerManager().getPlayer(id);
                if (m != null && m.getPos().distanceTo(r.center) > 110) {
                    m.teleport(w, r.center.x, r.center.y + 1, r.center.z, m.getYaw(), m.getPitch());
                }
            }
            r.mobs.removeIf(id -> {
                Entity e = w.getEntity(id);
                return e == null || !e.isAlive();
            });
            int maxWaves = WAVES[r.diff];
            if (r.wave < maxWaves && r.mobs.isEmpty() && now >= r.nextWave) {
                r.wave++;
                spawnWave(w, r);
                r.nextWave = now + 6000;
            } else if (r.wave == maxWaves && r.mobs.isEmpty() && r.bossId == null && now >= r.nextWave) {
                spawnBoss(w, r);
            }
            Entity boss = r.bossId == null ? null : w.getEntity(r.bossId);
            if (r.bossId != null && (boss == null || !boss.isAlive())) {
                finish(r, true);
                continue;
            }
            if (boss != null && boss.getPos().distanceTo(r.center) > 70) boss.requestTeleport(r.center.x, r.center.y, r.center.z);
            long left = (r.endAt - now) / 1000;
            String status = r.bossId == null ? "Wave " + Math.max(1, r.wave) + "/" + maxWaves + " · " + r.mobs.size() + " titans"
                : r.boss.name() + " · nape " + AotRpg.TITAN_LEVELS.strikesOn(boss) + "/" + STRIKE_N[r.diff];
            r.bar.setName(Text.literal(status + "  ·  " + left / 60 + ":" + String.format("%02d", left % 60)).formatted(Formatting.RED));
            r.bar.setPercent(Math.max(0.02f, (float) left * 1000 / LIMIT_MS));
        }
    }

    private int raidLevel(Raid r) {
        return r.boss.level() + LV_UP[r.diff];
    }

    private void spawnWave(ServerWorld w, Raid r) {
        List<EntityType<?>> kinds = TitanTypes.ordinary();
        if (kinds.isEmpty()) return;
        int n = 2 + r.players.size() + r.wave + r.diff;
        for (int i = 0; i < n; i++) {
            Entity t = spawn(w, r, kinds.get(w.getRandom().nextInt(kinds.size())), 28 + w.getRandom().nextInt(16));
            if (t == null) continue;
            TitanLevels.fix(t, raidLevel(r) - 5 + w.getRandom().nextInt(6));
            r.mobs.add(t.getUuid());
        }
        for (UUID id : r.players) {
            ServerPlayerEntity m = server.getPlayerManager().getPlayer(id);
            if (m != null) Notify.toast(m, Text.literal("Wave " + r.wave).formatted(Formatting.RED), Text.literal(n + " titans incoming"), 0xE04A3A, null, "raid");
        }
    }

    private void spawnBoss(ServerWorld w, Raid r) {
        EntityType<?> type = TitanTypes.shifter(r.boss.shifter());
        if (type == null) return;
        Entity b = spawn(w, r, type, 30);
        if (b == null) return;
        b.addCommandTag(BOSS);
        b.addCommandTag(STRIKES + STRIKE_N[r.diff]);
        TitanLevels.fix(b, raidLevel(r));
        b.setCustomName(Text.literal(r.boss.name()).formatted(Formatting.DARK_RED, Formatting.BOLD));
        r.bossId = b.getUuid();
        for (UUID id : r.players) {
            ServerPlayerEntity m = server.getPlayerManager().getPlayer(id);
            if (m == null) continue;
            Notify.toast(m, Text.literal(r.boss.name().toUpperCase()).formatted(Formatting.DARK_RED, Formatting.BOLD),
                Text.literal("It has come. " + STRIKE_N[r.diff] + " nape strikes to bring it down."), 0xC0263A, "minecraft:wither_skeleton_skull", "raid");
            m.playSoundToPlayer(SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 0.8f, 0.7f);
        }
    }

    private Entity spawn(ServerWorld w, Raid r, EntityType<?> type, double dist) {
        double a = w.getRandom().nextDouble() * Math.PI * 2;
        int x = (int) (r.center.x + Math.cos(a) * dist), z = (int) (r.center.z + Math.sin(a) * dist);
        w.getChunk(x >> 4, z >> 4);
        int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        Entity t = type.create(w);
        if (t == null) return null;
        t.refreshPositionAndAngles(x + 0.5, y, z + 0.5, w.getRandom().nextFloat() * 360, 0);
        if (t instanceof MobEntity mob) {
            mob.initialize(w, w.getLocalDifficulty(new BlockPos(x, y, z)), SpawnReason.EVENT, null);
            mob.setPersistent();
            mob.getNavigation().startMovingTo(r.center.x, r.center.y, r.center.z, 1.0);
        }
        t.addCommandTag(MOB);
        t.addCommandTag("aot_titan");
        return w.spawnEntity(t) ? t : null;
    }

    private void finish(Raid r, boolean won) {
        r.over = true;
        r.won = won;
        ServerWorld w = server.getOverworld();
        for (UUID id : r.mobs) {
            Entity e = w.getEntity(id);
            if (e != null) e.discard();
        }
        if (r.bossId != null) {
            Entity e = w.getEntity(r.bossId);
            if (e != null && e.isAlive()) e.discard();
        }
        r.bar.clearPlayers();
        for (var e : r.back.entrySet()) {
            ServerPlayerEntity m = server.getPlayerManager().getPlayer(e.getKey());
            if (m == null) continue;
            boolean fought = r.players.contains(e.getKey());
            if (won && fought) reward(m, r);
            else if (!won) Notify.toast(m, Text.literal("Raid failed").formatted(Formatting.RED),
                Text.literal(r.boss.name() + " got away"), 0xC0463A, null, "raid");
            // Home after a moment to collect loot (looked up again then: a player who fell is a new entity after respawning).
            Vec3d back = e.getValue();
            UUID id = e.getKey();
            if (m.isDead()) {
                returns.put(id, back);
                continue;
            }
            AotRpg.SCHEDULER.later(won ? 200 : 40, () -> {
                ServerPlayerEntity now = server.getPlayerManager().getPlayer(id);
                if (now == null || now.isDead()) {
                    returns.put(id, back);
                    return;
                }
                if (now.getWorld() == w && now.getPos().distanceTo(r.center) < 200) now.teleport(w, back.x, back.y, back.z, now.getYaw(), now.getPitch());
            });
        }
    }

    private void reward(ServerPlayerEntity m, Raid r) {
        double pay = PAY[r.diff];
        long marks = Math.round((400 + r.boss.level() * 20) * pay);
        AotRpg.WALLET.earn(m, marks, r.boss.name() + " raid");
        AotRpg.SEASON.xp(m, Math.round(600 * pay));
        AotRpg.REGIMENTS.gain(m, Math.round(250 * pay));
        Gear.Rarity rar = r.diff == 2 ? Gear.Rarity.LEGENDARY : r.diff == 1 ? Gear.Rarity.EPIC : Gear.Rarity.RARE;
        AotRpg.SATCHEL.add(m, Gear.roll(m.getRandom(), rar, Gear.dropLevel(m, raidLevel(r), 2)));
        Notify.toast(m, Text.literal("RAID CLEARED").formatted(Formatting.GOLD, Formatting.BOLD),
            Text.literal(r.boss.name() + " · +" + marks + " Marks · " + rar.title + " gear in your satchel"), 0xF2C14E, "minecraft:nether_star", "raid");
        m.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1f, 1f);
    }

    public void forget(ServerPlayerEntity p) {
        Raid r = raidOf(p.getUuid());
        if (r != null) {
            r.players.remove(p.getUuid());
            r.bar.removePlayer(p);
            Vec3d back = r.back.get(p.getUuid());
            if (back != null) p.teleport(server.getOverworld(), back.x, back.y, back.z, p.getYaw(), p.getPitch());
        }
    }
}
