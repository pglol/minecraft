package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Class abilities: each class's two actives (Z, X), its ultimate (V) and the passives of its tree
 * that work with the squad (auras, marks, taunts, shields). Ultimates charge by fighting: dealing
 * damage, taking it, healing and felling titans; twice as fast during events (Call to Arms, boss
 * raids, hordes).
 *
 * Lone Wolf: a player with no one else within 40 blocks is fighting alone, and the game leans
 * their way: titans hit softer, napes need a strike fewer, grips break sooner, ultimates charge
 * faster and the downed can pick themselves up.
 */
public final class Classes {
    public static final double ALONE_RADIUS = 40;
    public static final float CHARGE_MAX = 100;
    /** Base cooldowns in seconds: [class][Z, X]. */
    private static final int[][] COOLDOWN = {{10, 30}, {14, 35}, {12, 40}, {12, 30}};

    private static final class State {
        final long[] ready = new long[3], max = new long[3];
        float charge;
        long ultUntil;
        int rhythm;
        long lastCombat;
        long rhythmAt, buffUntil, reportUntil, bashUntil, smokeAt, rushAt, lifelineReady;
        boolean ambushUsed, alone = true;
        UUID wall;
        long wallUntil;
    }

    private record Mark(UUID owner, long until) { }
    private record Zone(ServerWorld world, Vec3d at, long until, UUID owner) { }
    private static final class Dash {
        int ticks;
        final java.util.Set<Integer> hit = new java.util.HashSet<>();
    }
    private static final class Bleed {
        final ServerWorld world;
        final UUID target;
        int seconds;
        final float perSecond;

        Bleed(ServerWorld world, UUID target, int seconds, float perSecond) {
            this.world = world;
            this.target = target;
            this.seconds = seconds;
            this.perSecond = perSecond;
        }
    }

    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Mark> reconMarks = new HashMap<>(), coordMarks = new HashMap<>(), taunts = new HashMap<>();
    private final List<Zone> zones = new ArrayList<>();
    private final Map<UUID, Dash> dashes = new HashMap<>();
    private final List<Bleed> bleeds = new ArrayList<>();
    private MinecraftServer server;

    public void open(MinecraftServer server) {
        this.server = server;
        states.clear();
        reconMarks.clear();
        coordMarks.clear();
        taunts.clear();
        zones.clear();
        dashes.clear();
        bleeds.clear();
    }

    private State st(PlayerEntity p) {
        return states.computeIfAbsent(p.getUuid(), k -> new State());
    }

    private static Profile pr(PlayerEntity p) {
        return AotRpg.PROFILES.get(p.getUuid());
    }

    public void forget(UUID id) {
        states.remove(id);
        dashes.remove(id);
    }

    // ------------------------------------------------------------------ helpers

    /** Hit or hitting in the last 8 seconds. */
    public static boolean inCombat(ServerPlayerEntity p) {
        return System.currentTimeMillis() - AotRpg.CLASSES.st(p).lastCombat < 8000;
    }

    /** Fighting alone: nobody else within 40 blocks. */
    public static boolean alone(ServerPlayerEntity p) {
        return AotRpg.CLASSES.st(p).alone;
    }

    private static boolean computeAlone(ServerPlayerEntity p) {
        for (ServerPlayerEntity o : p.getServerWorld().getPlayers()) {
            if (o == p || o.isSpectator() || !o.isAlive()) continue;
            if (o.squaredDistanceTo(p) < ALONE_RADIUS * ALONE_RADIUS) return false;
        }
        return true;
    }

    /** You and your party within r blocks. */
    private static List<ServerPlayerEntity> squad(ServerPlayerEntity p, double r) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        out.add(p);
        for (ServerPlayerEntity o : p.getServerWorld().getPlayers()) {
            if (o == p || o.isSpectator() || !o.isAlive() || o.squaredDistanceTo(p) > r * r) continue;
            if (AotRpg.PARTIES.same(p.getUuid(), o.getUuid())) out.add(o);
        }
        return out;
    }

    private static boolean foe(ServerPlayerEntity p, Entity e) {
        if (!(e instanceof LivingEntity le) || !le.isAlive() || e == p) return false;
        if (e instanceof PlayerEntity) return false;
        return e instanceof HostileEntity || AotRpg.isTitan(e) || e instanceof MobEntity m && m.getTarget() instanceof PlayerEntity;
    }

    private static List<LivingEntity> foes(ServerPlayerEntity p, double r) {
        Box box = p.getBoundingBox().expand(r);
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : p.getServerWorld().getEntitiesByClass(LivingEntity.class, box, e -> foe(p, e))) {
            if (e.squaredDistanceTo(p) <= r * r) out.add(e);
        }
        return out;
    }

    /** The living thing you're looking at within range (a titan's whole body counts). */
    private static LivingEntity lookedAt(ServerPlayerEntity p, double range, java.util.function.Predicate<LivingEntity> ok) {
        Vec3d eye = p.getEyePos(), end = eye.add(p.getRotationVec(1f).multiply(range));
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity e : p.getServerWorld().getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(range), e -> e != p && e.isAlive() && ok.test(e))) {
            var hit = e.getBoundingBox().expand(0.6).raycast(eye, end);
            if (hit.isEmpty()) continue;
            double d = hit.get().squaredDistanceTo(eye);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    private static void fx(Entity at, ParticleEffect fx, int n, double spread) {
        if (at.getWorld() instanceof ServerWorld w) {
            w.spawnParticles(fx, at.getX(), at.getY() + at.getHeight() * 0.5, at.getZ(), n, spread, at.getHeight() / 3, spread, 0.05);
        }
    }

    private static void ring(ServerWorld w, Vec3d c, double r, ParticleEffect fx, int n) {
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            w.spawnParticles(fx, c.x + Math.cos(a) * r, c.y + 0.2, c.z + Math.sin(a) * r, 1, 0, 0.05, 0, 0);
        }
    }

    private static void sound(Entity at, SoundEvent s, float vol, float pitch) {
        at.getWorld().playSound(null, at.getBlockPos(), s, SoundCategory.PLAYERS, vol, pitch);
    }

    private static void callout(ServerPlayerEntity p, String what, Formatting color) {
        p.sendMessage(Text.literal(what).formatted(color, Formatting.BOLD), true);
    }

    /** Is an event on around this player (ultimates charge twice as fast)? */
    public static boolean inEvent(ServerPlayerEntity p) {
        FactionWar.Event e = AotRpg.WAR.active();
        if (e != null) {
            double dx = p.getX() - e.x, dz = p.getZ() - e.z;
            if (dx * dx + dz * dz < 200 * 200) return true;
        }
        return AotRpg.RAID_BOSSES.inRaid(p.getUuid()) || AotRpg.ACTIVITY.inEvent(p);
    }

    private void charge(ServerPlayerEntity p, float amount) {
        if (amount <= 0 || !pr(p).created) return;
        State s = st(p);
        if (System.currentTimeMillis() < s.ultUntil) return;
        float m = (inEvent(p) ? 2f : 1f) * (s.alone ? 1.25f : 1f);
        boolean was = s.charge >= CHARGE_MAX;
        s.charge = Math.min(CHARGE_MAX, s.charge + amount * m);
        if (!was && s.charge >= CHARGE_MAX && has(p, 2)) {
            p.playSoundToPlayer(SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.7f, 1.6f);
            Notify.toast(p, Text.literal("Ultimate ready").formatted(Formatting.GOLD, Formatting.BOLD),
                Text.literal(pr(p).cls().abilities[2] + " · press V"), 0xF2C14E, "minecraft:nether_star", "ult");
        }
    }

    /** Healing done by a player (Steady Hands makes it stronger); charges their ultimate. */
    public float heal(ServerPlayerEntity healer, LivingEntity target, float amount) {
        if (amount <= 0 || !target.isAlive()) return 0;
        if (pr(healer).has(Skill.MED_STEADY)) amount *= 1.25f;
        float before = target.getHealth();
        target.heal(amount);
        float done = target.getHealth() - before;
        if (done > 0) {
            charge(healer, done * 1.2f);
            fx(target, ParticleTypes.HEART, Math.min(6, 1 + (int) (done / 3)), 0.4);
        }
        return done;
    }

    private Skill skill(PlayerClass c, int slot) {
        Skill.Branch b = Skill.Branch.of(c);
        for (Skill s : Skill.values()) if (s.branch == b && s.slot() == slot) return s;
        return null;
    }

    private boolean has(ServerPlayerEntity p, int slot) {
        Skill s = skill(pr(p).cls(), slot);
        return s != null && pr(p).has(s);
    }

    /** Freedom's Wings: a dodge takes time off every cooldown. */
    public void cutCooldowns(ServerPlayerEntity p, long ms) {
        State s = st(p);
        for (int i = 0; i < 2; i++) s.ready[i] -= ms;
    }

    /** A lineup's roles and what it lacks: "1 Tank · 2 DPS · needs a Medic". */
    public static String lineup(List<ServerPlayerEntity> team) {
        int[] n = new int[PlayerClass.values().length];
        for (ServerPlayerEntity m : team) n[pr(m).cls().ordinal()]++;
        StringBuilder b = new StringBuilder();
        for (PlayerClass c : PlayerClass.values()) {
            if (n[c.ordinal()] == 0) continue;
            if (b.length() > 0) b.append(" · ");
            b.append(n[c.ordinal()]).append(' ').append(c.tag()).append(' ').append(c.title);
        }
        if (team.size() <= 1) return b + " · solo: Lone Wolf bonuses apply";
        List<String> need = new ArrayList<>();
        if (n[PlayerClass.TANK.ordinal()] == 0) need.add("a Tank");
        if (n[PlayerClass.MEDIC.ordinal()] == 0) need.add("a Medic");
        if (n[PlayerClass.INFANTRY.ordinal()] + n[PlayerClass.RECON.ordinal()] == 0) need.add("damage");
        return b + (need.isEmpty() ? " · balanced" : " · needs " + String.join(", ", need));
    }

    // ------------------------------------------------------------------ choosing a role

    /** Pick the role you show and play: free, any time out of combat and outside a boss raid. */
    public void choose(ServerPlayerEntity p, PlayerClass c) {
        Profile pr = pr(p);
        if (!pr.created || c == null || c == pr.cls()) return;
        State s = st(p);
        long now = System.currentTimeMillis();
        String why = AotRpg.RAID_BOSSES.inRaid(p.getUuid()) ? "Choose your role before the raid begins"
            : now - s.lastCombat < 8000 ? "Not in the middle of a fight" : null;
        if (why != null) {
            Notify.toast(p, Text.literal("Can't change role now").formatted(Formatting.RED), Text.literal(why), 0xC0463A, null, "role");
            return;
        }
        pr.cls = c;
        s.ultUntil = 0;
        s.wallUntil = 0;
        AotRpg.PROFILES.save(p.getUuid());
        AotRpg.NAMETAGS.update(p, pr);
        AotRpg.sync(p, pr);
        p.playSoundToPlayer(SoundEvents.ITEM_ARMOR_EQUIP_IRON.value(), SoundCategory.PLAYERS, 0.8f, 1.1f);
        Notify.toast(p, Text.literal(c.tag() + " " + c.title).formatted(Formatting.BOLD).styled(st -> st.withColor(c.color & 0xFFFFFF)),
            Text.literal("Your role: " + c.abilities[0] + " (Z) · " + c.abilities[1] + " (X) · " + c.abilities[2] + " (V)"), c.color & 0xFFFFFF, null, "role");
    }

    // ------------------------------------------------------------------ using abilities

    public void use(ServerPlayerEntity p, int slot) {
        Profile pr = pr(p);
        if (!pr.created || slot < 0 || slot > 2 || p.isSpectator() || AotRpg.DOWNED.isDowned(p)) return;
        PlayerClass c = pr.cls();
        Skill sk = skill(c, slot);
        if (sk == null) return;
        if (!pr.has(sk)) {
            Notify.toast(p, Text.literal(c.abilities[slot] + " isn't learned yet").formatted(Formatting.RED),
                Text.literal("Tier " + (sk.tier + 1) + " of the " + c.title + " tree (level " + sk.level + ", K)"), 0xC0463A, null, "ability");
            return;
        }
        State s = st(p);
        long now = System.currentTimeMillis();
        if (slot == 2) {
            if (s.charge < CHARGE_MAX) {
                p.sendMessage(Text.literal(c.abilities[2] + " · " + Math.round(s.charge) + "% charged").formatted(Formatting.GRAY), true);
                return;
            }
        } else if (now < s.ready[slot]) {
            p.sendMessage(Text.literal(c.abilities[slot] + " · " + String.format(java.util.Locale.ROOT, "%.1fs", (s.ready[slot] - now) / 1000.0)).formatted(Formatting.GRAY), true);
            return;
        }
        boolean done = switch (c) {
            case INFANTRY -> slot == 0 ? rush(p) : slot == 1 ? warCry(p) : strongest(p);
            case TANK -> slot == 0 ? provoke(p, true) : slot == 1 ? bulwark(p) : resolve(p);
            case MEDIC -> slot == 0 ? dressing(p) : slot == 1 ? sanctuary(p) : blessing(p);
            case RECON -> slot == 0 ? mark(p) : slot == 1 ? smoke(p) : huntersEye(p);
        };
        if (!done) return;
        if (slot == 2) {
            s.charge = 0;
            return;
        }
        double cd = COOLDOWN[c.ordinal()][slot];
        if (c == PlayerClass.INFANTRY && pr.has(Skill.INF_QUICK)) cd *= 0.7;
        if (c == PlayerClass.MEDIC && slot == 0 && pr.has(Skill.MED_PURGE)) cd *= 0.75;
        if (c == PlayerClass.RECON && pr.has(Skill.RCN_LONE) && s.alone) cd *= 0.75;
        s.max[slot] = Math.round(cd * 1000);
        s.ready[slot] = now + s.max[slot];
    }

    // ---- Infantry

    private boolean rush(ServerPlayerEntity p) {
        Vec3d look = p.getRotationVec(1f);
        Vec3d v = new Vec3d(look.x, Math.max(-0.2, Math.min(0.5, look.y)), look.z).normalize().multiply(1.9);
        p.setVelocity(v.x, v.y + 0.15, v.z);
        p.velocityModified = true;
        Dash d = new Dash();
        d.ticks = 8;
        dashes.put(p.getUuid(), d);
        st(p).rushAt = System.currentTimeMillis();
        sound(p, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.7f);
        sound(p, SoundEvents.ENTITY_BREEZE_SHOOT, 0.7f, 1.4f);
        fx(p, ParticleTypes.CLOUD, 12, 0.4);
        return true;
    }

    private boolean warCry(ServerPlayerEntity p) {
        long until = System.currentTimeMillis() + 8000;
        for (ServerPlayerEntity a : squad(p, 12)) {
            st(a).buffUntil = Math.max(st(a).buffUntil, until);
            a.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 160, 0, false, true, true));
            fx(a, ParticleTypes.ANGRY_VILLAGER, 4, 0.4);
            if (a != p) Notify.toast(a, Text.literal("War Cry!").formatted(Formatting.RED, Formatting.BOLD),
                Text.literal(pr(p).name + " rallies you: +20% damage"), 0xD0563A, "minecraft:goat_horn", "warcry");
        }
        sound(p, SoundEvents.ENTITY_RAVAGER_ROAR, 0.8f, 1.3f);
        ring(p.getServerWorld(), p.getPos(), 12, ParticleTypes.FLAME, 48);
        callout(p, "WAR CRY", Formatting.RED);
        return true;
    }

    private boolean strongest(ServerPlayerEntity p) {
        State s = st(p);
        s.ultUntil = System.currentTimeMillis() + 10_000;
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 200, 1, false, true, true));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.HASTE, 200, 1, false, false, true));
        ult(p, "HUMANITY'S STRONGEST", 0xD0563A, ParticleTypes.SWEEP_ATTACK);
        return true;
    }

    // ---- Tank

    private boolean provoke(ServerPlayerEntity p, boolean loud) {
        long until = System.currentTimeMillis() + 6000;
        int n = 0;
        for (LivingEntity e : foes(p, 16)) {
            if (e instanceof MobEntity m) {
                m.setTarget(p);
                taunts.put(e.getUuid(), new Mark(p.getUuid(), until));
                n++;
            }
        }
        if (!loud) return true;
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 120, 0, false, true, true));
        if (pr(p).has(Skill.TNK_IRONHIDE)) p.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 120, 1, false, true, true));
        sound(p, SoundEvents.ITEM_SHIELD_BLOCK, 1f, 0.6f);
        sound(p, SoundEvents.ENTITY_IRON_GOLEM_HURT, 0.8f, 0.6f);
        ring(p.getServerWorld(), p.getPos(), 3, ParticleTypes.ENCHANTED_HIT, 30);
        callout(p, n > 0 ? "PROVOKED " + n : "PROVOKE", Formatting.BLUE);
        return true;
    }

    private boolean bulwark(ServerPlayerEntity p) {
        State s = st(p);
        s.wallUntil = System.currentTimeMillis() + 8000;
        for (ServerPlayerEntity a : squad(p, 8)) {
            if (a != p) Notify.toast(a, Text.literal("Bulwark").formatted(Formatting.BLUE, Formatting.BOLD),
                Text.literal("Stay within 8 blocks of " + pr(p).name + ": 30% less damage"), 0x4A7AC0, "minecraft:shield", "bulwark");
        }
        sound(p, SoundEvents.BLOCK_ANVIL_LAND, 0.6f, 0.7f);
        ring(p.getServerWorld(), p.getPos(), 8, ParticleTypes.END_ROD, 60);
        callout(p, "BULWARK", Formatting.BLUE);
        return true;
    }

    private boolean resolve(ServerPlayerEntity p) {
        st(p).ultUntil = System.currentTimeMillis() + 12_000;
        if (Grab.grabbed(p)) p.stopRiding();
        provoke(p, false);
        ult(p, "ARMORED RESOLVE", 0x4A7AC0, ParticleTypes.WAX_ON);
        return true;
    }

    // ---- Medic

    private boolean dressing(ServerPlayerEntity p) {
        Profile pr = pr(p);
        ServerPlayerEntity t = (ServerPlayerEntity) lookedAt(p, 10, e -> e instanceof ServerPlayerEntity sp && !sp.isSpectator());
        if (t == null) t = p;
        if (AotRpg.DOWNED.isDowned(t)) {
            if (!pr.has(Skill.MED_QUICK)) {
                p.sendMessage(Text.literal("Crouch beside them to revive").formatted(Formatting.GRAY), true);
                return false;
            }
            AotRpg.DOWNED.boost(t, p, 0.5f);
            fx(t, ParticleTypes.END_ROD, 12, 0.5);
            sound(t, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
            return true;
        }
        float amount = 6 + pr.level / 8f;
        if (pr.has(Skill.MED_LONE) && st(p).alone) amount *= 2;
        heal(p, t, amount);
        if (pr.has(Skill.MED_ADRENALINE)) {
            t.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 100, 0, false, true, true));
            AotRpg.STAMINA.spend(t, -25);
        }
        if (pr.has(Skill.MED_PURGE)) {
            for (var e : new ArrayList<>(t.getStatusEffects())) {
                if (e.getEffectType().value().getCategory() == net.minecraft.entity.effect.StatusEffectCategory.HARMFUL) t.removeStatusEffect(e.getEffectType());
            }
        }
        sound(t, SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(), 1f, 1.2f);
        sound(t, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.6f);
        fx(t, ParticleTypes.HAPPY_VILLAGER, 12, 0.4);
        if (t != p) Notify.toast(t, Text.literal("Patched up").formatted(Formatting.GREEN),
            Text.literal(pr.name + " dressed your wounds"), 0x5BC06A, "minecraft:paper", "dressing");
        return true;
    }

    private boolean sanctuary(ServerPlayerEntity p) {
        zones.add(new Zone(p.getServerWorld(), p.getPos(), System.currentTimeMillis() + 8000, p.getUuid()));
        sound(p, SoundEvents.BLOCK_BEACON_ACTIVATE, 0.9f, 1.5f);
        ring(p.getServerWorld(), p.getPos(), 6, ParticleTypes.HAPPY_VILLAGER, 50);
        callout(p, "SANCTUARY", Formatting.GREEN);
        return true;
    }

    private boolean blessing(ServerPlayerEntity p) {
        int revived = AotRpg.DOWNED.reviveAll(p, 20);
        int healed = 0;
        for (ServerPlayerEntity a : p.getServerWorld().getPlayers()) {
            if (a.isSpectator() || !a.isAlive() || a.squaredDistanceTo(p) > 20 * 20 || AotRpg.DOWNED.isDowned(a)) continue;
            if (a != p && !AotRpg.PARTIES.same(p.getUuid(), a.getUuid()) && !st(p).alone) {
                // strangers nearby are blessed too
            }
            heal(p, a, a.getMaxHealth());
            a.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 120, 0, false, true, true));
            if (a == p && st(p).alone) {
                a.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 160, 2, false, true, true));
                a.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 300, 1, false, true, true));
            }
            for (var e : new ArrayList<>(a.getStatusEffects())) {
                if (e.getEffectType().value().getCategory() == net.minecraft.entity.effect.StatusEffectCategory.HARMFUL) a.removeStatusEffect(e.getEffectType());
            }
            ServerWorld w = a.getServerWorld();
            w.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, a.getX(), a.getY() + 1, a.getZ(), 40, 0.5, 0.8, 0.5, 0.3);
            healed++;
        }
        ServerWorld w = p.getServerWorld();
        w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 2, p.getZ(), 120, 0.3, 3, 0.3, 0.02);
        ring(w, p.getPos(), 20, ParticleTypes.END_ROD, 120);
        sound(p, SoundEvents.ITEM_TOTEM_USE, 0.9f, 1.1f);
        sound(p, SoundEvents.BLOCK_BELL_RESONATE, 1f, 1.2f);
        ult(p, "BLESSING OF THE WALLS", 0x5BC06A, ParticleTypes.GLOW);
        callout(p, "BLESSING · " + healed + " healed" + (revived > 0 ? " · " + revived + " revived" : ""), Formatting.GOLD);
        return true;
    }

    // ---- Recon

    private boolean mark(ServerPlayerEntity p) {
        LivingEntity t = lookedAt(p, 48, e -> !(e instanceof PlayerEntity));
        if (t == null) {
            p.sendMessage(Text.literal("Look at a foe to mark it").formatted(Formatting.GRAY), true);
            return false;
        }
        reconMarks.put(t.getUuid(), new Mark(p.getUuid(), System.currentTimeMillis() + 10_000));
        t.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 200, 0, false, false));
        fx(t, new DustParticleEffect(new Vector3f(1f, 0.8f, 0.2f), 2f), 30, t.getWidth() / 2);
        sound(p, SoundEvents.ITEM_SPYGLASS_USE, 1f, 1f);
        sound(t, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1f, 1.8f);
        if (pr(p).has(Skill.RCN_SPOT)) {
            for (LivingEntity e : p.getServerWorld().getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(40), AotRpg::isTitan)) {
                e.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 160, 0, false, false));
            }
        }
        for (ServerPlayerEntity a : squad(p, 64)) {
            if (a != p) a.sendMessage(Text.literal(pr(p).name + " marked a target: +15% damage").formatted(Formatting.GOLD), true);
        }
        callout(p, "MARKED", Formatting.GOLD);
        return true;
    }

    private boolean smoke(ServerPlayerEntity p) {
        State s = st(p);
        s.smokeAt = System.currentTimeMillis();
        s.ambushUsed = false;
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 80, 0, false, false, true));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 80, 1, false, false, true));
        for (MobEntity m : p.getServerWorld().getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(24), m -> m.getTarget() == p)) {
            m.setTarget(null);
            m.getNavigation().stop();
        }
        ServerWorld w = p.getServerWorld();
        w.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, p.getX(), p.getY() + 1, p.getZ(), 60, 1.2, 1, 1.2, 0.02);
        w.spawnParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + 1, p.getZ(), 40, 1, 1, 1, 0.05);
        sound(p, SoundEvents.ENTITY_GENERIC_EXTINGUISH_FIRE, 1f, 0.7f);
        callout(p, "VANISHED", Formatting.DARK_GRAY);
        return true;
    }

    private boolean huntersEye(ServerPlayerEntity p) {
        st(p).ultUntil = System.currentTimeMillis() + 10_000;
        ult(p, "HUNTER'S EYE", 0xC9A53A, ParticleTypes.ENCHANT);
        return true;
    }

    private void ult(ServerPlayerEntity p, String name, int color, ParticleEffect fx) {
        ServerWorld w = p.getServerWorld();
        w.spawnParticles(fx, p.getX(), p.getY() + 1, p.getZ(), 80, 0.8, 1.2, 0.8, 0.2);
        w.spawnParticles(ParticleTypes.FLASH, p.getX(), p.getY() + 1, p.getZ(), 1, 0, 0, 0, 0);
        sound(p, SoundEvents.ITEM_TRIDENT_THUNDER.value(), 0.6f, 1.4f);
        sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
        Notify.toast(p, Text.literal(name).formatted(Formatting.BOLD).styled(st -> st.withColor(color)),
            Text.literal("Ultimate unleashed"), color, "minecraft:nether_star", "ult");
        for (ServerPlayerEntity o : w.getPlayers()) {
            if (o != p && o.squaredDistanceTo(p) < 48 * 48) o.sendMessage(Text.literal(pr(p).name + ": " + name).styled(st -> st.withColor(color).withBold(true)), true);
        }
    }

    // ------------------------------------------------------------------ combat hooks

    /** Damage multiplier for a hit this player lands. */
    public double outgoing(ServerPlayerEntity att, LivingEntity target, DamageSource source) {
        Profile pr = pr(att);
        if (!pr.created) return 1;
        State s = st(att);
        long now = System.currentTimeMillis();
        boolean titan = AotRpg.isTitan(target), melee = Combat.melee(source), ult = now < s.ultUntil;
        PlayerClass c = pr.cls();
        double m = 1;
        if (titan && pr.has(Skill.INF_HUNTER)) m *= 1.15;
        if (pr.has(Skill.INF_RHYTHM) && melee) {
            s.rhythm = now - s.rhythmAt < 3000 ? Math.min(10, s.rhythm + 1) : 1;
            s.rhythmAt = now;
            m *= 1 + 0.03 * s.rhythm;
        }
        if (pr.has(Skill.INF_BERSERK) && att.getHealth() < att.getMaxHealth() / 2) m *= 1.25;
        if (pr.has(Skill.INF_LONE) && s.alone) m *= 1.2;
        if (pr.has(Skill.RCN_LONE) && s.alone && titan) m *= 1.15;
        if (ult) m *= c == PlayerClass.INFANTRY ? 1.6 : c == PlayerClass.RECON ? 1.3 : c == PlayerClass.TANK ? 1.25 : 1;
        if (now < s.buffUntil) m *= 1.2;
        if (now < s.reportUntil) m *= 1.1;
        Mark mk = reconMarks.get(target.getUuid());
        if (mk != null && now < mk.until()) {
            m *= 1.15;
            if (mk.owner().equals(att.getUuid()) && pr.has(Skill.RCN_EYE)) m *= 1.1;
        }
        Mark co = coordMarks.get(target.getUuid());
        if (co != null && now < co.until() && AotRpg.PARTIES.same(co.owner(), att.getUuid())) m *= 1.12;
        if (pr.has(Skill.RCN_AMBUSH) && !s.ambushUsed && now - s.smokeAt < 5000) {
            s.ambushUsed = true;
            m *= 2;
            att.removeStatusEffect(StatusEffects.INVISIBILITY);
            callout(att, "AMBUSH", Formatting.DARK_RED);
            fx(target, ParticleTypes.CRIT, 20, 0.5);
        }
        if (pr.has(Skill.TNK_BASH) && now < s.bashUntil && melee) {
            s.bashUntil = 0;
            target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 3));
            target.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 40, 1));
            callout(att, "SHIELD BASH", Formatting.BLUE);
            fx(target, ParticleTypes.CRIT, 14, 0.5);
            sound(target, SoundEvents.ITEM_SHIELD_BLOCK, 1f, 0.5f);
        }
        return m;
    }

    /** Damage multiplier for a hit this player takes. */
    public double incoming(ServerPlayerEntity def, DamageSource source) {
        Profile pr = pr(def);
        if (!pr.created) return 1;
        State s = st(def);
        long now = System.currentTimeMillis();
        double m = 1;
        if (pr.has(Skill.TNK_STALWART)) m *= 0.92;
        if (pr.has(Skill.TNK_LONE) && s.alone) m *= 0.85;
        if (now < s.ultUntil && pr.cls() == PlayerClass.TANK) m *= 0.4;
        // Bulwark: the best wall you're standing behind.
        double wall = 1;
        for (ServerPlayerEntity t : def.getServerWorld().getPlayers()) {
            State ts = states.get(t.getUuid());
            if (ts == null || now >= ts.wallUntil || t.squaredDistanceTo(def) > 8 * 8) continue;
            if (t == def) wall = Math.min(wall, 0.8);
            else if (AotRpg.PARTIES.same(t.getUuid(), def.getUuid())) wall = Math.min(wall, 0.7);
        }
        m *= wall;
        for (Zone z : zones) {
            if (z.world() == def.getWorld() && now < z.until() && z.at().squaredDistanceTo(def.getPos()) < 36
                && (z.owner().equals(def.getUuid()) || AotRpg.PARTIES.same(z.owner(), def.getUuid()))) {
                m *= 0.9;
                break;
            }
        }
        Entity src = source.getAttacker();
        LivingEntity root = TitanLevels.rootOf(src);
        Entity who = root != null ? root : src;
        if (who != null) {
            Mark t = taunts.get(who.getUuid());
            if (t != null && now < t.until() && server != null) {
                ServerPlayerEntity tank = server.getPlayerManager().getPlayer(t.owner());
                if (tank != null && pr(tank).has(Skill.TNK_WEAKEN)) m *= 0.75;
            }
        }
        return m;
    }

    /** A blocked hit primes Shield Bash. */
    public void blocked(ServerPlayerEntity def) {
        if (pr(def).has(Skill.TNK_BASH)) st(def).bashUntil = System.currentTimeMillis() + 2500;
    }

    /** Extra nape strikes a cut counts for (ultimates, Weak Spot, Nape Specialist); charges the ultimate. */
    public int napeBonus(ServerPlayerEntity p, LivingEntity titan) {
        Profile pr = pr(p);
        if (!pr.created) return 0;
        State s = st(p);
        long now = System.currentTimeMillis();
        int extra = 0;
        if (now < s.ultUntil && (pr.cls() == PlayerClass.INFANTRY || pr.cls() == PlayerClass.RECON)) extra++;
        Mark mk = reconMarks.get(titan.getUuid());
        if (mk != null && now < mk.until() && server != null) {
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(mk.owner());
            if (owner != null && pr(owner).has(Skill.RCN_WEAKSPOT)) extra++;
        }
        if (pr.has(Skill.NAPE_SPECIALIST) && p.getRandom().nextFloat() < 0.15f) {
            extra++;
            callout(p, "PRECISE CUT", Formatting.AQUA);
        }
        charge(p, 6);
        return Math.min(2, extra);
    }

    /** After any hit: ultimate charge, lifesteal, marks, combat healing, Lifeline. */
    public void afterHit(LivingEntity victim, DamageSource source, float taken) {
        if (taken <= 0) return;
        long now = System.currentTimeMillis();
        if (source.getAttacker() instanceof ServerPlayerEntity att && att != victim) {
            Profile pr = pr(att);
            State s = st(att);
            s.lastCombat = now;
            charge(att, Math.min(4f, 1 + taken * 0.15f));
            float steal = 0;
            if (pr.has(Skill.INF_BERSERK) && att.getHealth() < att.getMaxHealth() / 2) steal += 0.08f;
            if (pr.has(Skill.INF_LONE) && s.alone) steal += 0.10f;
            if (steal > 0) att.heal(Math.min(4f, taken * steal));
            if (pr.has(Skill.INF_COORD) && !(victim instanceof PlayerEntity)) coordMarks.put(victim.getUuid(), new Mark(att.getUuid(), now + 4000));
            if (pr.has(Skill.MED_COMBAT)) {
                ServerPlayerEntity hurt = att;
                float worst = att.getHealth() / att.getMaxHealth();
                for (ServerPlayerEntity a : squad(att, 10)) {
                    float f = a.getHealth() / a.getMaxHealth();
                    if (f < worst && !AotRpg.DOWNED.isDowned(a)) {
                        worst = f;
                        hurt = a;
                    }
                }
                heal(att, hurt, Math.min(4f, taken * 0.15f));
            }
        }
        if (victim instanceof ServerPlayerEntity def) {
            st(def).lastCombat = now;
            charge(def, Math.min(3f, taken * 0.4f) * (pr(def).cls() == PlayerClass.TANK ? 1.5f : 1f));
            if (def.isAlive() && def.getHealth() < def.getMaxHealth() * 0.25f) lifeline(def, now);
        }
    }

    private void lifeline(ServerPlayerEntity hurt, long now) {
        for (ServerPlayerEntity m : hurt.getServerWorld().getPlayers()) {
            if (m == hurt ? false : !AotRpg.PARTIES.same(m.getUuid(), hurt.getUuid())) continue;
            if (!pr(m).has(Skill.MED_LIFELINE) || m.squaredDistanceTo(hurt) > 16 * 16) continue;
            State s = st(m);
            if (now < s.lifelineReady) continue;
            s.lifelineReady = now + 20_000;
            heal(m, hurt, 8);
            fx(hurt, ParticleTypes.TOTEM_OF_UNDYING, 16, 0.4);
            sound(hurt, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.5f);
            hurt.sendMessage(Text.literal("LIFELINE · " + pr(m).name).formatted(Formatting.GREEN, Formatting.BOLD), true);
            return;
        }
    }

    /** A kill: ultimate charge, and Rending Rush resets on a kill soon after a rush. */
    public void onKill(ServerPlayerEntity killer, LivingEntity dead) {
        if (!pr(killer).created || dead == killer) return;
        charge(killer, AotRpg.isTitan(dead) ? 15 : 3);
        State s = st(killer);
        if (pr(killer).has(Skill.INF_REND) && System.currentTimeMillis() - s.rushAt < 4000) {
            s.ready[0] = 0;
            callout(killer, "RUSH RESET", Formatting.RED);
        }
    }

    /** A revive charges the reviver's ultimate. */
    public void revived(ServerPlayerEntity by) {
        charge(by, 25);
    }

    // ------------------------------------------------------------------ every tick

    public void tick(int ticks) {
        if (server == null) return;
        long now = System.currentTimeMillis();
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        for (ServerPlayerEntity p : players) {
            Profile pr = pr(p);
            if (!pr.created) continue;
            State s = st(p);
            if (ticks % 10 == 0) s.alone = computeAlone(p);
            boolean ult = now < s.ultUntil;
            PlayerClass c = pr.cls();
            ServerWorld w = p.getServerWorld();
            if (ult) {
                if (ticks % 4 == 0) {
                    int col = c.color;
                    w.spawnParticles(new DustParticleEffect(new Vector3f((col >> 16 & 255) / 255f, (col >> 8 & 255) / 255f, (col & 255) / 255f), 1.4f),
                        p.getX(), p.getY() + 1, p.getZ(), 4, 0.4, 0.6, 0.4, 0);
                }
                if (c == PlayerClass.TANK) {
                    if (Grab.grabbed(p)) p.stopRiding();
                    if (ticks % 40 == 0) provoke(p, false);
                }
                if (c == PlayerClass.RECON && ticks % 20 == 0) {
                    for (LivingEntity e : foes(p, 30)) {
                        e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, 2));
                        e.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 30, 0, false, false));
                    }
                }
            }
            if (ticks % 60 == 0) {
                if (pr.has(Skill.MED_AURA)) for (ServerPlayerEntity a : squad(p, 10)) if (!AotRpg.DOWNED.isDowned(a)) heal(p, a, 1);
                if (pr.has(Skill.TNK_LONE) && s.alone && p.getHealth() < p.getMaxHealth()) p.heal(1);
            }
            if (now < s.wallUntil && ticks % 10 == 0) ring(w, p.getPos(), 8, ParticleTypes.END_ROD, 24);
            if (pr.has(Skill.RCN_REPORT) && ticks % 20 == 0) {
                boolean marking = false;
                for (Mark mk : reconMarks.values()) if (mk.owner().equals(p.getUuid()) && now < mk.until()) marking = true;
                if (marking) for (ServerPlayerEntity a : squad(p, 32)) {
                    st(a).reportUntil = now + 1500;
                    a.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 30, 0, false, false, true));
                }
            }
            if (ticks % 4 == 0 && ServerPlayNetworking.canSend(p, Net.ClassHud.ID)) {
                int mask = 0;
                for (int i = 0; i < 3; i++) if (has(p, i)) mask |= 1 << i;
                ServerPlayNetworking.send(p, new Net.ClassHud(c.ordinal(), mask,
                    new int[] {(int) Math.max(0, s.ready[0] - now), (int) Math.max(0, s.ready[1] - now)},
                    new int[] {(int) s.max[0], (int) s.max[1]}, s.charge / CHARGE_MAX,
                    (int) Math.max(0, s.ultUntil - now), s.alone, inEvent(p)));
            }
        }
        // Blade Rush cutting through.
        for (Iterator<Map.Entry<UUID, Dash>> it = dashes.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            Dash d = e.getValue();
            if (p == null || --d.ticks < 0) {
                it.remove();
                continue;
            }
            Profile pr = pr(p);
            p.getServerWorld().spawnParticles(ParticleTypes.SWEEP_ATTACK, p.getX(), p.getY() + 1, p.getZ(), 1, 0.2, 0.2, 0.2, 0);
            for (LivingEntity f : p.getServerWorld().getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(2.2), f -> foe(p, f))) {
                if (!d.hit.add(f.getId())) continue;
                float dmg = 5 + pr.level * 0.12f;
                f.damage(p.getDamageSources().playerAttack(p), pr.has(Skill.INF_REND) ? dmg * 1.3f : dmg);
                fx(f, ParticleTypes.CRIT, 10, 0.4);
                if (pr.has(Skill.INF_REND)) bleeds.add(new Bleed(p.getServerWorld(), f.getUuid(), 4, 1 + pr.level * 0.04f));
            }
        }
        if (ticks % 20 == 0) {
            for (Iterator<Bleed> it = bleeds.iterator(); it.hasNext(); ) {
                Bleed b = it.next();
                Entity t = b.world.getEntity(b.target);
                if (!(t instanceof LivingEntity le) || !le.isAlive() || b.seconds-- <= 0) {
                    it.remove();
                    continue;
                }
                le.damage(b.world.getDamageSources().magic(), b.perSecond);
                b.world.spawnParticles(new DustParticleEffect(new Vector3f(0.6f, 0f, 0f), 1.2f), le.getX(), le.getY() + le.getHeight() / 2, le.getZ(), 6, 0.3, 0.3, 0.3, 0);
            }
            taunts.values().removeIf(t -> now > t.until());
            reconMarks.values().removeIf(t -> now > t.until());
            coordMarks.values().removeIf(t -> now > t.until());
            // Taunted foes keep turning on their tank.
            for (var t : taunts.entrySet()) {
                ServerPlayerEntity tank = server.getPlayerManager().getPlayer(t.getValue().owner());
                if (tank == null || !tank.isAlive()) continue;
                Entity e = tank.getServerWorld().getEntity(t.getKey());
                if (e instanceof MobEntity m && m.isAlive() && m.getTarget() != tank) m.setTarget(tank);
            }
        }
        // Sanctuaries.
        if (ticks % 10 == 0) {
            zones.removeIf(z -> now > z.until());
            for (Zone z : zones) {
                ring(z.world(), z.at(), 6, ParticleTypes.HAPPY_VILLAGER, 20);
                z.world().spawnParticles(ParticleTypes.GLOW, z.at().x, z.at().y + 0.5, z.at().z, 4, 2.5, 0.3, 2.5, 0);
                for (ServerPlayerEntity a : z.world().getPlayers()) {
                    if (a.getPos().squaredDistanceTo(z.at()) > 36) continue;
                    if (!a.getUuid().equals(z.owner()) && !AotRpg.PARTIES.same(z.owner(), a.getUuid())) continue;
                    a.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 40, 1, false, false, true));
                }
            }
        }
    }
}
