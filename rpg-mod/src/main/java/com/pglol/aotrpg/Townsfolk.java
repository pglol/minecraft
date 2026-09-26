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
    }

    private void populate(ServerWorld w) {
        List<ServerPlayerEntity> players = w.getPlayers();
        // Quiet everyone, and send home the extras nobody is near.
        for (ServerPlayerEntity p : players) {
            for (VillagerEntity v : w.getEntitiesByClass(VillagerEntity.class, p.getBoundingBox().expand(64), Townsfolk::folk)) {
                if (!v.isSilent()) v.setSilent(true);
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
                BlockPos at = street(w, cx, cz, p);
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
                have++;
            }
        }
    }

    /** Somewhere in the streets to appear, out of the player's direct sight if possible. */
    private BlockPos street(ServerWorld w, int cx, int cz, ServerPlayerEntity p) {
        for (int tries = 0; tries < 12; tries++) {
            double a = rng.nextDouble() * Math.PI * 2, r = 6 + rng.nextDouble() * (RADIUS - 6);
            int x = cx + (int) (Math.cos(a) * r), z = cz + (int) (Math.sin(a) * r);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            var ground = w.getBlockState(feet.down());
            if (!ground.getFluidState().isEmpty() || !ground.isSolidBlock(w, feet.down())) continue;
            if (!w.getBlockState(feet).isAir() || !w.getBlockState(feet.up()).isAir()) continue;
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
                a.getLookControl().lookAt(b);
                b.getLookControl().lookAt(a);
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
        v.getLookControl().lookAt(p);
        String line = v.isBaby() ? new String[] {"Are you a soldier?", "Mama says not to talk to strangers.", "Can I see your blades?"}[rng.nextInt(3)]
            : GREETING[rng.nextInt(GREETING.length)];
        say(p.getServerWorld(), v, line, 0);
        return true;
    }
}
