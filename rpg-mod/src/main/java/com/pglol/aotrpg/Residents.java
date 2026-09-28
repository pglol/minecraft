package com.pglol.aotrpg;

import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The people who live in the towns. Most town houses are home to a family (the same family every
 * time: names, ages, jobs and temperaments are fixed by the house), and they keep a day: out in
 * the streets and at work in the day, walking home in the evening, asleep in their own beds at
 * night, out of the door again in the morning. They only exist near players; walk away and they
 * are put away, walk back and they are where their day says they should be.
 */
public final class Residents {
    public static final String TAG = "aot_res";

    public record Member(int index, String first, boolean female, int age, String job, String trait) {
        /** 0 child, 1 adult, 2 elder. */
        public boolean child() { return age == 0; }
    }

    public record Family(int house, String surname, String town, List<Member> members) { }

    private static final String[] MEN = {"Hans", "Karl", "Otto", "Emil", "Franz", "Jurgen", "Dieter", "Ernst", "Walter", "Anton", "Fritz",
        "Lukas", "Paul", "Konrad", "Max", "Rolf", "Kurt", "Georg", "Bruno", "Moritz", "Tobias", "Wilhelm", "Stefan", "Felix", "August",
        "Heinrich", "Ludwig", "Gustav", "Oskar", "Albert", "Johann", "Peter", "Klaus", "Werner", "Horst", "Gerhard", "Rudolf", "Arno"};
    private static final String[] WOMEN = {"Greta", "Anna", "Marta", "Ilse", "Frieda", "Hanna", "Elsa", "Clara", "Lena", "Rosa", "Berta",
        "Emma", "Liesel", "Paula", "Irma", "Heidi", "Katrin", "Johanna", "Ruth", "Sophie", "Leni", "Margit", "Helga", "Nina", "Agnes",
        "Dora", "Erika", "Gisela", "Hedwig", "Luise", "Mathilde", "Olga", "Resi", "Trude", "Ursula", "Wilma", "Edith", "Klara"};
    private static final String[] SURNAMES = {"Braun", "Fischer", "Schneider", "Weber", "Wagner", "Becker", "Hoffmann", "Koch", "Richter",
        "Klein", "Wolf", "Schroder", "Neumann", "Schwarz", "Zimmermann", "Kruger", "Hartmann", "Lange", "Werner", "Krause", "Lehmann",
        "Kohler", "Maier", "Kaiser", "Fuchs", "Vogel", "Jung", "Hahn", "Keller", "Roth", "Frank", "Berger", "Winkler", "Lorenz",
        "Baumann", "Albrecht", "Kuhn", "Busch", "Pohl", "Engel", "Horn", "Sauer", "Ernst", "Brandt", "Haas", "Graf", "Dietrich", "Stein"};
    public static final String[] JOBS = {"baker", "butcher", "blacksmith", "carpenter", "tailor", "farmer", "miller", "fisherman", "brewer",
        "washer", "clerk", "merchant", "cobbler", "candlemaker", "stonemason", "Garrison soldier", "nurse", "teacher", "stablehand",
        "innkeeper", "cooper", "potter", "weaver", "cook", "lamplighter", "porter", "herbalist", "rope maker"};
    public static final String[] TRAITS = {"cheerful", "grumpy", "nervous", "gossip", "pious", "bitter", "dreamer", "proud", "kind", "tired"};

    private final Map<Integer, Family> families = new HashMap<>();
    private final Map<Integer, List<BlockPos>> beds = new HashMap<>();
    /** Residents already put somewhere inside for the night (or the day): left there, not moved again. */
    private final java.util.Set<UUID> settled = new java.util.HashSet<>();
    private final Random rng = new Random();

    /** The family in town house i, or null if it stands empty (the same answer every time). */
    public Family family(int i) {
        if (i < 0 || i >= AotRpg.PLACES.homes.size()) return null;
        return families.computeIfAbsent(i, k -> make(k));
    }

    private Family make(int i) {
        Random r = new Random(i * 1_000_003L + 77);
        if (r.nextFloat() > 0.62f) return null;
        int[] h = AotRpg.PLACES.homes.get(i);
        Net.Area t = AotRpg.PLACES.nearest((h[0] + h[2]) / 2.0, (h[1] + h[3]) / 2.0, 400, "town", "village", "city", "capital", "safe");
        String town = t == null ? "town" : t.name();
        String sur = SURNAMES[r.nextInt(SURNAMES.length)];
        List<Member> m = new ArrayList<>();
        int kind = r.nextInt(100);
        // Couples with children, a lone adult, a couple, a widow(er) with children, three generations.
        if (kind < 15) {
            m.add(adult(r, m.size(), r.nextBoolean()));
        } else {
            boolean widow = kind >= 65 && kind < 75;
            m.add(adult(r, m.size(), false));
            if (!widow) m.add(adult(r, m.size(), true));
            else m.set(0, adult(r, 0, r.nextBoolean()));
            int kids = kind < 30 ? 0 : 1 + r.nextInt(3);
            for (int k = 0; k < kids; k++) {
                boolean f = r.nextBoolean();
                m.add(new Member(m.size(), name(r, f, m), f, 0, "", TRAITS[r.nextInt(TRAITS.length)]));
            }
            if (kind >= 88) {
                boolean f = r.nextBoolean();
                m.add(new Member(m.size(), name(r, f, m), f, 2, r.nextInt(3) == 0 ? "retired " + JOBS[r.nextInt(JOBS.length)] : "retired",
                    TRAITS[r.nextInt(TRAITS.length)]));
            }
        }
        return new Family(i, sur, town, m);
    }

    private static Member adult(Random r, int idx, boolean female) {
        return new Member(idx, name(r, female, List.of()), female, 1, JOBS[r.nextInt(JOBS.length)], TRAITS[r.nextInt(TRAITS.length)]);
    }

    private static String name(Random r, boolean female, List<Member> taken) {
        String[] pool = female ? WOMEN : MEN;
        for (int t = 0; t < 8; t++) {
            String n = pool[r.nextInt(pool.length)];
            boolean dup = false;
            for (Member x : taken) if (x.first().equals(n)) dup = true;
            if (!dup) return n;
        }
        return pool[r.nextInt(pool.length)];
    }

    /** Which house and member an entity is, or null for anyone else. */
    public static int[] who(Entity e) {
        for (String t : e.getCommandTags()) {
            if (!t.startsWith(TAG + ":")) continue;
            String[] a = t.split(":");
            try {
                return new int[] {Integer.parseInt(a[1]), Integer.parseInt(a[2])};
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    public Member member(Entity e) {
        int[] w = who(e);
        if (w == null) return null;
        Family f = family(w[0]);
        return f == null || w[1] >= f.members().size() ? null : f.members().get(w[1]);
    }

    public Family familyOf(Entity e) {
        int[] w = who(e);
        return w == null ? null : family(w[0]);
    }

    /** A fixed id for each resident, with the townsfolk look that matches their sex. */
    private static UUID id(int house, Member m) {
        UUID u = UUID.nameUUIDFromBytes(("aot_res:" + house + ":" + m.index()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long lsb = u.getLeastSignificantBits(), msb = u.getMostSignificantBits();
        boolean fem = ((lsb ^ msb) & 1) == 1;
        if (fem != m.female()) lsb ^= 1;
        return new UUID(msb, lsb);
    }

    // ------------------------------------------------------------------ the day

    /** Where in their day it is: 0 asleep, 1 waking, 2 out, 3 heading home. */
    static int phase(ServerWorld w) {
        long t = w.getTimeOfDay() % 24000;
        if (t >= 13000 && t < 23000) return 0;
        if (t >= 23000 || t < 600) return 1;
        if (t >= 11600) return 3;
        return 2;
    }

    public void tick(ServerWorld w, int ticks) {
        if (ticks % 20 != 9 || AotRpg.PLACES.homes.isEmpty()) return;
        int phase = phase(w);
        List<ServerPlayerEntity> players = new ArrayList<>(w.getPlayers());
        // Near players: every family where its day says, and all of them at once (no pop-in mid-street).
        java.util.Set<Integer> near = new java.util.HashSet<>();
        for (ServerPlayerEntity p : players) {
            for (int i = 0; i < AotRpg.PLACES.homes.size(); i++) {
                int[] h = AotRpg.PLACES.homes.get(i);
                double dx = p.getX() - (h[0] + h[2]) / 2.0, dz = p.getZ() - (h[1] + h[3]) / 2.0;
                if (dx * dx + dz * dz < 72 * 72) near.add(i);
            }
        }
        int budget = 6;
        for (int i : near) {
            Family f = family(i);
            if (f == null) continue;
            int[] h = AotRpg.PLACES.homes.get(i);
            if (!w.isChunkLoaded(h[0] >> 4, h[1] >> 4)) continue;
            for (Member m : f.members()) {
                UUID id = id(i, m);
                Entity e = w.getEntity(id);
                if (e instanceof VillagerEntity v && v.isAlive()) {
                    keep(w, v, i, m, phase, players);
                } else if (budget > 0) {
                    budget--;
                    place(w, i, m, id, phase);
                }
            }
        }
        // Far from everyone: put away (they'll be back where they should be when someone returns).
        if (ticks % 100 == 9) {
            List<VillagerEntity> ours = new ArrayList<>();
            for (Entity e : w.iterateEntities()) if (e instanceof VillagerEntity v && v.getCommandTags().contains(TAG)) ours.add(v);
            for (VillagerEntity v : ours) {
                boolean close = false;
                for (ServerPlayerEntity p : players) if (p.squaredDistanceTo(v) < 110 * 110) close = true;
                if (!close) {
                    if (v.isSleeping()) v.wakeUp();
                    settled.remove(v.getUuid());
                    v.discard();
                }
            }
        }
    }

    /** Someone already about: keep them to their day. */
    private void keep(ServerWorld w, VillagerEntity v, int house, Member m, int phase, List<ServerPlayerEntity> players) {
        int[] h = AotRpg.PLACES.homes.get(house);
        boolean home = inside(h, v.getBlockPos());
        switch (phase) {
            case 0 -> {
                // Night: in bed. Out in the street still: on the way home, or slipped in unseen.
                if (!home) {
                    settled.remove(v.getUuid());
                    if (unseen(v, players, 20)) toBed(w, v, house, m);
                    else AotRpg.FOLK.sendHome(v, door(h));
                } else if (!v.isSleeping() && !settled.contains(v.getUuid())) toBed(w, v, house, m);
            }
            case 1, 2 -> {
                // Morning and day: up, and out of the door (the old and the smallest mostly stay in).
                if (v.isSleeping()) {
                    v.wakeUp();
                    settled.remove(v.getUuid());
                }
                // One at a time out of the door, a little apart, and off to the street.
                if (home && goesOut(m, w) && unseen(v, players, 6) && (w.getTime() / 20 + m.index() * 3) % 6 == 0) {
                    settled.remove(v.getUuid());
                    BlockPos d = door(h);
                    v.refreshPositionAndAngles(d.getX() + 0.5, d.getY(), d.getZ() + 0.5, v.getYaw(), 0);
                    AotRpg.FOLK.sendOut(v, d);
                }
            }
            case 3 -> {
                if (!home) AotRpg.FOLK.sendHome(v, door(h));
            }
            default -> { }
        }
    }

    /** Most adults go out by day; elders and small children often stay home. Fixed per person and day. */
    private static boolean goesOut(Member m, ServerWorld w) {
        long day = w.getTimeOfDay() / 24000;
        long h = (m.first().hashCode() * 31L + day) & 0xff;
        return m.age() == 1 ? h < 220 : h < 90;
    }

    private void place(ServerWorld w, int house, Member m, UUID id, int phase) {
        int[] h = AotRpg.PLACES.homes.get(house);
        VillagerEntity v = EntityType.VILLAGER.create(w);
        if (v == null) return;
        v.setUuid(id);
        BlockPos d = door(h);
        v.refreshPositionAndAngles(d.getX() + 0.5, d.getY(), d.getZ() + 0.5, rng.nextFloat() * 360, 0);
        v.initialize(w, w.getLocalDifficulty(d), SpawnReason.EVENT, null);
        v.setVillagerData(v.getVillagerData().withProfession(VillagerProfession.NONE));
        v.setSilent(true);
        v.setBaby(m.child());
        v.setBreedingAge(m.child() ? -Integer.MAX_VALUE / 2 : 0);
        v.addCommandTag(TAG);
        v.addCommandTag(TAG + ":" + house + ":" + m.index());
        v.setCustomName(Text.literal(m.first()));
        v.setAiDisabled(true);
        if (!w.spawnEntity(v)) return;
        if (phase == 0) toBed(w, v, house, m);
        else if ((phase == 1 || phase == 2) && goesOut(m, w)) {
            // Already out and about: somewhere along the street near home, not stacked at the door.
            AotRpg.FOLK.sendOut(v, d);
        }
        else indoors(w, v, house);
    }

    /** Into their own bed (or somewhere inside if the house is short of beds), asleep. */
    private void toBed(ServerWorld w, VillagerEntity v, int house, Member m) {
        AotRpg.FOLK.release(v);
        List<BlockPos> bs = beds(w, house);
        if (bs.isEmpty() || m.index() >= bs.size() * 2) {
            indoors(w, v, house);
            return;
        }
        BlockPos bed = bs.get(m.index() % bs.size());
        settled.add(v.getUuid());
        if (!v.isSleeping()) {
            v.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
            v.sleep(bed);
        }
    }

    /** Standing about inside: on the ground floor, somewhere clear (once: then left be). */
    private void indoors(ServerWorld w, VillagerEntity v, int house) {
        AotRpg.FOLK.release(v);
        if (v.isSleeping()) v.wakeUp();
        settled.add(v.getUuid());
        v.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
        int[] h = AotRpg.PLACES.homes.get(house);
        for (int t = 0; t < 20; t++) {
            int x = h[0] + 1 + rng.nextInt(Math.max(1, h[2] - h[0] - 1)), z = h[1] + 1 + rng.nextInt(Math.max(1, h[3] - h[1] - 1));
            BlockPos feet = new BlockPos(x, h[4] + 1, z);
            if (w.getBlockState(feet).getCollisionShape(w, feet).isEmpty() && w.getBlockState(feet.up()).getCollisionShape(w, feet.up()).isEmpty()) {
                v.refreshPositionAndAngles(x + 0.5, feet.getY(), z + 0.5, rng.nextFloat() * 360, 0);
                return;
            }
        }
    }

    /** The beds in a house (their head halves), found once. */
    private List<BlockPos> beds(ServerWorld w, int house) {
        return beds.computeIfAbsent(house, k -> {
            int[] h = AotRpg.PLACES.homes.get(k);
            List<BlockPos> out = new ArrayList<>();
            for (BlockPos p : BlockPos.iterate(h[0], h[4], h[1], h[2], h[5], h[3])) {
                BlockState s = w.getBlockState(p);
                if (s.getBlock() instanceof BedBlock && s.get(BedBlock.PART) == BedPart.HEAD) out.add(p.toImmutable());
            }
            return out;
        });
    }

    static boolean inside(int[] h, BlockPos p) {
        return p.getX() >= h[0] && p.getX() <= h[2] && p.getZ() >= h[1] && p.getZ() <= h[3] && p.getY() >= h[4] && p.getY() <= h[5];
    }

    /** The doorstep, standing height. */
    static BlockPos door(int[] h) {
        return new BlockPos(h[6], h[4] + 1, h[7]);
    }

    private static boolean unseen(Entity e, List<ServerPlayerEntity> players, double r) {
        for (ServerPlayerEntity p : players) if (p.squaredDistanceTo(e) < r * r) return false;
        return true;
    }

    /** They reached their door: in they go (to bed if it's late). */
    public void arrived(ServerWorld w, VillagerEntity v) {
        int[] who = who(v);
        if (who == null) return;
        Member m = member(v);
        if (m == null) return;
        if (phase(w) == 0 || phase(w) == 3) toBed(w, v, who[0], m);
        else indoors(w, v, who[0]);
    }
}
