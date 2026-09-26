package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Town etiquette and being seen. Townsfolk see in a 90 degree cone in front of them, out to 16
 * blocks (half that if you sneak, less in the dark, nothing while you're invisible), and only with
 * a clear line of sight; they hear you right beside them unless you sneak.
 *
 * Seeing you builds their awareness. Walking through town with a blade, sword or gun drawn makes
 * people suspicious: they stare ("?"), then call you out ("!"), then the Garrison puts a bounty
 * on you. Opening someone's chest in front of a witness is theft. A bounty makes merchants in town
 * charge more and makes you easier to recognise; pay it with /bounty pay.
 *
 * Players see who's watching: a mark over each aware person, and while sneaking their sight
 * cones on the minimap. Story stealth missions use the same eyes (seenBy).
 */
public final class Witness {
    public static final double RANGE = 16, CONE_COS = Math.cos(Math.toRadians(45));

    private static final class Eye {
        float level;
        boolean sees;
        long alertedAt;
    }

    private static final class State {
        final Map<Integer, Eye> eyes = new HashMap<>();
        long warnedAt, lastBounty, drawnSince;
        boolean sentEmpty = true;
    }

    private final Map<UUID, State> states = new HashMap<>();
    private MinecraftServer server;

    public void open(MinecraftServer server) {
        this.server = server;
        states.clear();
    }

    public void forget(UUID id) {
        states.remove(id);
    }

    private State st(ServerPlayerEntity p) {
        return states.computeIfAbsent(p.getUuid(), k -> new State());
    }

    /** In a town, city or the Underground: where etiquette applies. */
    public static boolean inTown(ServerPlayerEntity p) {
        if (p.getWorld() != p.getServer().getOverworld()) return false;
        if (AotRpg.PLACES.nearest(p.getX(), p.getZ(), 110, "town") != null) return true;
        Net.Area a = AotRpg.PLACES.nearest(p.getX(), p.getZ(), 262);
        return a != null && a.name().equals("Underground City") && p.getY() < 55;
    }

    /** A drawn weapon in either hand. */
    public static boolean armed(ServerPlayerEntity p) {
        for (ItemStack s : new ItemStack[] {p.getMainHandStack(), p.getOffHandStack()}) {
            if (s.isEmpty()) continue;
            if (Loadout.isGrip(s) || Loadout.isMelee(s) || Loadout.isRanged(s) || AotItems.isApgGun(s)) return true;
        }
        return false;
    }

    /** Townsfolk who can watch: villagers, traders and golems (the town watch). */
    private static boolean watcher(LivingEntity e) {
        return e.isAlive() && (e instanceof MerchantEntity || e instanceof IronGolemEntity);
    }

    /** Can this person see (or hear) the player right now? */
    public static boolean sees(LivingEntity npc, ServerPlayerEntity p) {
        if (p.isInvisible() || p.isSpectator()) return false;
        double d = npc.distanceTo(p);
        if (d < 2.5 && !p.isSneaking()) return true; // heard
        double range = RANGE * (p.isSneaking() ? 0.5 : 1);
        if (p.getServerWorld().getLightLevel(p.getBlockPos()) < 7) range *= 0.6;
        if (d > range) return false;
        float yaw = npc.getHeadYaw() * MathHelper.RADIANS_PER_DEGREE;
        Vec3d look = new Vec3d(-MathHelper.sin(yaw), 0, MathHelper.cos(yaw));
        Vec3d to = new Vec3d(p.getX() - npc.getX(), 0, p.getZ() - npc.getZ());
        if (to.lengthSquared() < 1e-4) return true;
        if (look.dotProduct(to.normalize()) < CONE_COS) return false;
        return npc.canSee(p);
    }

    /** How many people can see this player now (for story stealth). */
    public int seenBy(ServerPlayerEntity p) {
        State s = states.get(p.getUuid());
        if (s == null) return 0;
        int n = 0;
        for (Eye e : s.eyes.values()) if (e.sees) n++;
        return n;
    }

    public void tick(int ticks) {
        if (server == null || ticks % 5 != 0) return;
        long now = System.currentTimeMillis();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            Profile pr = AotRpg.PROFILES.get(p.getUuid());
            State s = st(p);
            if (!pr.created || p.isCreative() || p.isSpectator() || !inTown(p)) {
                if (!s.eyes.isEmpty() || !s.sentEmpty) {
                    s.eyes.clear();
                    send(p, s);
                }
                continue;
            }
            boolean armed = armed(p);
            if (armed && s.drawnSince == 0) s.drawnSince = now;
            if (!armed) s.drawnSince = 0;
            ServerWorld w = p.getServerWorld();
            List<LivingEntity> near = w.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(24), Witness::watcher);
            Map<Integer, Eye> next = new HashMap<>();
            float wanted = pr.bounty > 0 ? 1.5f : 1f;
            int alerted = 0;
            for (LivingEntity npc : near) {
                Eye e = s.eyes.getOrDefault(npc.getId(), new Eye());
                e.sees = sees(npc, p);
                if (e.sees) {
                    float close = 1 + (float) (1 - npc.distanceTo(p) / RANGE);
                    float cap = armed || pr.bounty > 0 ? 1f : 0.35f;
                    e.level = Math.min(cap, e.level + 0.07f * close * wanted * (armed ? 1.4f : 1f));
                } else {
                    e.level = Math.max(0, e.level - 0.04f);
                }
                if (e.level >= 0.3f && e.sees && npc instanceof MobEntity m) m.getLookControl().lookAt(p, 30, 30);
                if (e.level >= 1f) {
                    if (e.alertedAt == 0) e.alertedAt = now;
                    alerted++;
                } else if (e.level < 0.6f) {
                    e.alertedAt = 0;
                }
                if (e.level > 0.01f || p.isSneaking()) next.put(npc.getId(), e);
            }
            s.eyes.clear();
            s.eyes.putAll(next);
            if (alerted > 0 && armed) disturb(p, pr, s, now);
            send(p, s);
        }
    }

    /** Called out for a drawn weapon: a warning first, then a bounty while you keep it out. */
    private void disturb(ServerPlayerEntity p, Profile pr, State s, long now) {
        if (now - s.warnedAt > 60_000) {
            s.warnedAt = now;
            s.lastBounty = now;
            p.playSoundToPlayer(SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 1f, 0.9f);
            Notify.toast(p, Text.literal("\"Put that away!\"").formatted(Formatting.GOLD),
                Text.literal("Drawn weapons alarm the townsfolk · sheathe it (G) or face a fine"), 0xC9A53A, null, "witness");
            return;
        }
        if (now - s.lastBounty < 20_000 || now - s.warnedAt < 6000) return;
        s.lastBounty = now;
        crime(p, pr, 25, "Disturbing the peace");
    }

    /** A crime seen by townsfolk: a bounty in this part of the world. */
    public void crime(ServerPlayerEntity p, Profile pr, long amount, String what) {
        pr.bounty += amount;
        AotRpg.PROFILES.save(p.getUuid());
        p.playSoundToPlayer(SoundEvents.BLOCK_BELL_USE, SoundCategory.NEUTRAL, 0.8f, 0.8f);
        Notify.toast(p, Text.literal(what).formatted(Formatting.RED),
            Text.literal("Bounty " + pr.bounty + " Marks · merchants charge more · /bounty pay"), 0xC0463A, "minecraft:iron_bars", "bounty");
    }

    /** Opening a chest that isn't yours in town: theft if anyone is looking. Returns true if caught. */
    public boolean theft(ServerPlayerEntity p) {
        if (p.isCreative() || !inTown(p)) return false;
        State s = st(p);
        LivingEntity witness = null;
        for (LivingEntity npc : p.getServerWorld().getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(24), Witness::watcher)) {
            if (sees(npc, p)) {
                witness = npc;
                Eye e = s.eyes.computeIfAbsent(npc.getId(), k -> new Eye());
                e.level = 1;
                e.sees = true;
                e.alertedAt = System.currentTimeMillis();
            }
        }
        if (witness == null) return false;
        if (witness instanceof MobEntity m) m.getLookControl().lookAt(p, 60, 60);
        crime(p, AotRpg.PROFILES.get(p.getUuid()), 50, "Caught stealing!");
        send(p, s);
        return true;
    }

    public void pay(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (pr.bounty <= 0) {
            p.sendMessage(Text.literal("You have no bounty.").formatted(Formatting.GRAY), true);
            return;
        }
        if (!AotRpg.WALLET.spendMarks(p, pr.bounty)) {
            Notify.toast(p, Text.literal("Your bounty is " + pr.bounty + " Marks").formatted(Formatting.RED), Text.literal("Not enough Marks to pay it"), 0xC0463A, null, "bounty");
            return;
        }
        long paid = pr.bounty;
        pr.bounty = 0;
        AotRpg.PROFILES.save(p.getUuid());
        Notify.toast(p, Text.literal("Bounty paid").formatted(Formatting.GREEN), Text.literal("-" + paid + " Marks · your name is clear"), 0x5BD35B, null, "bounty");
    }

    private void send(ServerPlayerEntity p, State s) {
        if (!ServerPlayNetworking.canSend(p, Net.Watchers.ID)) return;
        List<Net.Watcher> list = new ArrayList<>();
        for (var e : s.eyes.entrySet()) {
            Eye eye = e.getValue();
            list.add(new Net.Watcher(e.getKey(), eye.level, (eye.sees ? 1 : 0) | (eye.alertedAt > 0 ? 2 : 0)));
        }
        if (list.isEmpty() && s.sentEmpty) return;
        s.sentEmpty = list.isEmpty();
        ServerPlayNetworking.send(p, new Net.Watchers(list, AotRpg.PROFILES.get(p.getUuid()).bounty));
    }
}
