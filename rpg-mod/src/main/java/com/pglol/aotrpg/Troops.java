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

        Squad(int id, String session, BlockPos centre, int radius, int level) {
            this.id = id;
            this.session = session;
            this.centre = centre;
            this.radius = radius;
            this.level = level;
        }
    }

    private static final Map<UUID, Troop> troops = new HashMap<>();
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
            v.setCustomName(Text.literal(officer ? "Marleyan Officer" : "Marleyan Rifleman").formatted(officer ? Formatting.GOLD : Formatting.RED));
            v.setCustomNameVisible(officer);
            var hp = v.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
            if (hp != null) hp.setBaseValue((officer ? 60 : 28) + level * 0.8);
            v.setHealth(v.getMaxHealth());
            Item gun = AotItems.exact("apg_gun");
            if (gun != null) v.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, new ItemStack(gun));
            v.setEquipmentDropChance(net.minecraft.entity.EquipmentSlot.MAINHAND, 0);
            if (w.spawnEntity(v)) {
                int role = made == 0 ? 0 : made % 2 == 0 ? 2 : 1;
                troops.put(v.getUuid(), new Troop(v.getUuid(), sq.id, officer, role, made));
                if (made == 0) sq.leader = v.getUuid();
                made++;
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
        for (var it = troops.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            Troop t = en.getValue();
            Entity ent = w.getEntity(en.getKey());
            if (!(ent instanceof VillagerEntity v) || !v.isAlive()) {
                if (ent != null && !ent.isAlive()) it.remove();
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
            if (near == null || nd > 160 * 160) continue;
            Squad sq = squads.get(t.squad);
            if (sq == null) continue;
            if (t.grabbed >= 0) {
                eaten(w, v, t);
                continue;
            }
            think(w, v, t, sq, players, ticks);
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

    private static void think(ServerWorld w, VillagerEntity v, Troop t, Squad sq, List<ServerPlayerEntity> players, int ticks) {
        if (t.stun > 0) {
            // Reeling from a parry: stock still, seeing stars.
            t.stun--;
            if (t.stun % 5 == 0) w.spawnParticles(ParticleTypes.CRIT, v.getX(), v.getY() + 2.1, v.getZ(), 3, 0.25, 0.05, 0.25, 0.02);
            return;
        }
        LivingEntity target = find(w, t.target);
        if (target instanceof ServerPlayerEntity sp && (sp.isSpectator() || sp.isCreative() || AotRpg.DOWNED.isDowned(sp))) target = null;
        // Whoever just shot or cut them gets their attention.
        if (v.getAttacker() instanceof LivingEntity att && att.isAlive() && att != target && v.getLastAttackedTime() > v.age - 5
            && (att instanceof ServerPlayerEntity || AotRpg.isTitan(att))) target = att;
        // The squad fights as one: take the leader's target when you have none.
        if (target == null && sq.leader != null && !sq.leader.equals(t.id)) {
            Troop lead = troops.get(sq.leader);
            if (lead != null) target = find(w, lead.target);
        }
        if (--t.retarget <= 0 || target == null) {
            t.retarget = 10;
            LivingEntity best = null;
            double bd = Double.MAX_VALUE;
            for (Entity e : w.getOtherEntities(v, v.getBoundingBox().expand(44, 30, 44), e -> e instanceof LivingEntity le && le.isAlive())) {
                LivingEntity le = (LivingEntity) e;
                double d = le.squaredDistanceTo(v);
                if (le instanceof ServerPlayerEntity p) {
                    if (p.isSpectator() || p.isCreative() || AotRpg.DOWNED.isDowned(p) || d > 38 * 38) continue;
                } else if (AotRpg.isTitan(le) && TitanLevels.rootOf(le) == le) {
                    d *= 0.6;
                } else continue;
                if (d < bd && sees(w, v, aim(le))) {
                    bd = d;
                    best = le;
                }
            }
            if (best != null && (target == null || best != target && bd < target.squaredDistanceTo(v) * 0.6)) target = best;
        }
        if (target instanceof ServerPlayerEntity sp && !t.spotted) {
            t.spotted = true;
            sp.playSoundToPlayer(SoundEvents.ENTITY_PILLAGER_CELEBRATE, SoundCategory.HOSTILE, 1f, 0.8f);
        }
        t.target = target == null ? null : target.getUuid();

        // Hurt badly, or their officer's down: some fall back for a while.
        if (!t.officer && t.flee <= 0 && target != null
            && (v.getHealth() < v.getMaxHealth() * 0.3f || sq.broken && w.random.nextInt(400) == 0)) t.flee = 90;
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
        // Up close with a person on the ground: blades out. They can't follow you into the air
        // (no gear of their own), so the moment you pull away or take off, it's back to the gun.
        boolean airborne = target instanceof ServerPlayerEntity sp2 && (!sp2.isOnGround() && sp2.getVelocity().lengthSquared() > 0.09);
        if (!titan && dist < 5 && !airborne) blade(v, t);
        else if (dist > 6 || airborne || titan) gun(v, t);
        if (t.blade) {
            melee(w, v, t, target, dist, sq);
            return;
        }

        // At range, in formation: the leader holds back, riflemen hold a line, flankers work round the side.
        Vec3d at = aim(target);
        Vec3d flat = target.getPos().subtract(v.getPos()).multiply(1, 0, 1);
        double want = titan ? Math.max(16, target.getHeight() * 1.2) : t.role == 0 ? 22 : 17;
        Vec3d move;
        if (t.role == 2 && !titan) {
            // A spot off to the target's side (left or right by slot), closing to about 14 blocks.
            double base = Math.atan2(v.getZ() - target.getZ(), v.getX() - target.getX());
            double ang = base + (t.slot % 4 < 2 ? 1 : -1) * 0.35;
            Vec3d spot = target.getPos().add(Math.cos(ang) * 14, 0, Math.sin(ang) * 14);
            move = spot.subtract(v.getPos()).multiply(1, 0, 1);
            if (move.lengthSquared() < 4) move = Vec3d.ZERO;
            else move = move.normalize();
        } else {
            Vec3d side = new Vec3d(-flat.z, 0, flat.x).normalize().multiply(t.strafe);
            if (--t.strafeFlip <= 0) {
                t.strafeFlip = 30 + w.random.nextInt(40);
                t.strafe = -t.strafe;
            }
            move = side.multiply(t.windup > 0 ? 0.15 : 0.45);
            if (dist > want + 6) move = move.add(flat.normalize());
            else if (dist < want - 5) move = move.add(flat.normalize().multiply(-1));
        }
        walk(w, v, move, titan && dist < want ? 0.19 : t.windup > 0 ? 0.05 : 0.11);
        face(v, t.windup > 0 && t.aimAt != null ? t.aimAt : at);

        if (t.windup > 0) {
            // Taking aim: a red line to where the volley will go. Move and it misses.
            t.windup--;
            if (ticks % 2 == 0) laser(w, v, t.aimAt);
            if (t.windup == 0) {
                t.burst = t.officer ? 4 : 3;
                t.cool = 0;
            }
            return;
        }
        if (t.burst > 0) {
            if (ticks % 4 == 0) {
                fire(w, v, t, target, t.aimAt, dist, sq);
                if (--t.burst == 0) t.cool = 40 + w.random.nextInt(30);
            }
            return;
        }
        if (t.cool > 0) {
            t.cool--;
            return;
        }
        // Volleys in turns across the squad: a rhythm you can learn and rush between.
        if (ticks < sq.nextVolley || !sees(w, v, at)) return;
        sq.nextVolley = ticks + (titan ? 10 : 22);
        t.windup = titan ? 6 : t.officer ? 12 : 16;
        t.aimAt = at;
        if (target instanceof ServerPlayerEntity p) p.playSoundToPlayer(SoundEvents.ITEM_CROSSBOW_LOADING_MIDDLE.value(), SoundCategory.HOSTILE, 1f, 1.4f);
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
        if (!t.blade) return;
        t.blade = false;
        t.swing = 0;
        Item gun = AotItems.exact("apg_gun");
        if (gun != null) v.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, new ItemStack(gun));
    }

    private static void blade(VillagerEntity v, Troop t) {
        if (t.blade) return;
        t.blade = true;
        t.windup = 0;
        t.burst = 0;
        Item grip = AotItems.exact("blade");
        if (grip != null) v.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, new ItemStack(grip));
        v.getWorld().playSound(null, v.getX(), v.getY() + 1, v.getZ(), SoundEvents.ITEM_ARMOR_EQUIP_IRON.value(), SoundCategory.HOSTILE, 1f, 1.3f);
    }

    /**
     * Blades up close: close in, then a wind-up you can read (a glint, a rising scrape) before the
     * cut. Guard it facing them and they're thrown off balance, wide open; step out of reach and it
     * whiffs.
     */
    private static void melee(ServerWorld w, VillagerEntity v, Troop t, LivingEntity target, double dist, Squad sq) {
        face(v, target.getEyePos());
        if (t.swing > 0) {
            t.swing--;
            Vec3d hand = v.getEyePos().add(v.getRotationVec(1f).multiply(0.7)).add(0, -0.4, 0);
            w.spawnParticles(ParticleTypes.ENCHANTED_HIT, hand.x, hand.y, hand.z, 2, 0.1, 0.1, 0.1, 0.05);
            if (t.swing > 0) return;
            v.swingHand(net.minecraft.util.Hand.MAIN_HAND);
            w.playSound(null, v.getX(), v.getY() + 1, v.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 1f, 0.9f);
            Vec3d look = v.getRotationVec(1f).multiply(1, 0, 1).normalize();
            Vec3d to = target.getPos().subtract(v.getPos()).multiply(1, 0, 1);
            boolean reach = dist < 3.4 && to.lengthSquared() > 1e-4 && look.dotProduct(to.normalize()) > 0.3;
            if (!reach) {
                t.cool = 14;
                return;
            }
            // (A guard raised in time turns it: a well-timed one throws them off balance, see Guard.)
            target.damage(w.getDamageSources().mobAttack(v), 5 + sq.level * 0.1f + (t.officer ? 2 : 0));
            target.addVelocity(look.x * 0.5, 0.2, look.z * 0.5);
            target.velocityModified = true;
            t.cool = 18;
            return;
        }
        if (t.cool > 0) {
            t.cool--;
            if (dist > 2.6) walk(w, v, target.getPos().subtract(v.getPos()).multiply(1, 0, 1), 0.14);
            return;
        }
        if (dist > 2.6) {
            walk(w, v, target.getPos().subtract(v.getPos()).multiply(1, 0, 1), 0.2);
            return;
        }
        // The tell: a glint and a scrape, then the cut.
        t.swing = t.officer ? 8 : 11;
        w.playSound(null, v.getX(), v.getY() + 1, v.getZ(), SoundEvents.ITEM_TRIDENT_RETURN, SoundCategory.HOSTILE, 1f, 1.6f);
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
        double spread = titan ? 0.6 : 0.55 + dist * 0.02;
        if (sq.broken) spread *= 1.6;
        else if (!t.officer && sq.leader != null && troops.containsKey(sq.leader) && troops.get(sq.leader).officer) spread *= 0.85;
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
        float dmg = titan ? 7 + sq.level * 0.25f : 2.5f + sq.level * 0.06f + (t.officer ? 1 : 0);
        target.damage(w.getDamageSources().mobAttack(v), dmg);
        w.spawnParticles(titan ? ParticleTypes.CLOUD : ParticleTypes.CRIT, stop.x, stop.y, stop.z, titan ? 3 : 5, 0.15, 0.15, 0.15, 0.05);
        if (titan && target instanceof MobEntity m && r.nextFloat() < 0.35f) m.setTarget(v);
    }

    // ------------------------------------------------------------------ titans

    /**
     * Titans near a troop swat him, or grab him and eat him (a lift up to the jaws, a scream, a
     * crunch). Called once a second.
     */
    public static void titans(ServerWorld w) {
        for (var en : troops.entrySet()) {
            Troop t = en.getValue();
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
        if (ammo != null) drops.add(new ItemStack(ammo, 4 + r.nextInt(8)));
        if (gas != null && r.nextFloat() < 0.35f) drops.add(new ItemStack(gas));
        if (blades != null && r.nextFloat() < 0.35f) drops.add(new ItemStack(blades, 3 + r.nextInt(5)));
        if (officer || r.nextFloat() < 0.18f) {
            ItemStack g = Gear.roll(r, Gear.rollRarity(r, officer ? 3 : 1), Math.max(1, level + r.nextInt(3)));
            if (!g.isEmpty()) drops.add(g);
        }
        if (officer && r.nextFloat() < 0.4f && AotItems.exact("apg_gun") != null) drops.add(new ItemStack(AotItems.exact("apg_gun")));
        for (ItemStack s : drops) {
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
