package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
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
    private static final int PER_TOWN = 14, RADIUS = 40;
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
        return e instanceof VillagerEntity && !Story.phased(e) && !Raids.commander(e) && !Ferries.ferryman(e) && !Horses.isStableMaster(e);
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
        double speed = v.isBaby() ? 0.06 : 0.085;
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

    private static void face(VillagerEntity v, double x, double z) {
        float yaw = (float) Math.toDegrees(Math.atan2(-(x - v.getX()), z - v.getZ()));
        v.setYaw(yaw);
        v.setHeadYaw(yaw);
        v.setBodyYaw(yaw);
    }

    /** Starts someone walking the streets (they walk by our hand from now on, not the villager brain). */
    private void adopt(VillagerEntity v) {
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
                // Townsfolk who wander on the villager brain (or were walking before a restart) take to the streets.
                boolean walker = v.getCommandTags().contains(TAG) || v.getCommandTags().contains(ROADS) || !v.isAiDisabled();
                if (walker && !v.getCommandTags().contains("aot_walker") && !walks.containsKey(v.getUuid())
                    && street(w, v.getX(), v.getZ(), v.getY()) != null) adopt(v);
            }
        }
        for (Entity e : w.iterateEntities()) {
            if (!(e instanceof VillagerEntity v) || !v.getCommandTags().contains(TAG)) continue;
            boolean near = false;
            for (ServerPlayerEntity p : players) if (p.squaredDistanceTo(v) < 160 * 160) near = true;
            if (!near) v.discard();
        }
        for (ServerPlayerEntity p : players) {
            Net.Area town = AotRpg.PLACES.nearest(p.getX(), p.getZ(), 110, "town", "village", "city", "capital");
            if (town == null) continue;
            int cx = town.x(), cz = town.z();
            if (!w.isChunkLoaded(cx >> 4, cz >> 4)) continue;
            Box box = new Box(cx - RADIUS - 8, -64, cz - RADIUS - 8, cx + RADIUS + 8, 400, cz + RADIUS + 8);
            int have = w.getEntitiesByClass(VillagerEntity.class, box, Townsfolk::folk).size();
            for (int i = 0; i < 2 && have < PER_TOWN; i++) {
                BlockPos at = spawnSpot(w, cx, cz, p);
                if (at == null) continue;
                VillagerEntity v = EntityType.VILLAGER.create(w);
                if (v == null) continue;
                v.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, rng.nextFloat() * 360, 0);
                v.initialize(w, w.getLocalDifficulty(at), SpawnReason.EVENT, null);
                v.setVillagerData(v.getVillagerData().withProfession(VillagerProfession.NONE));
                v.setSilent(true);
                v.addCommandTag(TAG);
                if (rng.nextInt(9) == 0) v.setBaby(true);
                w.spawnEntity(v);
                adopt(v);
                have++;
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
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (rng.nextInt(3) != 0) continue;
            List<VillagerEntity> near = new ArrayList<>(w.getEntitiesByClass(VillagerEntity.class, p.getBoundingBox().expand(20), Townsfolk::folk));
            if (near.size() < 2) continue;
            java.util.Collections.shuffle(near, rng);
            for (VillagerEntity a : near) {
                VillagerEntity b = null;
                for (VillagerEntity o : near) if (o != a && o.squaredDistanceTo(a) < 5 * 5) b = o;
                if (b == null || a.isBaby() || b.isBaby()) continue;
                String[] pair = CHATTER[rng.nextInt(CHATTER.length)];
                face(a, b.getX(), b.getZ());
                face(b, a.getX(), a.getZ());
                Walk wa = walks.get(a.getUuid()), wb = walks.get(b.getUuid());
                if (wa != null) wa.pause = Math.max(wa.pause, 110);
                if (wb != null) wb.pause = Math.max(wb.pause, 110);
                say(w, a, pair[0], 0);
                say(w, b, pair[1], 50);
                break;
            }
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
