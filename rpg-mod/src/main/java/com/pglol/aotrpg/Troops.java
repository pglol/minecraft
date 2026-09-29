package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.RaycastContext;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Marleyan troops on the islands: squads of riflemen with APG guns, sometimes led by an officer,
 * out for the same supplies you are. They patrol between points of interest and fight whatever
 * they meet: titans (which fight back, and now and then snatch one up and eat him) and divers.
 * Gunfire draws titans. A squad bleeding a titan dry is a chance to steal the kill; a squad that
 * has spotted you will pin you down, and a moving target on its gear is far harder to hit.
 * They carry ammunition, gas and blades, and officers carry real gear.
 */
public final class Troops {
    private Troops() {}

    public static final String TAG = "aot_troop", OFFICER = "aot_troop_officer";

    private static final class Troop {
        final UUID id;
        final int squad;
        final boolean officer;
        /** 0 leads, 1 rifleman (holds and fires), 2 flanker (works round the side). */
        final int role, slot;
        UUID target;
        int burst, cool, retarget, strafe = 1, strafeFlip, flee, grabbed = -1;
        /** A volley being aimed (ticks left), and where it will go: dodge it by moving. */
        int windup;
        Vec3d aimAt;
        /** Up close with a blade: a swing winding up (ticks left), and staggered after a parry. */
        boolean blade;
        int swing, stun;
        UUID grabber;
        Vec3d grabFrom;
        boolean spotted;
        /** The squad's orders: 0 free, 1 draw the titan off, 2 work round to its nape, 3 on a person. */
        int job;
        UUID orders;
        /** Chained cuts, and which blade cuts next. */
        int combo;
        boolean offhand;
        /** Reading your rhythm: guard up (ticks), hits taken on it, a sidestep (ticks, direction), the last hit. */
        int guard, guardHits, hitsInRow, dodge;
        Vec3d dodgeDir;
        long lastHit;
        /** Knocked back by a hit (direction and strength, ticks left), and a pause between weapon swaps. */
        Vec3d knock;
        int knockT, swapCool;
        /** The current cut's wind-up has been shown to onlookers. */
        boolean swingShown;

        Troop(UUID id, int squad, boolean officer, int role, int slot) {
            this.id = id;
            this.squad = squad;
            this.officer = officer;
            this.role = role;
            this.slot = slot;
        }
    }

    private static final class Squad {
        final int id;
        final String session;
        final BlockPos centre;
        final int radius, level;
        BlockPos goal;
        int regoal;
        UUID leader;
        /** Volleys go in turns: the next time anyone in the squad may start one. */
        int nextVolley;
        /** Their officer is down: shakier aim, and some of them run. */
        boolean broken;
        /** Where the squad stands (the middle of it), and when the next shots at each target may start. */
        Vec3d mid;
        int plan;
        final Map<UUID, Integer> volleys = new HashMap<>();

        Squad(int id, String session, BlockPos centre, int radius, int level) {
            this.id = id;
            this.session = session;
            this.centre = centre;
            this.radius = radius;
            this.level = level;
        }
    }

    private static final Map<UUID, Troop> troops = new HashMap<>();
    /** Honor: who is fighting whom hand to hand (player -> troop); the rest wait their turn. */
    private static final Map<UUID, UUID> duels = new HashMap<>();
    /** A hand-to-hand fight just ended (player -> until when): the next man steps in. */
    private static final Map<UUID, Long> duelOpen = new HashMap<>();

    /** The troop fighting this player hand to hand right now, or null. */
    private static UUID duelist(ServerWorld w, ServerPlayerEntity p) {
        UUID id = duels.get(p.getUuid());
        if (id == null) return null;
        Troop t = troops.get(id);
        Entity e = w.getEntity(id);
        if (t == null || !(e instanceof VillagerEntity v) || !v.isAlive()) {
            // He fell: the next man may step in.
            duels.remove(p.getUuid());
            duelOpen.put(p.getUuid(), System.currentTimeMillis() + 8000);
            return null;
        }
        if (v.squaredDistanceTo(p) > 26 * 26) {
            // You got clear of him: the fight is off, but nobody swaps in for it.
            duels.remove(p.getUuid());
            return null;
        }
        return id;
    }
    private static final Map<Integer, Squad> squads = new HashMap<>();
    /** A spot a match's squads converge on (a supply drop), by session. */
    private static final Map<String, BlockPos> lures = new HashMap<>();
    private static int nextSquad;

    public static boolean is(Entity e) {
        return e != null && e.getCommandTags().contains(TAG);
    }

    /** A troop this server is running (one loaded back from before a restart is not). */
    public static boolean known(Entity e) {
        return troops.containsKey(e.getUuid());
    }

    /** Thrown off balance (a perfect parry): stock still for this many ticks, and open to punishment. */
    public static void stagger(Entity e, int ticks) {
        Troop t = troops.get(e.getUuid());
        if (t == null) return;
        t.stun = Math.max(t.stun, ticks);
        t.swing = 0;
        t.swingShown = false;
        if (e instanceof VillagerEntity sv) anim(sv, 0, 0, 0);
        t.windup = 0;
        t.burst = 0;
    }

    /** Staggered after a parried swing: open to a punishing hit. */
    public static boolean stunned(Entity e) {
        Troop t = troops.get(e.getUuid());
        return t != null && t.stun > 0;
    }

    public static void lure(String session, BlockPos at) {
        if (at == null) lures.remove(session);
        else lures.put(session, at);
    }

    /** How many troops a match has standing. */
    public static int count(String session) {
        int n = 0;
        for (Troop t : troops.values()) {
            Squad s = squads.get(t.squad);
            if (s != null && s.session.equals(session)) n++;
        }
        return n;
    }

    /** Drops a squad of n at a spot (an officer leads bigger ones). Returns how many came. */
    public static int squad(ServerWorld w, BlockPos at, int n, int level, String session, BlockPos centre, int radius, Random r) {
        Squad sq = new Squad(nextSquad++, session, centre, radius, level);
        squads.put(sq.id, sq);
        int made = 0;
        for (int i = 0; i < n; i++) {
            boolean officer = i == 0 && n >= 3 && r.nextFloat() < 0.6f;
            int x = at.getX() + r.nextInt(7) - 3, z = at.getZ() + r.nextInt(7) - 3;
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            VillagerEntity v = EntityType.VILLAGER.create(w);
            if (v == null) continue;
            v.refreshPositionAndAngles(x + 0.5, y, z + 0.5, r.nextFloat() * 360, 0);
            v.initialize(w, w.getLocalDifficulty(at), SpawnReason.EVENT, null);
            v.setVillagerData(v.getVillagerData().withProfession(VillagerProfession.NONE));
            v.setBaby(false);
            v.setAiDisabled(true);
            v.setSilent(true);
            v.setPersistent();
            v.addCommandTag(TAG);
            v.addCommandTag("aot_ses:" + session);
            if (officer) v.addCommandTag(OFFICER);
            v.setCustomName(Text.literal("Lv " + level + " ").formatted(Formatting.GRAY)
                .append(Text.literal(officer ? "Marleyan Officer" : "Marleyan Rifleman").formatted(officer ? Formatting.GOLD : Formatting.RED)));
            v.setCustomNameVisible(true);
            var hp = v.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
            if (hp != null) hp.setBaseValue((officer ? 90 : 45) + level * (officer ? 3.5 : 2.2));
            v.setHealth(v.getMaxHealth());
            Item gun = AotItems.exact("apg_gun");
            if (gun != null) v.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, new ItemStack(gun));
            v.setEquipmentDropChance(net.minecraft.entity.EquipmentSlot.MAINHAND, 0);
            // Known before it enters the world: the clean-up of leftover troops checks as it loads.
            int role = made == 0 ? 0 : made % 2 == 0 ? 2 : 1;
            troops.put(v.getUuid(), new Troop(v.getUuid(), sq.id, officer, role, made));
            if (w.spawnEntity(v)) {
                if (made == 0) sq.leader = v.getUuid();
                made++;
            } else {
                troops.remove(v.getUuid());
            }
        }
        return made;
    }

    /** A match is over: its troops go. */
    public static void clear(ServerWorld w, String session) {
        for (var it = troops.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Squad s = squads.get(e.getValue().squad);
            if (s != null && !s.session.equals(session)) continue;
            Entity ent = w.getEntity(e.getKey());
            if (ent != null) ent.discard();
            it.remove();
        }
        squads.values().removeIf(s -> s.session.equals(session));
        lures.remove(session);
    }

    // ------------------------------------------------------------------ the fight

    public static void tick(ServerWorld w, int ticks) {
        if (troops.isEmpty()) return;
        List<ServerPlayerEntity> players = new ArrayList<>(w.getPlayers());
        for (Squad sq : squads.values()) {
            if (--sq.regoal <= 0 || sq.goal == null) {
                sq.regoal = 400 + w.random.nextInt(400);
                BlockPos lure = lures.get(sq.session);
                if (lure != null && lure.getSquaredDistance(sq.centre) < (double) sq.radius * sq.radius * 4) sq.goal = lure;
                else {
                    double a = w.random.nextDouble() * Math.PI * 2, d = w.random.nextDouble() * sq.radius * 0.85;
                    sq.goal = sq.centre.add((int) (Math.cos(a) * d), 0, (int) (Math.sin(a) * d));
                }
            }
        }
        if (ticks % 20 == 7) for (Squad sq : new ArrayList<>(squads.values())) plan(w, sq, players);
        // A copy: a troop dying mid-loop (eaten, cut down) takes himself off the list.
        for (var en : new ArrayList<>(troops.entrySet())) {
            Troop t = en.getValue();
            if (troops.get(en.getKey()) != t) continue;
            Entity ent = w.getEntity(en.getKey());
            if (!(ent instanceof VillagerEntity v) || !v.isAlive()) {
                if (ent != null && !ent.isAlive()) troops.remove(en.getKey(), t);
                continue;
            }
            // Asleep when nobody is near (the island is big; they wait where they are).
            ServerPlayerEntity near = null;
            double nd = Double.MAX_VALUE;
            for (ServerPlayerEntity p : players) {
                double d = p.squaredDistanceTo(v);
                if (d < nd) {
                    nd = d;
                    near = p;
                }
            }
            if (near == null || nd > 300 * 300) continue;
            Squad sq = squads.get(t.squad);
            if (sq == null) continue;
            if (t.grabbed >= 0) {
                eaten(w, v, t);
                continue;
            }
            think(w, v, t, sq, players, ticks);
        }
        if (ticks % 2 == 0) sense(w, players);
    }

    /** Players who were last sent threats (so the arcs clear once nobody has them marked). */
    private static final java.util.Set<UUID> sensed = new java.util.HashSet<>();

    /** Each player's sixth sense: every troop (and titan) with them marked, and how close to striking. */
    private static void sense(ServerWorld w, List<ServerPlayerEntity> players) {
        for (ServerPlayerEntity p : players) {
            if (!ServerPlayNetworking.canSend(p, Net.Threats.ID)) continue;
            List<double[]> found = new ArrayList<>();
            for (Troop t : troops.values()) {
                if (!p.getUuid().equals(t.target) || t.grabbed >= 0) continue;
                if (!(w.getEntity(t.id) instanceof VillagerEntity v) || !v.isAlive() || v.squaredDistanceTo(p) > 64 * 64) continue;
                int lvl = t.swing > 0 && t.swing <= 4 || t.windup > 0 && t.windup <= 5 || t.burst > 0 ? 3
                    : t.swing > 0 || t.windup > 0 || t.blade && v.squaredDistanceTo(p) < 5 * 5 && t.cool <= 3 ? 2 : 1;
                // The honor ring only watches.
                if (!t.blade && !t.id.equals(duels.get(p.getUuid())) && duels.containsKey(p.getUuid()) && t.windup == 0 && t.burst == 0) lvl = 1;
                found.add(new double[] {v.getX(), v.getY() + 1, v.getZ(), lvl});
                if (found.size() >= 24) break;
            }
            for (Entity e : w.getOtherEntities(p, p.getBoundingBox().expand(48, 30, 48), e -> e instanceof MobEntity m && AotRpg.isTitan(e) && m.getTarget() == p)) {
                double reach = e.getWidth() * 0.6 + 4;
                found.add(new double[] {e.getX(), e.getY() + e.getHeight() * 0.5, e.getZ(), e.squaredDistanceTo(p) < reach * reach ? 3 : 2});
                if (found.size() >= 30) break;
            }
            if (found.isEmpty() && !sensed.remove(p.getUuid())) continue;
            if (!found.isEmpty()) sensed.add(p.getUuid());
            int n = found.size();
            double[] x = new double[n], y = new double[n], z = new double[n];
            byte[] l = new byte[n];
            for (int i = 0; i < n; i++) {
                double[] f = found.get(i);
                x[i] = f[0];
                y[i] = f[1];
                z[i] = f[2];
                l[i] = (byte) f[3];
            }
            ServerPlayNetworking.send(p, new Net.Threats(x, y, z, l));
        }
    }

    private static boolean sees(ServerWorld w, Entity from, Vec3d at) {
        Vec3d eye = from.getEyePos();
        var hit = w.raycast(new RaycastContext(eye, at, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, from));
        return hit.getType() == HitResult.Type.MISS || hit.getPos().squaredDistanceTo(at) < 1.5;
    }

    /** Where to aim on a target: a titan's chest, a person's middle. */
    private static Vec3d aim(LivingEntity e) {
        double h = e.getHeight();
        return e.getPos().add(0, AotRpg.isTitan(e) ? h * 0.7 : h * 0.6, 0);
    }

    private static LivingEntity find(ServerWorld w, UUID id) {
        return id != null && w.getEntity(id) instanceof LivingEntity le && le.isAlive() ? le : null;
    }

    // ------------------------------------------------------------------ squad tactics

    /**
     * Once a second, the squad reads the field and gives orders. A titan and people both near: a
     * few peel off to draw the titan away (keeping their distance, shooting to hold its eye) while
     * the rest press the people. A titan alone: one draws it, the rest work round behind to its
     * nape. People alone: fire is split between them, and anyone hurt badly gets the whole squad.
     */
    private static void plan(ServerWorld w, Squad sq, List<ServerPlayerEntity> players) {
        List<Troop> men = new ArrayList<>();
        List<VillagerEntity> bodies = new ArrayList<>();
        for (Troop t : troops.values()) {
            if (t.squad != sq.id || t.grabbed >= 0) continue;
            if (w.getEntity(t.id) instanceof VillagerEntity v && v.isAlive()) {
                men.add(t);
                bodies.add(v);
            }
        }
        if (men.isEmpty()) {
            sq.mid = null;
            return;
        }
        Vec3d mid = Vec3d.ZERO;
        for (VillagerEntity v : bodies) mid = mid.add(v.getPos());
        mid = mid.multiply(1.0 / bodies.size());
        sq.mid = mid;
        final Vec3d m = mid;
        List<ServerPlayerEntity> people = new ArrayList<>();
        for (ServerPlayerEntity p : players) {
            if (p.isSpectator() || p.isCreative() || AotRpg.DOWNED.isDowned(p)) continue;
            if (p.squaredDistanceTo(m) < 42 * 42) people.add(p);
        }
        people.sort((a, b) -> Double.compare(a.squaredDistanceTo(m), b.squaredDistanceTo(m)));
        LivingEntity titan = null;
        double td = 50 * 50;
        for (Entity e : w.getOtherEntities(null, new Box(m, m).expand(50, 30, 50), e -> AotRpg.isTitan(e) && e.isAlive() && TitanLevels.rootOf(e) == e)) {
            double d = e.squaredDistanceTo(m);
            if (d < td) {
                td = d;
                titan = (LivingEntity) e;
            }
        }
        int plan = titan != null && !people.isEmpty() ? 3 : titan != null ? 2 : !people.isEmpty() ? 1 : 0;
        if (plan != sq.plan && plan >= 2 && men.size() > 1) {
            // The call to split up: a shout down the line.
            Entity lead = sq.leader == null ? bodies.get(0) : w.getEntity(sq.leader);
            if (lead != null) w.playSound(null, lead.getX(), lead.getY() + 1.6, lead.getZ(), SoundEvents.ENTITY_PILLAGER_AMBIENT, SoundCategory.HOSTILE, 2f, 0.7f);
        }
        sq.plan = plan;
        for (Troop t : men) {
            t.job = 0;
            t.orders = null;
        }
        if (plan == 0) return;
        // Who's hurt worst among the people: finish them.
        ServerPlayerEntity weak = null;
        for (ServerPlayerEntity p : people) if (p.getHealth() < p.getMaxHealth() * 0.4f && (weak == null || p.getHealth() < weak.getHealth())) weak = p;
        int baiters = 0;
        if (titan != null) {
            // The ones nearest the titan draw it (never the whole squad when people are about).
            final LivingEntity ti = titan;
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < men.size(); i++) order.add(i);
            order.sort((a, b) -> Double.compare(bodies.get(a).squaredDistanceTo(ti), bodies.get(b).squaredDistanceTo(ti)));
            int n = plan == 3 ? Math.max(1, Math.round(men.size() * 0.35f)) : men.size() > 1 ? 1 : men.size();
            if (plan == 3 && men.size() > 1) n = Math.min(n, men.size() - 1);
            for (int k = 0; k < order.size(); k++) {
                Troop t = men.get(order.get(k));
                if (k < n) {
                    t.job = 1;
                    t.orders = ti.getUuid();
                    baiters++;
                } else if (plan == 2) {
                    t.job = 2;
                    t.orders = ti.getUuid();
                }
            }
        }
        if (people.isEmpty()) return;
        int k = 0;
        for (Troop t : men) {
            if (t.job != 0) continue;
            t.job = 3;
            // Split fire between people, but everyone turns on someone about to fall.
            ServerPlayerEntity p = weak != null && k % 3 != 2 ? weak : people.get(k % Math.min(people.size(), Math.max(1, (men.size() - baiters + 1) / 2)));
            t.orders = p.getUuid();
            k++;
        }
    }

    // ------------------------------------------------------------------ one man

    private static boolean valid(LivingEntity e) {
        return e != null && e.isAlive() && !(e instanceof ServerPlayerEntity sp && (sp.isSpectator() || sp.isCreative() || AotRpg.DOWNED.isDowned(sp)));
    }

    private static void think(ServerWorld w, VillagerEntity v, Troop t, Squad sq, List<ServerPlayerEntity> players, int ticks) {
        if (t.swapCool > 0) t.swapCool--;
        if (t.knockT > 0 && t.knock != null) {
            // Rocked back by a hit, sliding to a stop.
            walk(w, v, t.knock, t.knock.length() * t.knockT / 6.0);
            t.knockT--;
            if (t.knockT >= 2) return;
        }
        if (t.stun > 0) {
            // Reeling from a parry: stock still, seeing stars.
            t.stun--;
            if (t.stun % 5 == 0) w.spawnParticles(ParticleTypes.CRIT, v.getX(), v.getY() + 2.1, v.getZ(), 3, 0.25, 0.05, 0.25, 0.02);
            return;
        }
        if (t.dodge > 0) {
            // A quick sidestep out of a flurry...
            t.dodge--;
            if (t.dodgeDir != null) walk(w, v, t.dodgeDir, 0.5);
            if (t.dodge == 0 && t.blade) {
                // ...and straight back in with a cut.
                t.swing = 3;
                t.cool = 0;
            }
            return;
        }
        LivingEntity target = find(w, t.target);
        if (!valid(target)) target = null;
        LivingEntity ordered = find(w, t.orders);
        if (!valid(ordered)) ordered = null;
        // Orders first; but whoever is cutting at them up close gets dealt with.
        if (ordered != null) target = ordered;
        if (v.getAttacker() instanceof LivingEntity att && valid(att) && att != target && v.getLastAttackedTime() > v.age - 10
            && (att instanceof ServerPlayerEntity && att.squaredDistanceTo(v) < 8 * 8 || AotRpg.isTitan(att) && t.job != 3)) target = att;
        if (target == null && sq.leader != null && !sq.leader.equals(t.id)) {
            Troop lead = troops.get(sq.leader);
            if (lead != null) {
                LivingEntity lt = find(w, lead.target);
                if (valid(lt)) target = lt;
            }
        }
        if (--t.retarget <= 0 || target == null) {
            t.retarget = 10;
            if (target == null) {
                LivingEntity best = null;
                double bd = Double.MAX_VALUE;
                for (Entity e : w.getOtherEntities(v, v.getBoundingBox().expand(44, 30, 44), e -> e instanceof LivingEntity le && le.isAlive())) {
                    LivingEntity le = (LivingEntity) e;
                    double d = le.squaredDistanceTo(v);
                    if (le instanceof ServerPlayerEntity p) {
                        if (!valid(p) || d > 40 * 40) continue;
                    } else if (AotRpg.isTitan(le) && TitanLevels.rootOf(le) == le) {
                        d *= 0.6;
                    } else continue;
                    if (d < bd && sees(w, v, aim(le))) {
                        bd = d;
                        best = le;
                    }
                }
                target = best;
            }
        }
        if (target instanceof ServerPlayerEntity sp && !t.spotted) {
            t.spotted = true;
            sp.playSoundToPlayer(SoundEvents.ENTITY_PILLAGER_CELEBRATE, SoundCategory.HOSTILE, 1f, 0.8f);
        }
        if (target == null || !target.getUuid().equals(t.target)) t.windup = 0;
        t.target = target == null ? null : target.getUuid();

        // Only a leaderless squad nearly dead on its feet falls back, and not for long.
        if (!t.officer && t.flee <= 0 && target != null && sq.broken && v.getHealth() < v.getMaxHealth() * 0.2f && w.random.nextInt(200) == 0) t.flee = 40;
        if (t.flee > 0) {
            t.flee--;
            t.windup = 0;
            if (target != null) walk(w, v, v.getPos().subtract(target.getPos()).multiply(1, 0, 1), 0.2);
            return;
        }

        if (target == null) {
            t.windup = 0;
            gun(v, t);
            patrol(w, v, t, sq);
            return;
        }

        double dist = Math.sqrt(v.squaredDistanceTo(target));
        boolean titan = AotRpg.isTitan(target);
        // Up close with a person on the ground: both blades out. They can't follow you into the air
        // (no gear of their own), so once you pull well away or take off, it's back to the gun.
        boolean airborne = target instanceof ServerPlayerEntity sp2 && !sp2.isOnGround()
            && sp2.getY() - w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, sp2.getBlockX(), sp2.getBlockZ()) > 2.5;
        if (!titan && target instanceof ServerPlayerEntity duelP) {
            // Honor: one blade at a time. While someone is fighting this person hand to hand, the
            // rest hold a ring and keep their guns up for when he takes to the air.
            UUID holder = duelist(w, duelP);
            if (holder != null && !holder.equals(t.id)) {
                t.windup = 0;
                t.burst = 0;
                gun(v, t);
                Vec3d flat0 = target.getPos().subtract(v.getPos()).multiply(1, 0, 1);
                if (dist < 8) walk(w, v, flat0.multiply(-1), 0.12);
                else if (dist > 12) walk(w, v, flat0, 0.16);
                else walk(w, v, new Vec3d(-flat0.z, 0, flat0.x).multiply(t.strafe), 0.05);
                face(v, target.getEyePos());
                return;
            }
            boolean open = duelOpen.getOrDefault(duelP.getUuid(), 0L) > System.currentTimeMillis();
            if (!airborne && (dist < 16 || t.blade && dist < 22 || open && dist < 20)) {
                if (holder == null) {
                    duels.put(duelP.getUuid(), t.id);
                    duelOpen.remove(duelP.getUuid());
                    w.playSound(null, v.getX(), v.getY() + 1, v.getZ(), SoundEvents.ENTITY_PILLAGER_CELEBRATE, SoundCategory.HOSTILE, 1f, 0.9f);
                }
                blade(v, t);
            } else if (dist > 22 || airborne) {
                if (t.blade) duels.remove(duelP.getUuid(), t.id);
                gun(v, t);
            }
        } else if (titan) gun(v, t);
        if (t.blade) {
            melee(w, v, t, target, dist, sq);
            return;
        }

        Vec3d at = aim(target);
        Vec3d flat = target.getPos().subtract(v.getPos()).multiply(1, 0, 1);
        if (!titan && !airborne && dist < 32) {
            // A person on foot is a blade fight: close in (the gun is for when they take to the air).
            t.windup = 0;
            t.burst = 0;
            face(v, target.getEyePos());
            walk(w, v, flat, 0.22);
            return;
        }
        Vec3d move;
        double speed;
        if (titan && t.job == 2) {
            // Round the back of it, shooting for the nape.
            double yaw = Math.toRadians(target.getYaw());
            Vec3d back = new Vec3d(Math.sin(yaw), 0, -Math.cos(yaw));
            at = nape(target);
            Vec3d spot = target.getPos().add(back.multiply(target.getWidth() * 0.5 + Math.max(9, target.getHeight() * 0.8)));
            move = spot.subtract(v.getPos()).multiply(1, 0, 1);
            move = move.lengthSquared() < 4 ? Vec3d.ZERO : move.normalize();
            speed = 0.2;
        } else if (titan) {
            // Drawing it off: keep just out of its reach, backing away from the rest of the squad.
            double want = Math.max(13, target.getWidth() * 0.6 + target.getHeight() * 0.7);
            Vec3d off = sq.mid == null || t.job != 1 ? Vec3d.ZERO : v.getPos().subtract(sq.mid).multiply(1, 0, 1);
            off = off.lengthSquared() < 1e-3 ? new Vec3d(-flat.z, 0, flat.x) : off;
            move = off.normalize().multiply(0.5);
            if (dist < want) move = move.add(flat.normalize().multiply(-1.2));
            else if (dist > want + 8) move = move.add(flat.normalize());
            speed = dist < want ? 0.22 : 0.14;
        } else if (t.role == 2) {
            // A spot off to the person's side (left or right by slot), closing to about 12 blocks.
            double base = Math.atan2(v.getZ() - target.getZ(), v.getX() - target.getX());
            double ang = base + (t.slot % 4 < 2 ? 1 : -1) * 0.45;
            Vec3d spot = target.getPos().add(Math.cos(ang) * 12, 0, Math.sin(ang) * 12);
            move = spot.subtract(v.getPos()).multiply(1, 0, 1);
            move = move.lengthSquared() < 4 ? Vec3d.ZERO : move.normalize();
            speed = 0.15;
        } else {
            double want = t.role == 0 ? 18 : 14;
            Vec3d side = new Vec3d(-flat.z, 0, flat.x).normalize().multiply(t.strafe);
            if (--t.strafeFlip <= 0) {
                t.strafeFlip = 25 + w.random.nextInt(35);
                t.strafe = -t.strafe;
            }
            move = side.multiply(t.windup > 0 ? 0.2 : 0.5);
            if (dist > want + 5) move = move.add(flat.normalize());
            else if (dist < want - 6) move = move.add(flat.normalize().multiply(-0.6));
            speed = t.windup > 0 ? 0.07 : 0.13;
        }
        walk(w, v, move, speed);

        if (t.windup > 0) {
            // Taking aim: the red line tracks you (leading where you're going), then locks for a
            // moment before the burst. Break your line late and it misses.
            t.windup--;
            if (t.windup > 4) {
                Vec3d lead = at.add(target.getVelocity().multiply(titan ? 0 : 7, 0.3, titan ? 0 : 7));
                t.aimAt = t.aimAt == null ? lead : t.aimAt.lerp(lead, t.officer ? 0.55 : 0.4);
            } else if (t.windup == 4 && target instanceof ServerPlayerEntity p) {
                p.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.HOSTILE, 1f, 1.8f);
            }
            face(v, t.aimAt);
            if (ticks % 2 == 0) laser(w, v, t.aimAt);
            if (t.windup == 0) {
                t.burst = titan ? 4 : t.officer ? 3 : 2;
                t.cool = 0;
            }
            return;
        }
        face(v, at);
        if (t.burst > 0) {
            if (ticks % 3 == 0) {
                // Each round walks a little further onto where you are now.
                if (t.aimAt != null) t.aimAt = t.aimAt.lerp(at.add(target.getVelocity().multiply(3, 0, 3)), 0.25);
                fire(w, v, t, target, t.aimAt, dist, sq);
                if (--t.burst == 0) t.cool = (titan ? 25 : 55) + w.random.nextInt(30);
            }
            return;
        }
        if (t.cool > 0) {
            t.cool--;
            return;
        }
        if (!sees(w, v, at)) return;
        // Volleys at one target come in turns across the squad (a rhythm you can learn and rush
        // between); split onto different targets, they fire at once.
        int next = sq.volleys.getOrDefault(target.getUuid(), 0);
        if (ticks < next) return;
        sq.volleys.put(target.getUuid(), ticks + (titan ? 10 : 30));
        if (sq.volleys.size() > 16) sq.volleys.values().removeIf(x -> x < ticks);
        t.windup = titan ? 8 : t.officer ? 13 : 16;
        t.aimAt = at;
        anim(v, 4, t.windup + (titan ? 4 : t.officer ? 3 : 2) * 3 + 4, 0);
        if (target instanceof ServerPlayerEntity p) p.playSoundToPlayer(SoundEvents.ITEM_CROSSBOW_LOADING_MIDDLE.value(), SoundCategory.HOSTILE, 1f, 1.4f);
    }

    /** A titan's weak spot: high on the back of the neck. */
    private static Vec3d nape(LivingEntity ti) {
        double yaw = Math.toRadians(ti.getYaw());
        return ti.getPos().add(Math.sin(yaw) * ti.getWidth() * 0.25, ti.getHeight() * 0.88, -Math.cos(yaw) * ti.getWidth() * 0.25);
    }

    /** Walking the squad's patrol in a wedge behind whoever leads. */
    private static void patrol(ServerWorld w, VillagerEntity v, Troop t, Squad sq) {
        Entity lead = sq.leader == null ? null : w.getEntity(sq.leader);
        if (t.role == 0 || !(lead instanceof VillagerEntity lv) || !lv.isAlive()) {
            if (sq.goal == null) return;
            Vec3d to = Vec3d.ofBottomCenter(sq.goal).subtract(v.getPos()).multiply(1, 0, 1);
            if (to.lengthSquared() > 9) walk(w, v, to, 0.1);
            return;
        }
        double yaw = Math.toRadians(lv.getYaw());
        Vec3d fwd = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw)), right = new Vec3d(-fwd.z, 0, fwd.x);
        int row = (t.slot + 1) / 2, side = t.slot % 2 == 0 ? 1 : -1;
        Vec3d spot = lv.getPos().add(right.multiply(side * 2.4 * row)).add(fwd.multiply(-2.4 * row));
        Vec3d to = spot.subtract(v.getPos()).multiply(1, 0, 1);
        double d = to.length();
        if (d > 0.6) walk(w, v, to, d > 5 ? 0.16 : 0.1);
        else v.setYaw(lv.getYaw());
    }

    private static void gun(VillagerEntity v, Troop t) {
        if (!t.blade || t.swapCool > 0) return;
        t.swapCool = 30;
        t.blade = false;
        t.swing = 0;
        t.swingShown = false;
        t.combo = 0;
        Item gun = AotItems.exact("apg_gun");
        v.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, gun != null ? new ItemStack(gun) : ItemStack.EMPTY);
        v.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    }

    private static void blade(VillagerEntity v, Troop t) {
        if (t.blade || t.swapCool > 0) return;
        t.swapCool = 30;
        t.blade = true;
        t.windup = 0;
        t.burst = 0;
        t.cool = 4;
        Squad sq0 = squads.get(t.squad);
        ItemStack grip = t.officer ? officerGrip(v.getRandom(), sq0 == null ? 1 : sq0.level) : loadedGrip();
        v.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, grip.copy());
        v.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND, grip.copy());
        v.setEquipmentDropChance(net.minecraft.entity.EquipmentSlot.OFFHAND, 0);
        v.getWorld().playSound(null, v.getX(), v.getY() + 1, v.getZ(), SoundEvents.ITEM_ARMOR_EQUIP_IRON.value(), SoundCategory.HOSTILE, 1f, 1.3f);
        v.getWorld().playSound(null, v.getX(), v.getY() + 1, v.getZ(), SoundEvents.ITEM_TRIDENT_RETURN, SoundCategory.HOSTILE, 0.8f, 1.9f);
    }

    /**
     * Blades up close, both hands: closes fast, a short wind-up you can read (a glint, a scrape),
     * then cuts that chain two or three deep, alternating hands. Guard one in time and they're
     * thrown off balance, wide open; step out of reach and it whiffs.
     */
    private static void melee(ServerWorld w, VillagerEntity v, Troop t, LivingEntity target, double dist, Squad sq) {
        face(v, target.getEyePos());
        Vec3d to = target.getPos().subtract(v.getPos()).multiply(1, 0, 1);
        if (t.guard > 0) {
            // Blades crossed in front: hits from the front glance off. Get round him, or wait it out.
            t.guard--;
            if (t.guard % 4 == 0) {
                Vec3d g = v.getEyePos().add(v.getRotationVec(1f).multiply(0.6)).add(0, -0.3, 0);
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, g.x, g.y, g.z, 2, 0.15, 0.15, 0.15, 0.02);
            }
            if (dist < 1.8) walk(w, v, to.multiply(-1), 0.08);
            if (t.guard == 0) {
                // The guard drops into a quick cut.
                t.swing = 3;
                t.cool = 0;
            }
            return;
        }
        if (t.swing > 0 && !t.swingShown) {
            anim(v, 1, t.swing, t.offhand ? 1 : 0);
            t.swingShown = true;
        }
        if (t.swing > 0) {
            t.swing--;
            Vec3d hand = v.getEyePos().add(v.getRotationVec(1f).multiply(0.7)).add(0, -0.4, 0);
            w.spawnParticles(ParticleTypes.ENCHANTED_HIT, hand.x, hand.y, hand.z, 2, 0.1, 0.1, 0.1, 0.05);
            if (dist > 2.2) walk(w, v, to, 0.12);
            if (t.swing > 0) return;
            v.swingHand(t.offhand ? net.minecraft.util.Hand.OFF_HAND : net.minecraft.util.Hand.MAIN_HAND);
            anim(v, 2, 5, t.offhand ? 1 : 0);
            t.swingShown = false;
            t.offhand = !t.offhand;
            w.playSound(null, v.getX(), v.getY() + 1, v.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 1f, 0.9f + t.combo * 0.12f);
            Vec3d look = v.getRotationVec(1f).multiply(1, 0, 1).normalize();
            boolean reach = dist < 3.7 && to.lengthSquared() > 1e-4 && look.dotProduct(to.normalize()) > 0.2;
            if (!reach) {
                t.combo = 0;
                t.cool = 8;
                return;
            }
            // (A guard raised in time turns it: a well-timed one throws them off balance, see Guard.)
            float dmg = 6 + sq.level * 0.22f + (t.officer ? 2.5f : 0) + t.combo * 1.5f;
            target.damage(w.getDamageSources().mobAttack(v), dmg);
            target.addVelocity(look.x * 0.35, 0.15, look.z * 0.35);
            target.velocityModified = true;
            if (t.stun > 0) {
                // Parried: the chain is broken.
                t.combo = 0;
                return;
            }
            if (t.combo < (t.officer ? 3 : 2) && w.random.nextFloat() < 0.6f) {
                // Straight into the next cut, from the other hand.
                t.combo++;
                t.swing = 4;
                w.playSound(null, v.getX(), v.getY() + 1, v.getZ(), SoundEvents.ITEM_TRIDENT_RETURN, SoundCategory.HOSTILE, 0.8f, 1.8f);
                return;
            }
            t.combo = 0;
            t.cool = t.officer ? 7 : 10;
            return;
        }
        if (t.cool > 0) {
            t.cool--;
            // Now and then the guard comes up on its own when you're in close.
            if (dist < 3.2 && w.random.nextInt(t.officer ? 30 : 45) == 0) {
                raise(w, v, t);
                return;
            }
            // Between cuts: stay on you, circling a little.
            if (dist > 2.4) walk(w, v, to, 0.2);
            else walk(w, v, new Vec3d(-to.z, 0, to.x).multiply(t.strafe), 0.06);
            return;
        }
        if (dist > 5) {
            walk(w, v, to, 0.26);
            return;
        }
        if (dist > 2.6) {
            // A lunge to close the gap, winding up as he comes.
            walk(w, v, to, 0.42);
        }
        // The tell: a glint and a scrape, then the cut.
        t.swing = t.officer ? 5 : 7;
        w.playSound(null, v.getX(), v.getY() + 1, v.getZ(), SoundEvents.ITEM_TRIDENT_RETURN, SoundCategory.HOSTILE, 1f, 1.6f);
    }

    /** Shows everyone nearby what his body is doing (see Net.TroopAnim). */
    private static void anim(VillagerEntity v, int kind, int ticks, int hand) {
        Net.TroopAnim a = new Net.TroopAnim(v.getId(), (byte) kind, (byte) Math.min(127, Math.max(0, ticks)), (byte) hand);
        for (ServerPlayerEntity o : PlayerLookup.tracking(v)) if (ServerPlayNetworking.canSend(o, Net.TroopAnim.ID)) ServerPlayNetworking.send(o, a);
    }

    private static void raise(ServerWorld w, VillagerEntity v, Troop t) {
        anim(v, 3, t.officer ? 24 : 16, 0);
        t.swingShown = false;
        t.guard = t.officer ? 24 : 16;
        t.guardHits = 0;
        t.swing = 0;
        t.combo = 0;
        w.playSound(null, v.getX(), v.getY() + 1, v.getZ(), SoundEvents.ITEM_ARMOR_EQUIP_CHAIN.value(), SoundCategory.HOSTILE, 1f, 1.5f);
    }

    /**
     * A player's blow landing on a troop: how much of it goes through. Mash and they read it: a
     * second quick hit sees the guard come up or a sidestep; keep hacking at the guard and they
     * turn it (you're thrown off balance, and the cut comes straight back). Well-timed hits, a
     * perfect parry, or getting round behind them is how you win.
     */
    /** Shot or hit from range: a jolt back from where it came. */
    public static void jolt(Entity e, ServerPlayerEntity by) {
        Troop t = troops.get(e.getUuid());
        if (t == null || t.grabbed >= 0) return;
        Vec3d away = e.getPos().subtract(by.getPos()).multiply(1, 0, 1);
        if (away.lengthSquared() < 1e-4) return;
        t.knock = away.normalize().multiply(0.5);
        t.knockT = 4;
    }

    public static double struck(Entity e, ServerPlayerEntity by) {
        Troop t = troops.get(e.getUuid());
        if (t == null || !(e instanceof VillagerEntity v) || !(v.getWorld() instanceof ServerWorld w) || t.grabbed >= 0) return 1;
        if (t.stun > 0) {
            // Reeling: every hit lands, and sends him staggering back.
            Vec3d away = v.getPos().subtract(by.getPos()).multiply(1, 0, 1);
            t.knock = away.lengthSquared() < 1e-4 ? Vec3d.ZERO : away.normalize().multiply(0.9);
            t.knockT = 6;
            return 1;
        }
        long now = w.getTime();
        boolean quick = now - t.lastHit < 14;
        t.lastHit = now;
        Vec3d toBy = by.getPos().subtract(v.getPos()).multiply(1, 0, 1);
        Vec3d look = v.getRotationVec(1f).multiply(1, 0, 1);
        boolean front = toBy.lengthSquared() < 1e-4 || look.lengthSquared() < 1e-4 || look.normalize().dotProduct(toBy.normalize()) > 0.25;
        if (t.dodge > 0) {
            // Already stepping out of it: a whiff.
            w.playSound(null, v.getX(), v.getY() + 1, v.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_NODAMAGE, SoundCategory.HOSTILE, 1f, 1.2f);
            return 0;
        }
        if (t.guard > 0 && front) {
            w.playSound(null, v.getX(), v.getY() + 1, v.getZ(), SoundEvents.BLOCK_ANVIL_PLACE, SoundCategory.HOSTILE, 0.6f, 1.8f);
            Vec3d g = v.getEyePos().add(look.multiply(0.6));
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, g.x, g.y - 0.3, g.z, 8, 0.2, 0.2, 0.2, 0.15);
            if (++t.guardHits >= 2 || quick) {
                // Turned: the flailing blade is knocked aside and the answer comes at once.
                t.guard = 0;
                t.swingShown = false;
                t.guardHits = 0;
                Vec3d push = toBy.lengthSquared() < 1e-4 ? look.multiply(-1) : toBy.normalize();
                by.addVelocity(push.x * 0.9, 0.25, push.z * 0.9);
                by.velocityModified = true;
                by.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.SLOWNESS, 25, 3, false, false));
                by.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.WEAKNESS, 25, 1, false, false));
                by.playSoundToPlayer(SoundEvents.ITEM_SHIELD_BREAK, SoundCategory.HOSTILE, 1f, 1.4f);
                w.playSound(null, v.getX(), v.getY() + 1, v.getZ(), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.HOSTILE, 0.7f, 1.6f);
                w.spawnParticles(ParticleTypes.CRIT, g.x, g.y, g.z, 14, 0.3, 0.3, 0.3, 0.3);
                t.swing = 2;
                t.combo = 1;
                t.cool = 0;
                return 0;
            }
            // Driven back a step even on his guard.
            t.knock = toBy.lengthSquared() < 1e-4 ? look.multiply(-0.2) : toBy.normalize().multiply(-0.2);
            t.knockT = 3;
            return 0.15;
        }
        t.hitsInRow = quick ? t.hitsInRow + 1 : 1;
        // A real hit rocks him back.
        t.knock = toBy.lengthSquared() < 1e-4 ? look.multiply(-0.8) : toBy.normalize().multiply(-0.8);
        t.knockT = 6;
        if (t.blade && t.hitsInRow >= 3) {
            // Pressed too hard: he plants his feet and shoves you off, and comes back cutting.
            t.hitsInRow = 0;
            t.knockT = 0;
            anim(v, 5, 6, 0);
            t.swingShown = false;
            Vec3d push = toBy.lengthSquared() < 1e-4 ? look.multiply(-1) : toBy.normalize();
            by.addVelocity(push.x * 1.1, 0.3, push.z * 1.1);
            by.velocityModified = true;
            by.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.SLOWNESS, 15, 2, false, false));
            w.playSound(null, v.getX(), v.getY() + 1, v.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.HOSTILE, 1.2f, 0.7f);
            w.playSound(null, v.getX(), v.getY() + 1, v.getZ(), SoundEvents.ENTITY_PILLAGER_HURT, SoundCategory.HOSTILE, 1f, 0.8f);
            w.spawnParticles(ParticleTypes.SWEEP_ATTACK, v.getX() + push.x, v.getY() + 1.1, v.getZ() + push.z, 1, 0, 0, 0, 0);
            t.swing = 4;
            t.combo = 0;
            t.cool = 0;
            return 0.6;
        }
        if (t.blade && t.hitsInRow >= 2 && t.swing == 0) {
            float r = w.random.nextFloat();
            if (r < (t.officer ? 0.7f : 0.5f)) raise(w, v, t);
            else if (r < 0.85f) {
                // Out to the side and back in.
                Vec3d side = new Vec3d(-toBy.z, 0, toBy.x);
                if (w.random.nextBoolean()) side = side.multiply(-1);
                t.dodgeDir = toBy.lengthSquared() < 1e-4 ? side : side.add(toBy.normalize().multiply(-0.4));
                t.dodge = 4;
                t.hitsInRow = 0;
                w.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_WEAK, SoundCategory.HOSTILE, 1f, 0.6f);
            }
        }
        // An officer mid-swing doesn't flinch; anyone else's cut is knocked back a little.
        if (!(t.officer && t.swing > 0) && t.swing > 0) t.swing = Math.min(t.swing + 2, 8);
        return 1;
    }

    // ------------------------------------------------------------------ loaded grips

    private static ItemStack gripTemplate = ItemStack.EMPTY;
    private static int gripRichness = -1;
    private static java.nio.file.Path gripFile;

    /** How much a grip carries beyond the bare item (a loaded one carries its blade data). Our own marks don't count. */
    private static int richness(ItemStack s) {
        int n = 0;
        var cd = s.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
        if (cd != null) for (String k : cd.copyNbt().getKeys()) if (!k.startsWith("aot_")) n += 2;
        for (var c : s.getComponents()) {
            var id = net.minecraft.registry.Registries.DATA_COMPONENT_TYPE.getId(c.type());
            if (id != null && !id.getNamespace().equals("minecraft")) n += 2;
        }
        return n;
    }

    private static <T> void copy(ItemStack to, net.minecraft.component.Component<T> c) {
        to.set(c.type(), c.value());
    }

    /**
     * Danny's grips show a blade only when one is loaded, and that lives on the item. The troops
     * copy a real loaded grip: the richest one seen in a player's hand is kept (and saved) as the
     * pattern, but only the AoT mod's own blade data comes across: a fresh, plain, unworn grip,
     * never the player's rarity, infusion, name or looks.
     */
    public static void learnGrip(net.minecraft.server.MinecraftServer server, ItemStack held) {
        Item g = AotItems.exact("blade");
        if (g == null || held.getItem() != g) return;
        int r = richness(held);
        if (r <= gripRichness) return;
        ItemStack s = new ItemStack(g);
        var cd = held.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
        if (cd != null) {
            var n = cd.copyNbt();
            for (String k : new ArrayList<>(n.getKeys())) {
                if (k.startsWith("aot_")) {
                    n.remove(k);
                    continue;
                }
                String lk = k.toLowerCase(java.util.Locale.ROOT);
                if (lk.contains("damage") && n.get(k) instanceof net.minecraft.nbt.AbstractNbtNumber) n.putInt(k, 0);
            }
            if (!n.isEmpty()) s.set(net.minecraft.component.DataComponentTypes.CUSTOM_DATA, net.minecraft.component.type.NbtComponent.of(n));
        }
        for (var c : held.getComponents()) {
            var id = net.minecraft.registry.Registries.DATA_COMPONENT_TYPE.getId(c.type());
            if (id != null && !id.getNamespace().equals("minecraft")) copy(s, c);
        }
        gripTemplate = s;
        gripRichness = r;
        try {
            if (gripFile == null) gripFile = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("aot_rpg").resolve("troop_grip2.dat");
            java.nio.file.Files.createDirectories(gripFile.getParent());
            var tag = new net.minecraft.nbt.NbtCompound();
            tag.put("grip", s.encode(server.getRegistryManager()));
            tag.putInt("rich", r);
            net.minecraft.nbt.NbtIo.writeCompressed(tag, gripFile);
        } catch (Exception ignored) {
        }
    }

    public static void loadGrip(net.minecraft.server.MinecraftServer server) {
        gripFile = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("aot_rpg").resolve("troop_grip2.dat");
        gripTemplate = ItemStack.EMPTY;
        gripRichness = -1;
        if (!java.nio.file.Files.exists(gripFile)) return;
        try {
            var tag = net.minecraft.nbt.NbtIo.readCompressed(gripFile, net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());
            gripTemplate = ItemStack.fromNbt(server.getRegistryManager(), tag.get("grip")).orElse(ItemStack.EMPTY);
            gripRichness = gripTemplate.isEmpty() ? -1 : tag.getInt("rich");
        } catch (Exception ignored) {
        }
    }

    /**
     * An officer's grip: the same loaded blade with a touch of Epic about it (nothing finer: the
     * showpiece blades belong to bosses and to players).
     */
    private static ItemStack officerGrip(Random r, int level) {
        ItemStack base = loadedGrip();
        Item g = AotItems.exact("blade");
        if (g == null || base.getItem() != g) return base;
        ItemStack s = Gear.make(r, g, Gear.Rarity.EPIC, Math.max(1, level), null);
        if (s.isEmpty()) return base;
        var cd = base.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
        if (cd != null) {
            var n = s.getOrDefault(net.minecraft.component.DataComponentTypes.CUSTOM_DATA, net.minecraft.component.type.NbtComponent.DEFAULT).copyNbt();
            var add = cd.copyNbt();
            for (String k : add.getKeys()) n.put(k, add.get(k).copy());
            s.set(net.minecraft.component.DataComponentTypes.CUSTOM_DATA, net.minecraft.component.type.NbtComponent.of(n));
        }
        for (var c : base.getComponents()) {
            var id = net.minecraft.registry.Registries.DATA_COMPONENT_TYPE.getId(c.type());
            if (id != null && !id.getNamespace().equals("minecraft")) copy(s, c);
        }
        return s;
    }

    /** A grip with its blade in. */
    public static ItemStack loadedGrip() {
        if (!gripTemplate.isEmpty()) return gripTemplate.copy();
        Item g = AotItems.exact("blade");
        return g != null ? new ItemStack(g) : new ItemStack(net.minecraft.item.Items.IRON_SWORD);
    }

    /** The aim line: a thin red trace from the muzzle toward where the volley is going. */
    private static void laser(ServerWorld w, VillagerEntity v, Vec3d to) {
        if (to == null) return;
        Vec3d from = v.getEyePos().add(0, -0.25, 0);
        Vec3d d = to.subtract(from);
        double len = Math.min(40, d.length());
        Vec3d step = d.normalize();
        var red = new net.minecraft.particle.DustParticleEffect(new org.joml.Vector3f(1f, 0.1f, 0.08f), 0.4f);
        for (double k = 1; k < len; k += 1.3) w.spawnParticles(red, from.x + step.x * k, from.y + step.y * k, from.z + step.z * k, 1, 0, 0, 0, 0);
    }

    private static void face(VillagerEntity v, Vec3d at) {
        Vec3d d = at.subtract(v.getEyePos());
        float yaw = (float) (MathHelper.atan2(d.z, d.x) * MathHelper.DEGREES_PER_RADIAN) - 90f;
        float pitch = (float) -(MathHelper.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * MathHelper.DEGREES_PER_RADIAN);
        v.setYaw(yaw);
        v.setBodyYaw(yaw);
        v.setHeadYaw(yaw);
        v.setPitch(pitch);
    }

    /** A step over the ground (never up more than a block, never into water). */
    private static void walk(ServerWorld w, VillagerEntity v, Vec3d dir, double speed) {
        if (dir.lengthSquared() < 1e-4) return;
        Vec3d d = dir.normalize();
        for (int k = 0; k < 5; k++) {
            double ang = new double[] {0, 0.6, -0.6, 1.2, -1.2}[k];
            double c = Math.cos(ang), s = Math.sin(ang);
            double dx = d.x * c - d.z * s, dz = d.x * s + d.z * c;
            double nx = v.getX() + dx * speed, nz = v.getZ() + dz * speed;
            int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, MathHelper.floor(nx), MathHelper.floor(nz));
            if (top - v.getY() > 1.2 || v.getY() - top > 3) continue;
            BlockPos under = BlockPos.ofFloored(nx, top - 1, nz);
            if (!w.getFluidState(under).isEmpty()) continue;
            double ny = MathHelper.lerp(0.5, v.getY(), top);
            if (Math.abs(top - v.getY()) < 0.05) ny = top;
            v.refreshPositionAndAngles(nx, ny, nz, v.getYaw(), v.getPitch());
            return;
        }
    }

    /**
     * One round of a volley, at the spot aimed at during the wind-up (plus some scatter): a target
     * that moved since is missed, one that stood still is hit. Titans are too big to miss.
     */
    private static void fire(ServerWorld w, VillagerEntity v, Troop t, LivingEntity target, Vec3d aimed, double dist, Squad sq) {
        if (aimed == null) aimed = aim(target);
        Vec3d muzzle = v.getEyePos().add(v.getRotationVec(1f).multiply(0.6)).add(0, -0.25, 0);
        boolean titan = AotRpg.isTitan(target);
        double spread = titan ? 0.5 : 0.28 + dist * 0.011;
        if (sq.broken) spread *= 1.5;
        else if (!t.officer && sq.leader != null && troops.containsKey(sq.leader) && troops.get(sq.leader).officer) spread *= 0.8;
        if (t.officer) spread *= 0.8;
        var r = w.random;
        Vec3d end = aimed.add(r.nextGaussian() * spread, r.nextGaussian() * spread * 0.7, r.nextGaussian() * spread);
        Vec3d dir = end.subtract(muzzle).normalize();
        Vec3d far = muzzle.add(dir.multiply(Math.max(dist + 20, 40)));
        var hitBox = target.getBoundingBox().expand(titan ? 0.5 : 0.15).raycast(muzzle, far);
        boolean hit = hitBox.isPresent() && sees(w, v, hitBox.get());
        Vec3d stop = hitBox.orElse(end);
        w.playSound(null, v.getX(), v.getY() + 1.5, v.getZ(), SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.HOSTILE, 1.6f, 1.5f + r.nextFloat() * 0.2f);
        w.playSound(null, v.getX(), v.getY() + 1.5, v.getZ(), SoundEvents.ITEM_CROSSBOW_SHOOT, SoundCategory.HOSTILE, 0.8f, 1.8f);
        w.spawnParticles(ParticleTypes.SMOKE, muzzle.x, muzzle.y, muzzle.z, 3, 0.05, 0.05, 0.05, 0.01);
        Net.Trail trail = new Net.Trail("trail_troop", muzzle.x, muzzle.y, muzzle.z, stop.x, stop.y, stop.z);
        for (ServerPlayerEntity o : PlayerLookup.around(w, muzzle, 96)) if (ServerPlayNetworking.canSend(o, Net.Trail.ID)) ServerPlayNetworking.send(o, trail);
        if (!hit) {
            if (target instanceof ServerPlayerEntity p) p.playSoundToPlayer(SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.HOSTILE, 1f, 2f);
            return;
        }
        float dmg = titan ? 7 + sq.level * 0.3f : 3f + sq.level * 0.12f + (t.officer ? 1.5f : 0);
        if (titan && t.job == 2 && stop.y > target.getY() + target.getHeight() * 0.75) dmg *= 1.6f;
        target.damage(w.getDamageSources().mobAttack(v), dmg);
        w.spawnParticles(titan ? ParticleTypes.CLOUD : ParticleTypes.CRIT, stop.x, stop.y, stop.z, titan ? 3 : 5, 0.15, 0.15, 0.15, 0.05);
        // Whoever's drawing it keeps its eye; the rest only sometimes catch it.
        if (titan && target instanceof MobEntity m && (t.job == 1 || r.nextFloat() < 0.2f)) m.setTarget(v);
    }

    // ------------------------------------------------------------------ titans

    /**
     * Titans near a troop swat him, or grab him and eat him (a lift up to the jaws, a scream, a
     * crunch). Called once a second.
     */
    public static void titans(ServerWorld w) {
        for (var en : new ArrayList<>(troops.entrySet())) {
            Troop t = en.getValue();
            if (troops.get(en.getKey()) != t) continue;
            if (t.grabbed >= 0 || !(w.getEntity(en.getKey()) instanceof VillagerEntity v) || !v.isAlive()) continue;
            for (Entity e : w.getOtherEntities(v, v.getBoundingBox().expand(10, 8, 10), e -> AotRpg.isTitan(e) && TitanLevels.rootOf(e) == e && e.isAlive())) {
                LivingEntity ti = (LivingEntity) e;
                double reach = ti.getWidth() * 0.6 + 3.5;
                if (ti.squaredDistanceTo(v) > reach * reach) continue;
                if (w.random.nextFloat() < 0.35f) {
                    t.grabbed = 0;
                    t.grabber = ti.getUuid();
                    t.grabFrom = v.getPos();
                    w.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.ENTITY_VILLAGER_HURT, SoundCategory.HOSTILE, 1.5f, 0.6f);
                } else {
                    v.damage(w.getDamageSources().mobAttack(ti), 14);
                    w.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.HOSTILE, 1.4f, 0.6f);
                    w.spawnParticles(ParticleTypes.EXPLOSION, v.getX(), v.getY() + 0.5, v.getZ(), 1, 0, 0, 0, 0);
                }
                if (ti instanceof MobEntity m && m.getTarget() == null) m.setTarget(v);
                break;
            }
        }
    }

    /** Being eaten: lifted to the titan's mouth over a second, then gone. */
    private static void eaten(ServerWorld w, VillagerEntity v, Troop t) {
        Entity ti = t.grabber == null ? null : w.getEntity(t.grabber);
        if (!(ti instanceof LivingEntity titan) || !titan.isAlive()) {
            t.grabbed = -1;
            return;
        }
        t.grabbed++;
        double yaw = Math.toRadians(titan.getYaw());
        Vec3d mouth = titan.getPos().add(-Math.sin(yaw) * titan.getWidth() * 0.5, titan.getHeight() * 0.86, Math.cos(yaw) * titan.getWidth() * 0.5);
        double k = Math.min(1, t.grabbed / 24.0);
        Vec3d p = t.grabFrom.lerp(mouth, k * k * (3 - 2 * k));
        v.refreshPositionAndAngles(p.x, p.y, p.z, v.getYaw() + 12, 0);
        if (t.grabbed % 6 == 0) w.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_VILLAGER_HURT, SoundCategory.HOSTILE, 1.4f, 0.5f + w.random.nextFloat() * 0.3f);
        if (t.grabbed >= 30) {
            w.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_GENERIC_EAT, SoundCategory.HOSTILE, 2f, 0.4f);
            w.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_HONEY_BLOCK_BREAK, SoundCategory.HOSTILE, 2f, 0.5f);
            w.spawnParticles(new net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, net.minecraft.block.Blocks.REDSTONE_BLOCK.getDefaultState()),
                p.x, p.y, p.z, 40, 0.4, 0.4, 0.4, 0.2);
            v.damage(w.getDamageSources().mobAttack(titan), 10000);
        }
    }

    // ------------------------------------------------------------------ the fallen

    /** A troop fell: what he carried spills out, and whoever killed him hears it. */
    public static void died(ServerWorld w, VillagerEntity v, net.minecraft.entity.damage.DamageSource src, int level) {
        Troop t = troops.remove(v.getUuid());
        boolean officer = v.getCommandTags().contains(OFFICER);
        Squad sq = t == null ? null : squads.get(t.squad);
        // A duel ends with him: the next man may step in.
        for (var it = duels.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!e.getValue().equals(v.getUuid())) continue;
            duelOpen.put(e.getKey(), System.currentTimeMillis() + 8000);
            it.remove();
        }
        if (sq != null) {
            // The leader falls: an officer's squad breaks; otherwise the next man steps up.
            if (officer) {
                sq.broken = true;
                w.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.ENTITY_PILLAGER_HURT, SoundCategory.HOSTILE, 1.5f, 0.6f);
            }
            if (v.getUuid().equals(sq.leader)) {
                sq.leader = null;
                for (Troop o : troops.values()) if (o.squad == sq.id) {
                    sq.leader = o.id;
                    break;
                }
            }
        }
        Random r = w.random;
        List<ItemStack> drops = new ArrayList<>();
        Item ammo = AotItems.exact("apg_cartridge"), gas = AotItems.exact("gas_canister"), blades = AotItems.exact("blade_component");
        if (ammo != null && (officer || r.nextFloat() < 0.6f)) drops.add(new ItemStack(ammo, 2 + r.nextInt(4)));
        if (gas != null && r.nextFloat() < 0.12f) drops.add(new ItemStack(gas));
        if (blades != null && r.nextFloat() < 0.15f) drops.add(new ItemStack(blades, 1 + r.nextInt(3)));
        if (officer ? r.nextFloat() < 0.6f : r.nextFloat() < 0.07f) {
            ItemStack g = Gear.roll(r, Gear.rollRarity(r, officer ? 3 : 1), Math.max(1, level + r.nextInt(3)));
            if (!g.isEmpty()) drops.add(g);
        }
        // Marleyan know-how: now and then a schematic in a pocket (officers carry the good ones).
        if (r.nextFloat() < (officer ? 0.25f : 0.06f)) {
            ItemStack sch = Recipes.randomSchematic(r, officer ? 3 : 2);
            if (!sch.isEmpty()) drops.add(sch);
        }
        if (officer && r.nextFloat() < 0.15f && AotItems.exact("apg_gun") != null) drops.add(new ItemStack(AotItems.exact("apg_gun")));
        for (ItemStack s : drops) {
            // Cut down by someone: it's theirs, straight into the bag. (Eaten by a titan, it spills.)
            if (src.getAttacker() instanceof ServerPlayerEntity looter) {
                Loot.claim(looter, s);
                continue;
            }
            ItemEntity ie = new ItemEntity(w, v.getX(), v.getY() + 0.8, v.getZ(), s,
                (r.nextDouble() - 0.5) * 0.25, 0.3 + r.nextDouble() * 0.15, (r.nextDouble() - 0.5) * 0.25);
            ie.setPickupDelay(10);
            w.spawnEntity(ie);
        }
        if (src.getAttacker() instanceof ServerPlayerEntity killer) {
            long pay = officer ? 350 : 90;
            AotRpg.WALLET.addMarks(killer, pay, "Marleyan troop");
            killer.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.8f, 0.6f);
            killer.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.PLAYERS, 0.6f, officer ? 0.7f : 1.2f);
            killer.sendMessage(Text.literal(officer ? "OFFICER DOWN" : "RIFLEMAN DOWN").formatted(officer ? Formatting.GOLD : Formatting.RED, Formatting.BOLD)
                .append(Text.literal("  +" + pay + " Marks").formatted(Formatting.YELLOW)), true);
        }
    }
}
