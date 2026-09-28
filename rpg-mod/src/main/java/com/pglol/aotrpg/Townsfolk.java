package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Town life. Every town and village near a player keeps a crowd of ordinary people (drawn as people
 * on the client, each with their own plain look and name) walking its streets. They mutter to each
 * other as you pass, words floating over their heads, and say something back when you talk to them.
 * Villager noises are gone; the far-off extras are tidied away so nothing piles up.
 */
public final class Townsfolk {
    public static final String TAG = "aot_folk";
    private static final int PER_TOWN = 26, RADIUS = 48;
    private final Random rng = new Random();

    private static final String[] FIRST_M = {"Hans", "Karl", "Otto", "Emil", "Franz", "Jurgen", "Dieter", "Ernst", "Walter", "Anton",
        "Fritz", "Lukas", "Paul", "Konrad", "Max", "Rolf", "Kurt", "Georg", "Bruno", "Moritz", "Tobias", "Wilhelm", "Stefan", "Felix"};
    private static final String[] FIRST_F = {"Greta", "Anna", "Marta", "Ilse", "Frieda", "Hanna", "Elsa", "Clara", "Lena", "Rosa",
        "Berta", "Emma", "Liesel", "Paula", "Irma", "Heidi", "Katrin", "Johanna", "Ruth", "Sophie", "Leni", "Margit", "Helga", "Nina"};

    /** Overheard in the street. Pairs are one person speaking, then the other. */
    private static final String[][] CHATTER = {
        {"Bread's gone up again.", "It always does before winter."},
        {"Did you see the Survey Corps ride out this morning?", "Fewer came back last time."},
        {"My cousin's in the Garrison.", "Lucky him. They mostly just drink."},
        {"They say the Wall is a gift from God.", "Then God should fix the cracks."},
        {"Have you heard the bells?", "No. Nothing today, thank the Walls."},
        {"The Military Police took the Brauns' cart.", "For what?"},
        {"Rain's coming.", "Good. The fields need it."},
        {"Where are you off to?", "Market. Before the good cuts are gone."},
        {"My boy wants to join the training corps.", "Talk him out of it."},
        {"Did you sleep last night?", "Not with that racket from the tavern."},
        {"Look at that sky.", "Still here. Still standing."},
        {"I heard a titan got close to the gate.", "You hear a lot of things."},
        {"The priest was at it again.", "Same sermon, different Sunday."},
        {"Are you coming to the festival?", "If my knees let me."},
        {"Who's that?", "Soldier, by the look of the gear."},
        {"The well's running low.", "Tell the steward, not me."},
        {"My sister moved inside Wall Sina.", "Must be nice."},
        {"They're hiring at the mill.", "Hard work for bad pay."},
        {"Keep your voice down.", "Why? Who's listening?"},
        {"Another supply wagon came in.", "About time."},
    };
    /** Longer talks: three or four lines passed back and forth (A, B, A, B). */
    private static final String[][] TALKS = {
        {"Did you hear? The Survey Corps is back.", "How many this time?", "Fewer than left. Always fewer.", "Keep that to yourself."},
        {"You look tired.", "Up all night with the baby.", "Still not sleeping?", "Not a wink. She's got her father's lungs."},
        {"They raised the tax on salt.", "Salt! What next, air?", "Don't give them ideas."},
        {"I saw the Commander ride past.", "Erwin Smith? Here?", "Didn't even look at us.", "Why would he?"},
        {"Is that a titan steaming out past the gate?", "No, it's the smithy.", "Oh. Thank the Walls."},
        {"My husband wants to move to Wall Rose.", "Further from the edge. Sensible.", "Further from his mother, he means."},
        {"The cadets were training in the woods again.", "I heard the gas all night.", "Some of them will be heroes.", "Some of them will be names on a stone."},
        {"Have you tried the new baker?", "Burnt everything.", "Cheaper though.", "That's the only reason anyone goes."},
        {"The Garrison are drinking on the wall again.", "At least they're on the wall.", "Fair point."},
        {"My daughter says she'll marry a soldier.", "Better than a Military Policeman.", "Anything's better than a Military Policeman."},
    };
    /** Called out by someone in the street (to no one in particular). */
    private static final String[] CALLS = {
        "Fresh bread! Still warm!", "Apples from Wall Rose, three for a copper!", "Knives sharpened, blades honed!",
        "Anyone seen my goat?", "Mind your backs, cart coming through!", "News from the south gate! The Survey Corps rides tomorrow!",
        "Firewood! Dry firewood!", "Fish! Fresh from the canal!", "Watch where you're going!", "Has anyone seen a little boy in a blue coat?",
    };
    /** When you speak to someone. */
    private static final String[] GREETING = {
        "Morning. Or is it afternoon already?", "Can I help you with something?", "Mind the cart, it's heavy.",
        "You're one of the cadets, aren't you?", "Stay safe out there, soldier.", "Not today, I'm busy.",
        "Have you eaten? The bakery's still open.", "Don't go near the outer gate after dark.",
        "My feet are killing me.", "Lovely day for it.", "Is it true what they say about the titans?",
        "Watch your purse around here.", "Good luck, whatever you're doing.", "Ah, sorry, I thought you were someone else.",
        "The Walls will hold. They always have.", "If you're looking for work, try the market.",
    };

    public static boolean folk(Entity e) {
        return e instanceof VillagerEntity && !Story.phased(e) && !Raids.commander(e) && !Ferries.ferryman(e) && !Horses.isStableMaster(e)
            && !Vendors.vendor(e) && !Escorts.escort(e);
    }

    /** Their name: plain, and the same every time (from who they are). */
    public static String name(Entity e) {
        long h = e.getUuid().getLeastSignificantBits() ^ e.getUuid().getMostSignificantBits();
        boolean f = (h & 1) == 1;
        String[] pool = f ? FIRST_F : FIRST_M;
        return pool[(int) Math.floorMod(h >> 3, pool.length)];
    }

    public void tick(ServerWorld w, int ticks) {
        if (ticks % 100 == 17) populate(w);
        if (ticks % 40 == 5) chatter(w);
        if (ticks % 200 == 77) callOut(w);
        walk(w, ticks);
    }

    // ------------------------------------------------------------------ walking the streets

    /** What streets are made of here: cobbles, setts, gravel, paving and dirt paths. */
    private static final java.util.Set<net.minecraft.block.Block> ROAD = java.util.Set.of(
        net.minecraft.block.Blocks.COBBLESTONE, net.minecraft.block.Blocks.MOSSY_COBBLESTONE, net.minecraft.block.Blocks.GRAVEL,
        net.minecraft.block.Blocks.STONE, net.minecraft.block.Blocks.ANDESITE, net.minecraft.block.Blocks.POLISHED_ANDESITE,
        net.minecraft.block.Blocks.STONE_BRICKS, net.minecraft.block.Blocks.MOSSY_STONE_BRICKS, net.minecraft.block.Blocks.CRACKED_STONE_BRICKS,
        net.minecraft.block.Blocks.SMOOTH_STONE, net.minecraft.block.Blocks.DIRT_PATH, net.minecraft.block.Blocks.COARSE_DIRT);
    public static final String ROADS = "aot_road";
    private static final double[][] DIRS = {{1, 0}, {0.7071, 0.7071}, {0, 1}, {-0.7071, 0.7071}, {-1, 0}, {-0.7071, -0.7071}, {0, -1}, {0.7071, -0.7071}};

    private static final class Walk {
        double hx, hz;
        int pause, linger, turnIn = 40;
        UUID watched;
        /** Walking with someone: the one they follow, and where they keep beside them. */
        UUID leader;
        double side, back;
        /** Children run, and tear about more. */
        boolean runner;
        /** Heading home: their doorstep, and how long they've been stuck on the way. */
        BlockPos home;
        int stuck;
    }

    private final java.util.Map<UUID, Walk> walks = new java.util.HashMap<>();

    /** Open street under the sky at (x, z), near height y: its standing position, or null. */
    private static BlockPos street(ServerWorld w, double x, double z, double y) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;
        int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        if (Math.abs(top - y) > 1.2) return null;
        BlockPos feet = new BlockPos(bx, top, bz);
        if (!ROAD.contains(w.getBlockState(feet.down()).getBlock())) return null;
        if (!w.getBlockState(feet).getCollisionShape(w, feet).isEmpty() || !w.getBlockState(feet.up()).getCollisionShape(w, feet.up()).isEmpty()) return null;
        return feet;
    }

    /** Every tick: townsfolk walk the middle of the streets, stop to stare when suspicious, then move on. */
    private void walk(ServerWorld w, int ticks) {
        for (var it = walks.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (!(w.getEntity(en.getKey()) instanceof VillagerEntity v) || !v.isAlive()) {
                it.remove();
                continue;
            }
            Walk k = en.getValue();
            // They walk by our hand, without the game's own physics: keep their feet on the ground.
            if ((ticks + v.getId()) % 10 == 0) grounded(w, v);
            if (k.home != null) {
                if (homeward(w, v, k)) {
                    it.remove();
                    v.removeCommandTag(ROADS);
                    AotRpg.RESIDENTS.arrived(w, v);
                }
                continue;
            }
            // Someone they don't trust: stop, turn, and keep an eye on them until it passes.
            ServerPlayerEntity sus = AotRpg.WITNESS.watching(v);
            if (sus != null) {
                face(v, sus.getX(), sus.getZ());
                k.linger = 30;
                k.watched = sus.getUuid();
                continue;
            }
            if (k.linger > 0) {
                if (--k.linger == 0 && k.watched != null) {
                    // Then go on their way, away from whoever it was.
                    ServerPlayerEntity was = w.getServer().getPlayerManager().getPlayer(k.watched);
                    k.watched = null;
                    if (was != null) heading(w, v, k, v.getX() - was.getX(), v.getZ() - was.getZ());
                }
                continue;
            }
            if (k.pause > 0) {
                k.pause--;
                continue;
            }
            if (k.leader != null) {
                if (w.getEntity(k.leader) instanceof VillagerEntity lead && lead.isAlive() && lead.squaredDistanceTo(v) < 24 * 24) {
                    follow(w, v, k, lead);
                    continue;
                }
                k.leader = null; // lost them: off on their own
            }
            step(w, v, k);
        }
    }

    private void step(ServerWorld w, VillagerEntity v, Walk k) {
        double x = v.getX(), z = v.getZ(), y = v.getY();
        if (k.hx == 0 && k.hz == 0 && !heading(w, v, k, rng.nextDouble() - 0.5, rng.nextDouble() - 0.5)) {
            k.pause = 60;
            return;
        }
        // The street ends or turns: pick the way that bends least (never straight back unless it must).
        if (street(w, x + k.hx * 1.3, z + k.hz * 1.3, y) == null) {
            if (!heading(w, v, k, k.hx, k.hz)) k.pause = 40;
            return;
        }
        // Now and then, take a side street.
        if (--k.turnIn <= 0) {
            k.turnIn = 40 + rng.nextInt(80);
            double sx = -k.hz, sz = k.hx;
            if (rng.nextBoolean()) {
                sx = -sx;
                sz = -sz;
            }
            if (rng.nextInt(3) == 0 && street(w, x + sx * 3, z + sz * 3, y) != null && street(w, x + sx * 1.5, z + sz * 1.5, y) != null) {
                k.hx = sx;
                k.hz = sz;
            }
        }
        // Keep to the middle: measure the street to each side and drift toward its centre line.
        double px = -k.hz, pz = k.hx;
        int right = 0, left = 0;
        while (right < 5 && street(w, x + px * (right + 1), z + pz * (right + 1), y) != null) right++;
        while (left < 5 && street(w, x - px * (left + 1), z - pz * (left + 1), y) != null) left++;
        double lat = Math.max(-1, Math.min(1, (right - left) * 0.5)) * 0.05;
        // Someone in the way: wait a moment.
        net.minecraft.util.math.Box ahead = v.getBoundingBox().offset(k.hx * 0.8, 0, k.hz * 0.8);
        if (!w.getOtherEntities(v, ahead, e -> e instanceof net.minecraft.entity.LivingEntity).isEmpty()) {
            k.pause = 15 + rng.nextInt(20);
            return;
        }
        double speed = k.runner ? 0.16 : v.isBaby() ? 0.06 : 0.085;
        double nx = x + k.hx * speed + px * lat, nz = z + k.hz * speed + pz * lat;
        BlockPos next = street(w, nx, nz, y);
        if (next == null) {
            if (!heading(w, v, k, k.hx, k.hz)) k.pause = 40;
            return;
        }
        float want = (float) Math.toDegrees(Math.atan2(-k.hx, k.hz));
        float yaw = v.getYaw() + net.minecraft.util.math.MathHelper.wrapDegrees(want - v.getYaw()) * 0.25f;
        v.refreshPositionAndAngles(nx, next.getY(), nz, yaw, 0);
        v.setHeadYaw(yaw);
        v.setBodyYaw(yaw);
    }

    /** Walking with someone: keep your place beside (and a little behind) them, and stop when they stop. */
    private void follow(ServerWorld w, VillagerEntity v, Walk k, VillagerEntity lead) {
        Walk lk = walks.get(lead.getUuid());
        double hx = lk != null && (lk.hx != 0 || lk.hz != 0) ? lk.hx : -Math.sin(Math.toRadians(lead.getYaw()));
        double hz = lk != null && (lk.hx != 0 || lk.hz != 0) ? lk.hz : Math.cos(Math.toRadians(lead.getYaw()));
        double tx = lead.getX() - hx * k.back - hz * k.side, tz = lead.getZ() - hz * k.back + hx * k.side;
        double dx = tx - v.getX(), dz = tz - v.getZ(), d = Math.hypot(dx, dz);
        if (d < 0.15 || lk != null && lk.pause > 0 && d < 1.2) {
            // Stopped with them: turn to face whoever they're facing.
            face(v, lead.getX() + hx * 3, lead.getZ() + hz * 3);
            return;
        }
        double sp = Math.min(d, d > 2 ? 0.14 : 0.09);
        double nx = v.getX() + dx / d * sp, nz = v.getZ() + dz / d * sp;
        BlockPos next = street(w, nx, nz, v.getY());
        if (next == null) next = BlockPos.ofFloored(nx, v.getY(), nz);
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yaw = v.getYaw() + net.minecraft.util.math.MathHelper.wrapDegrees(want - v.getYaw()) * 0.3f;
        v.refreshPositionAndAngles(nx, next.getY(), nz, yaw, 0);
        v.setHeadYaw(yaw);
        v.setBodyYaw(yaw);
    }

    /** Standing on nothing (dropped, or a step that ran out): set down on the ground below. */
    private static void grounded(ServerWorld w, VillagerEntity v) {
        if (v.hasVehicle()) return;
        BlockPos feet = v.getBlockPos();
        if (!w.getBlockState(feet.down()).getCollisionShape(w, feet.down()).isEmpty()) return;
        for (int dy = 1; dy <= 24; dy++) {
            BlockPos b = feet.down(dy);
            if (!w.getBlockState(b).getCollisionShape(w, b).isEmpty()) {
                v.refreshPositionAndAngles(v.getX(), b.getY() + 1, v.getZ(), v.getYaw(), 0);
                v.fallDistance = 0;
                return;
            }
        }
        int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, v.getBlockX(), v.getBlockZ());
        v.refreshPositionAndAngles(v.getX(), top, v.getZ(), v.getYaw(), 0);
    }

    /** A new direction along the streets, as close as possible to (dx, dz). False if they're boxed in. */
    private boolean heading(ServerWorld w, VillagerEntity v, Walk k, double dx, double dz) {
        double len = Math.hypot(dx, dz);
        if (len < 1e-4) {
            dx = 1;
            dz = 0;
            len = 1;
        }
        dx /= len;
        dz /= len;
        double best = -2;
        double[] pick = null;
        for (double[] d : DIRS) {
            double dot = d[0] * dx + d[1] * dz;
            // Straight back only as a last resort.
            if (d[0] * k.hx + d[1] * k.hz < -0.9) dot -= 1.5;
            if (dot <= best) continue;
            if (street(w, v.getX() + d[0] * 1.5, v.getZ() + d[1] * 1.5, v.getY()) == null) continue;
            if (street(w, v.getX() + d[0] * 2.5, v.getZ() + d[1] * 2.5, v.getY()) == null) continue;
            best = dot;
            pick = d;
        }
        if (pick == null) return false;
        k.hx = pick[0];
        k.hz = pick[1];
        return true;
    }

    static void face(VillagerEntity v, double x, double z) {
        float yaw = (float) Math.toDegrees(Math.atan2(-(x - v.getX()), z - v.getZ()));
        v.setYaw(yaw);
        v.setHeadYaw(yaw);
        v.setBodyYaw(yaw);
    }

    /** Holds someone where they stand for a while (talking to a player). */
    public void hold(VillagerEntity v, int ticks) {
        Walk k = walks.get(v.getUuid());
        if (k != null) k.pause = Math.max(k.pause, ticks);
    }

    /** Stops walking someone (they're home, or asleep). */
    public void release(VillagerEntity v) {
        walks.remove(v.getUuid());
        v.removeCommandTag(ROADS);
    }

    /** Sends a resident walking home to their door. */
    public void sendHome(VillagerEntity v, BlockPos door) {
        adopt(v);
        Walk k = walks.get(v.getUuid());
        if (k != null && k.home == null) {
            k.home = door;
            k.stuck = 0;
            k.leader = null;
        }
    }

    /** A step toward home. True once they're at the door (or had to be put there, unseen). */
    private boolean homeward(ServerWorld w, VillagerEntity v, Walk k) {
        double dx = k.home.getX() + 0.5 - v.getX(), dz = k.home.getZ() + 0.5 - v.getZ(), d = Math.hypot(dx, dz);
        if (d < 1.2) return true;
        boolean seen = false;
        for (ServerPlayerEntity p : w.getPlayers()) if (p.squaredDistanceTo(v) < 18 * 18) seen = true;
        if (k.stuck > 80 && !seen) return true;
        double sp = v.isBaby() ? 0.07 : 0.09;
        // Straight for the door; round anything in the way by trying a little to either side.
        for (double turn : new double[] {0, 0.6, -0.6, 1.2, -1.2}) {
            double c = Math.cos(turn), s = Math.sin(turn);
            double hx = (dx * c - dz * s) / d, hz = (dx * s + dz * c) / d;
            double nx = v.getX() + hx * sp, nz = v.getZ() + hz * sp;
            BlockPos next = walkable(w, nx, nz, v.getY());
            if (next == null) continue;
            float yaw = (float) Math.toDegrees(Math.atan2(-hx, hz));
            v.refreshPositionAndAngles(nx, next.getY(), nz, yaw, 0);
            v.setHeadYaw(yaw);
            v.setBodyYaw(yaw);
            if (turn != 0) k.stuck++;
            return false;
        }
        k.stuck += 4;
        return false;
    }

    /** Somewhere a person can stand at (x, z), within a step of height y: its feet position, or null. */
    private static BlockPos walkable(ServerWorld w, double x, double z, double y) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(y + 0.01);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;
        for (int dy : new int[] {0, 1, -1}) {
            BlockPos feet = new BlockPos(bx, by + dy, bz);
            if (w.getBlockState(feet.down()).getCollisionShape(w, feet.down()).isEmpty()) continue;
            if (!w.getBlockState(feet).getCollisionShape(w, feet).isEmpty()) continue;
            if (!w.getBlockState(feet.up()).getCollisionShape(w, feet.up()).isEmpty()) continue;
            return feet;
        }
        return null;
    }

    /** Starts someone walking the streets (they walk by our hand from now on, not the villager brain). */
    public void adopt(VillagerEntity v) {
        v.setAiDisabled(true);
        v.addCommandTag(ROADS);
        walks.putIfAbsent(v.getUuid(), new Walk());
    }

    private void populate(ServerWorld w) {
        List<ServerPlayerEntity> players = w.getPlayers();
        // Quiet everyone, and send home the extras nobody is near.
        for (ServerPlayerEntity p : players) {
            for (VillagerEntity v : w.getEntitiesByClass(VillagerEntity.class, p.getBoundingBox().expand(64), Townsfolk::folk)) {
                if (!v.isSilent()) v.setSilent(true);
                // Anyone with the game's physics switched off (ours, or left like that) stays on the ground.
                if (v.isAiDisabled()) grounded(w, v);
                // Their own name (other mods and the look-at label show it, never "Villager").
                if (!v.hasCustomName() && v.getCommandTags().contains(TAG)) v.setCustomName(Text.literal(name(v)));
                // Townsfolk who wander on the villager brain (or were walking before a restart) take to the streets.
                boolean walker = v.getCommandTags().contains(TAG) || v.getCommandTags().contains(ROADS) || !v.isAiDisabled();
                if (walker && !v.getCommandTags().contains("aot_walker") && !walks.containsKey(v.getUuid())
                    && street(w, v.getX(), v.getZ(), v.getY()) != null) adopt(v);
            }
        }
        // Collected first: removing while walking the world's entity list breaks the walk.
        List<VillagerEntity> ours = new java.util.ArrayList<>();
        for (Entity e : w.iterateEntities()) if (e instanceof VillagerEntity v && v.getCommandTags().contains(TAG)) ours.add(v);
        boolean night = Residents.phase(w) == 0;
        int stragglers = 0;
        for (VillagerEntity v : ours) {
            boolean near = false, seen = false;
            for (ServerPlayerEntity p : players) {
                if (p.squaredDistanceTo(v) < 160 * 160) near = true;
                if (p.squaredDistanceTo(v) < 24 * 24) seen = true;
            }
            // Passers-by go home for the night too (out of sight), bar a few.
            if (night && !seen && ++stragglers > 4) near = false;
            if (!near) v.discard();
        }
        for (ServerPlayerEntity p : players) {
            Net.Area town = AotRpg.PLACES.nearest(p.getX(), p.getZ(), 110, "town", "village", "city", "capital");
            if (town == null) continue;
            int cx = town.x(), cz = town.z();
            if (!w.isChunkLoaded(cx >> 4, cz >> 4)) continue;
            Box box = new Box(cx - RADIUS - 8, -64, cz - RADIUS - 8, cx + RADIUS + 8, 400, cz + RADIUS + 8);
            int have = w.getEntitiesByClass(VillagerEntity.class, box, Townsfolk::folk).size();
            // At night the streets empty: the townspeople are home in bed, only a few stragglers about.
            int cap = Residents.phase(w) == 0 ? 4 : PER_TOWN;
            for (int i = 0; i < 3 && have < cap; i++) {
                BlockPos at = spawnSpot(w, cx, cz, p);
                if (at == null) continue;
                // Some walk alone; some in twos and threes (friends, a family with a child, kids chasing each other).
                int roll = rng.nextInt(10);
                int size = roll < 5 ? 1 : roll < 8 ? 2 : 3;
                boolean kids = size > 1 && rng.nextInt(5) == 0;
                VillagerEntity lead = null;
                for (int m = 0; m < size && have < PER_TOWN + 2; m++) {
                    VillagerEntity v = EntityType.VILLAGER.create(w);
                    if (v == null) continue;
                    double ox = m == 0 ? 0 : (m == 1 ? 0.9 : -0.9), oz = m == 0 ? 0 : -0.6;
                    v.refreshPositionAndAngles(at.getX() + 0.5 + ox, at.getY(), at.getZ() + 0.5 + oz, rng.nextFloat() * 360, 0);
                    v.initialize(w, w.getLocalDifficulty(at), SpawnReason.EVENT, null);
                    v.setVillagerData(v.getVillagerData().withProfession(VillagerProfession.NONE));
                    v.setSilent(true);
                    v.addCommandTag(TAG);
                    boolean child = kids || (size == 3 && m == 2) || (size == 1 && rng.nextInt(9) == 0);
                    if (child) v.setBaby(true);
                    w.spawnEntity(v);
                    v.setCustomName(Text.literal(name(v)));
                    adopt(v);
                    Walk k = walks.get(v.getUuid());
                    if (k != null) {
                        k.runner = child && (kids || rng.nextInt(3) == 0);
                        if (lead != null) {
                            k.leader = lead.getUuid();
                            k.side = m == 1 ? 1.0 : -1.0;
                            k.back = kids ? 1.2 : 0.3;
                        }
                    }
                    if (lead == null) lead = v;
                    have++;
                }
            }
        }
    }

    /** Somewhere in the streets to appear, out of the player's direct sight if possible. */
    private BlockPos spawnSpot(ServerWorld w, int cx, int cz, ServerPlayerEntity p) {
        for (int tries = 0; tries < 30; tries++) {
            double a = rng.nextDouble() * Math.PI * 2, r = 6 + rng.nextDouble() * (RADIUS - 6);
            int x = cx + (int) (Math.cos(a) * r), z = cz + (int) (Math.sin(a) * r);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos feet = street(w, x + 0.5, z + 0.5, y);
            if (feet == null) continue;
            // Not on a roof: the street is near the town's own level.
            if (Math.abs(y - w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, cx, cz)) > 6) continue;
            if (p.squaredDistanceTo(x, y, z) < 12 * 12) continue;
            return feet;
        }
        return null;
    }

    /** Two people standing close pass a few words; a lone one sometimes mutters to themselves. */
    private void chatter(ServerWorld w) {
        for (ServerPlayerEntity p : new java.util.ArrayList<>(w.getPlayers())) {
            if (rng.nextInt(3) != 0) continue;
            List<VillagerEntity> near = new ArrayList<>(w.getEntitiesByClass(VillagerEntity.class, p.getBoundingBox().expand(20), v -> folk(v) && !v.isSleeping()));
            if (near.size() < 2) continue;
            java.util.Collections.shuffle(near, rng);
            for (VillagerEntity a : near) {
                VillagerEntity b = null;
                for (VillagerEntity o : near) if (o != a && o.squaredDistanceTo(a) < 5 * 5) b = o;
                if (b == null || a.isBaby() || b.isBaby()) continue;
                Walk wa = walks.get(a.getUuid()), wb = walks.get(b.getUuid());
                boolean together = wa != null && wb != null && (b.getUuid().equals(wa.leader) || a.getUuid().equals(wb.leader));
                // Longer talks now and then; a pair walking together talks as they go.
                String[] lines = rng.nextInt(3) == 0 ? TALKS[rng.nextInt(TALKS.length)] : CHATTER[rng.nextInt(CHATTER.length)];
                int span = 50 * lines.length + 30;
                if (!together) {
                    face(a, b.getX(), b.getZ());
                    face(b, a.getX(), a.getZ());
                    if (wa != null) wa.pause = Math.max(wa.pause, span);
                    if (wb != null) wb.pause = Math.max(wb.pause, span);
                }
                for (int i = 0; i < lines.length; i++) say(w, i % 2 == 0 ? a : b, lines[i], i * 50);
                break;
            }
        }
    }

    /** Now and then someone near a player calls out: a seller, a lost parent, a bit of news. */
    private void callOut(ServerWorld w) {
        for (ServerPlayerEntity p : new java.util.ArrayList<>(w.getPlayers())) {
            if (rng.nextInt(6) != 0) continue;
            List<VillagerEntity> near = w.getEntitiesByClass(VillagerEntity.class, p.getBoundingBox().expand(22), v -> folk(v) && !v.isBaby() && !v.isSleeping());
            if (near.isEmpty()) continue;
            VillagerEntity v = near.get(rng.nextInt(near.size()));
            say(w, v, CALLS[rng.nextInt(CALLS.length)], 0);
        }
    }

    private static void say(ServerWorld w, Entity who, String text, int delay) {
        Runnable send = () -> {
            if (!who.isAlive()) return;
            Net.Chatter msg = new Net.Chatter(who.getId(), text);
            for (ServerPlayerEntity o : PlayerLookup.around(w, who.getPos(), 24)) {
                if (ServerPlayNetworking.canSend(o, Net.Chatter.ID)) ServerPlayNetworking.send(o, msg);
            }
        };
        if (delay <= 0) send.run();
        else AotRpg.SCHEDULER.later(delay, send);
    }

    /** Talking to one of them: a word back, over their head. True when handled. */
    public boolean talk(ServerPlayerEntity p, Entity e) {
        if (!folk(e)) return false;
        VillagerEntity v = (VillagerEntity) e;
        // A real trader keeps their trades.
        if (v.getVillagerData().getProfession() != VillagerProfession.NONE && v.getVillagerData().getProfession() != VillagerProfession.NITWIT
            && !v.getOffers().isEmpty()) return false;
        face(v, p.getX(), p.getZ());
        Walk wk = walks.get(v.getUuid());
        if (wk != null) wk.pause = Math.max(wk.pause, 80);
        String line = v.isBaby() ? new String[] {"Are you a soldier?", "Mama says not to talk to strangers.", "Can I see your blades?"}[rng.nextInt(3)]
            : GREETING[rng.nextInt(GREETING.length)];
        say(p.getServerWorld(), v, line, 0);
        return true;
    }
}
