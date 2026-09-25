package com.pglol.aotrpg;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sword fighting. Hold the guard key with a blade drawn to block: hits from the front do no harm
 * but cost stamina (and push the attacker back); run out and the guard breaks. When two fighters
 * swing at each other at the same moment their blades often clash: neither is hurt, both are
 * thrown apart. Titans can't be fully blocked, only softened.
 */
public final class Guard {
    public static final int FX_BLOCK = 0, FX_CLASH = 1, FX_BREAK = 2;
    private static final long CLASH_WINDOW_MS = 420;
    private static final float CLASH_CHANCE = 0.6f;

    private record Swing(int target, long at) { }

    private final Map<UUID, Long> guarding = new HashMap<>();
    private final Map<UUID, Long> brokenUntil = new HashMap<>();
    private final Map<UUID, Swing> swings = new HashMap<>();

    public static boolean melee(ItemStack s) {
        if (s.isEmpty()) return false;
        if (s.getItem() instanceof SwordItem || s.getItem() instanceof AxeItem) return true;
        String path = Registries.ITEM.getId(s.getItem()).getPath();
        return Loadout.isGrip(s) || path.contains("sword") || path.contains("blade") || path.contains("katana");
    }

    public boolean guarding(ServerPlayerEntity p) {
        return guarding.containsKey(p.getUuid());
    }

    /** The guard key, pressed or released. */
    /** The optional guard key (unbound by default): held or released. */
    public void set(ServerPlayerEntity p, boolean on) {
        if (on) keyHeld.add(p.getUuid());
        else keyHeld.remove(p.getUuid());
    }

    private final java.util.Set<UUID> keyHeld = new java.util.HashSet<>();

    /** Holding right click with a blade (its block animation) is the guard. */
    private static boolean raisingBlade(ServerPlayerEntity p) {
        return p.isUsingItem() && melee(p.getActiveItem());
    }

    public void tick(ServerPlayerEntity p, int ticks) {
        boolean raising = raisingBlade(p);
        boolean want = (raising || keyHeld.contains(p.getUuid())) && melee(p.getMainHandStack()) && !p.isDead();
        long now = System.currentTimeMillis();
        if (want && !guarding(p) && brokenUntil.getOrDefault(p.getUuid(), 0L) <= now && !AotRpg.STAMINA.exhausted(p)) {
            guarding.put(p.getUuid(), now);
        }
        if (!want || AotRpg.STAMINA.exhausted(p)) {
            guarding.remove(p.getUuid());
            return;
        }
        if (!guarding(p)) return;
        AotRpg.STAMINA.hold(p);
        if (p.isSprinting()) p.setSprinting(false);
        // Raising the blade already slows you like any held use; the key guard needs its own slow.
        if (!raising && ticks % 10 == 0) p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 14, 1, false, false, false));
    }

    public void forget(UUID id) {
        guarding.remove(id);
        keyHeld.remove(id);
        brokenUntil.remove(id);
        swings.remove(id);
    }

    /** Is the attacker in front of the defender (within about 75 degrees of where they look)? */
    private static boolean inFront(LivingEntity defender, Entity attacker) {
        Vec3d look = defender.getRotationVec(1f).multiply(1, 0, 1).normalize();
        Vec3d to = attacker.getPos().subtract(defender.getPos()).multiply(1, 0, 1).normalize();
        return look.dotProduct(to) > 0.26;
    }

    private static void push(Entity e, Entity from, double strength) {
        Vec3d d = e.getPos().subtract(from.getPos()).multiply(1, 0, 1);
        if (d.lengthSquared() < 1e-4) d = new Vec3d(0, 0, 1);
        d = d.normalize().multiply(strength);
        e.addVelocity(d.x, 0.18, d.z);
        e.velocityModified = true;
    }

    private static void fx(ServerWorld w, int kind, Vec3d at, ServerPlayerEntity by) {
        String slot = kind == FX_CLASH ? "clash" : "block";
        String style = by == null ? "" : AotRpg.COSMETICS.selected(by, slot);
        Net.GuardFx msg = new Net.GuardFx(kind, at.x, at.y, at.z, style);
        for (ServerPlayerEntity o : PlayerLookup.around(w, at, 64)) {
            if (ServerPlayNetworking.canSend(o, Net.GuardFx.ID)) ServerPlayNetworking.send(o, msg);
        }
        switch (kind) {
            case FX_CLASH -> {
                w.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.6f, 1.7f);
                w.playSound(null, at.x, at.y, at.z, SoundEvents.ITEM_TRIDENT_HIT, SoundCategory.PLAYERS, 1f, 1.4f);
            }
            case FX_BREAK -> w.playSound(null, at.x, at.y, at.z, SoundEvents.ITEM_SHIELD_BREAK, SoundCategory.PLAYERS, 1f, 0.8f);
            default -> w.playSound(null, at.x, at.y, at.z, SoundEvents.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1f, 1.2f);
        }
    }

    public void register() {
        // Remember who swung at whom, for clashes.
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity sp && melee(sp.getMainHandStack())) {
                swings.put(sp.getUuid(), new Swing(entity.getId(), System.currentTimeMillis()));
            }
            return ActionResult.PASS;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            Entity src = source.getAttacker();
            if (src == null || src == entity || amount <= 0) return true;
            long now = System.currentTimeMillis();
            ServerWorld w = (ServerWorld) entity.getWorld();
            // Clash: both fighters swung at each other just now.
            if (entity instanceof ServerPlayerEntity def && src instanceof ServerPlayerEntity att && Combat.melee(source)
                && melee(att.getMainHandStack()) && melee(def.getMainHandStack()) && !guarding(def)) {
                Swing theirs = swings.get(def.getUuid());
                if (theirs != null && theirs.target() == att.getId() && now - theirs.at() < CLASH_WINDOW_MS
                    && att.getRandom().nextFloat() < CLASH_CHANCE) {
                    swings.remove(def.getUuid());
                    swings.remove(att.getUuid());
                    push(att, def, 0.9);
                    push(def, att, 0.9);
                    AotRpg.STAMINA.spend(att, 5);
                    AotRpg.STAMINA.spend(def, 5);
                    Vec3d mid = att.getEyePos().add(def.getEyePos()).multiply(0.5).subtract(0, 0.3, 0);
                    fx(w, FX_CLASH, mid, att);
                    att.sendMessage(Text.literal("CLASH").formatted(Formatting.GOLD, Formatting.BOLD), true);
                    def.sendMessage(Text.literal("CLASH").formatted(Formatting.GOLD, Formatting.BOLD), true);
                    return false;
                }
            }
            // Block: a guarding fighter facing the hit.
            if (!(entity instanceof ServerPlayerEntity def) || !guarding(def) || !inFront(def, src)) return true;
            boolean titan = AotRpg.isTitan(src);
            Profile dp = AotRpg.PROFILES.get(def.getUuid());
            float cost = (amount * (titan ? 3f : 5f) + 4) * (dp.has(Skill.BULWARK) ? 0.65f : 1f);
            Vec3d at = def.getEyePos().add(def.getRotationVec(1f).multiply(0.7)).subtract(0, 0.4, 0);
            if (AotRpg.STAMINA.spend(def, cost)) {
                fx(w, FX_BLOCK, at, def);
                AotRpg.ABILITIES.blocked(def);
                if (titan) {
                    // Titans are too strong to stop: the guard only softens the blow.
                    push(def, src, 0.8);
                    def.damage(w.getDamageSources().generic(), amount * (dp.has(Skill.BULWARK) ? 0.175f : 0.35f));
                    return false;
                }
                push(src, def, Combat.melee(source) ? 0.75 : 0);
                push(def, src, 0.25);
                return false;
            }
            // Out of stamina: the guard breaks and the hit lands at half strength.
            AotRpg.STAMINA.drain(def);
            guarding.remove(def.getUuid());
            def.stopUsingItem();
            brokenUntil.put(def.getUuid(), now + 2500);
            def.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 50, 2, false, false, false));
            def.sendMessage(Text.literal("Guard broken!").formatted(Formatting.RED, Formatting.BOLD), true);
            fx(w, FX_BREAK, at, def);
            def.damage(w.getDamageSources().generic(), amount * 0.5f);
            return false;
        });
    }
}
