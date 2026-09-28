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
        UUID target;
        int burst, cool, retarget, strafe = 1, strafeFlip, flee, grabbed = -1;
        UUID grabber;
        Vec3d grabFrom;
        boolean spotted;

        Troop(UUID id, int squad, boolean officer) {
            this.id = id;
            this.squad = squad;
            this.officer = officer;
        }
    }

    private static final class Squad {
        final int id;
        final String session;
        final BlockPos centre;
        final int radius, level;
        BlockPos goal;
        int regoal;

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
                troops.put(v.getUuid(), new Troop(v.getUuid(), sq.id, officer));
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
        LivingEntity target = find(w, t.target);
        if (target instanceof ServerPlayerEntity sp && (sp.isSpectator() || sp.isCreative() || AotRpg.DOWNED.isDowned(sp))) target = null;
        // Whoever just shot or cut them gets their attention.
        if (v.getAttacker() instanceof LivingEntity att && att.isAlive() && att != target && v.getLastAttackedTime() > v.age - 5) {
            target = att instanceof MobEntity && !AotRpg.isTitan(att) ? target : att;
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
                    // Titans close by are the first worry.
                    d *= 0.6;
                } else continue;
                if (d < bd && sees(w, v, aim(le))) {
                    bd = d;
                    best = le;
                }
            }
            if (best != null && (target == null || best != target && bd < target.squaredDistanceTo(v) * 0.6)) target = best;
        }
        if (target != null && target != find(w, t.target) && target instanceof ServerPlayerEntity sp && !t.spotted) {
            // Spotted: a shout, heard by whoever they've seen.
            t.spotted = true;
            sp.playSoundToPlayer(SoundEvents.ENTITY_PILLAGER_CELEBRATE, SoundCategory.HOSTILE, 1f, 0.8f);
            sp.playSoundToPlayer(SoundEvents.ENTITY_PILLAGER_CELEBRATE, SoundCategory.HOSTILE, 0.6f, 1.2f);
        }
        t.target = target == null ? null : target.getUuid();

        // Hurt badly: fall back for a while (officers hold).
        if (!t.officer && t.flee <= 0 && v.getHealth() < v.getMaxHealth() * 0.3f && target != null) t.flee = 80;
        if (t.flee > 0) {
            t.flee--;
            if (target != null) {
                Vec3d away = v.getPos().subtract(target.getPos()).multiply(1, 0, 1);
                walk(w, v, away, 0.2);
            }
            return;
        }

        if (target == null) {
            // Patrol toward the squad's goal, loosely together.
            if (sq.goal != null) {
                Vec3d to = Vec3d.ofBottomCenter(sq.goal).add((t.id.hashCode() % 5) * 1.5, 0, ((t.id.hashCode() >> 4) % 5) * 1.5).subtract(v.getPos()).multiply(1, 0, 1);
                if (to.lengthSquared() > 9) walk(w, v, to, 0.11);
            }
            return;
        }

        // In a fight: keep a working distance, sidestep, and fire in bursts.
        Vec3d at = aim(target);
        double dist = Math.sqrt(v.squaredDistanceTo(target));
        boolean titan = AotRpg.isTitan(target);
        double want = titan ? Math.max(16, target.getHeight() * 1.2) : 18;
        Vec3d flat = target.getPos().subtract(v.getPos()).multiply(1, 0, 1);
        Vec3d side = new Vec3d(-flat.z, 0, flat.x).normalize().multiply(t.strafe);
        if (--t.strafeFlip <= 0) {
            t.strafeFlip = 30 + w.random.nextInt(40);
            t.strafe = -t.strafe;
        }
        Vec3d move = side.multiply(0.5);
        if (dist > want + 6) move = move.add(flat.normalize());
        else if (dist < want - 5) move = move.add(flat.normalize().multiply(-1));
        walk(w, v, move, titan && dist < want ? 0.19 : 0.1);
        face(v, at);
        if (t.cool > 0) {
            t.cool--;
            return;
        }
        if (!sees(w, v, at)) return;
        if (ticks % 5 != 0) return;
        fire(w, v, t, target, at, dist, sq.level);
        if (++t.burst >= (t.officer ? 4 : 3)) {
            t.burst = 0;
            t.cool = 30 + w.random.nextInt(30);
        }
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

    private static void fire(ServerWorld w, VillagerEntity v, Troop t, LivingEntity target, Vec3d at, double dist, int level) {
        Vec3d muzzle = v.getEyePos().add(v.getRotationVec(1f).multiply(0.6)).add(0, -0.25, 0);
        // Harder to hit far off, and much harder to hit someone flying on their gear.
        double chance = 0.75 - dist * 0.012;
        if (target instanceof ServerPlayerEntity p && p.getVelocity().lengthSquared() > 0.25) chance *= 0.35;
        if (AotRpg.isTitan(target)) chance = 0.9;
        boolean hit = w.random.nextDouble() < chance;
        Vec3d end = hit ? at : at.add((w.random.nextDouble() - 0.5) * 3, (w.random.nextDouble() - 0.5) * 2, (w.random.nextDouble() - 0.5) * 3);
        w.playSound(null, v.getX(), v.getY() + 1.5, v.getZ(), SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.HOSTILE, 1.6f, 1.5f + w.random.nextFloat() * 0.2f);
        w.playSound(null, v.getX(), v.getY() + 1.5, v.getZ(), SoundEvents.ITEM_CROSSBOW_SHOOT, SoundCategory.HOSTILE, 0.8f, 1.8f);
        w.spawnParticles(ParticleTypes.SMOKE, muzzle.x, muzzle.y, muzzle.z, 3, 0.05, 0.05, 0.05, 0.01);
        w.spawnParticles(ParticleTypes.SMALL_FLAME, muzzle.x, muzzle.y, muzzle.z, 1, 0.02, 0.02, 0.02, 0.0);
        Net.Trail trail = new Net.Trail("trail_troop", muzzle.x, muzzle.y, muzzle.z, end.x, end.y, end.z);
        for (ServerPlayerEntity o : PlayerLookup.around(w, muzzle, 96)) if (ServerPlayNetworking.canSend(o, Net.Trail.ID)) ServerPlayNetworking.send(o, trail);
        if (!hit) {
            // A near miss cracks past your head.
            if (target instanceof ServerPlayerEntity p) p.playSoundToPlayer(SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.HOSTILE, 1f, 2f);
            return;
        }
        boolean titan = AotRpg.isTitan(target);
        float dmg = titan ? 7 + level * 0.25f : 2.5f + level * 0.06f + (t.officer ? 1 : 0);
        target.damage(w.getDamageSources().mobAttack(v), dmg);
        w.spawnParticles(titan ? ParticleTypes.CLOUD : ParticleTypes.CRIT, end.x, end.y, end.z, titan ? 3 : 5, 0.15, 0.15, 0.15, 0.05);
        // Gunfire draws titans: the one hit turns on the shooter.
        if (titan && target instanceof MobEntity m && w.random.nextFloat() < 0.35f) m.setTarget(v);
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
