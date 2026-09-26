package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Downed, not dead. A killing blow sometimes leaves you bleeding on the ground instead: you crawl,
 * you can't fight, and you have 25 seconds (longer while you press your wounds, holding Sneak)
 * for a comrade to crouch beside you for 4 seconds and bring you back. Titans usually bite hard
 * enough to kill outright; Second Wind on armor makes going down, rather than dying, likelier.
 */
public final class Downed {
    public static final float BLEED_S = 25, REVIVE_S = 4;

    private static final class State {
        float left = BLEED_S;
        float revive;
        UUID reviver;
        DamageSource cause;
        long since;
    }

    private final Map<UUID, State> downed = new HashMap<>();
    private MinecraftServer server;
    /** Set while we finish off a player who bled out, so the death goes through. */
    private boolean finishing;

    public void open(MinecraftServer server) {
        this.server = server;
        downed.clear();
    }

    public boolean isDowned(ServerPlayerEntity p) {
        return downed.containsKey(p.getUuid());
    }

    /** Second Wind from worn gear: extra chance of going down instead of dying. */
    public static double secondWind(ServerPlayerEntity p) {
        double s = 0;
        for (var slot : new net.minecraft.entity.EquipmentSlot[] {net.minecraft.entity.EquipmentSlot.HEAD, net.minecraft.entity.EquipmentSlot.CHEST,
            net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.FEET}) {
            var st = p.getEquippedStack(slot);
            if (Gear.isGear(st) && Gear.canUse(p, st)) s += Gear.data(st).getDouble("secondwind");
        }
        return s;
    }

    /**
     * A killing blow: returns false (and downs the player) when they're only knocked down. A bite
     * or blow far past what it takes to kill is less likely to leave you alive.
     */
    public boolean allowDeath(ServerPlayerEntity p, DamageSource source, float amount) {
        if (finishing || isDowned(p) || p.isCreative() || p.isSpectator() || !AotRpg.PROFILES.get(p.getUuid()).created) return true;
        if (source.isOf(net.minecraft.entity.damage.DamageTypes.OUT_OF_WORLD) || source.isOf(net.minecraft.entity.damage.DamageTypes.GENERIC_KILL)) return true;
        boolean titan = TitanLevels.rootOf(source.getAttacker()) != null || (p.getVehicle() != null && AotRpg.isTitan(p.getVehicle()));
        double overkill = amount / Math.max(1f, p.getMaxHealth());
        double chance = titan ? (overkill > 2 ? 0.12 : 0.30) : (overkill > 2 ? 0.35 : 0.60);
        chance += secondWind(p);
        if (p.getRandom().nextDouble() >= Math.min(0.9, chance)) return true;
        down(p, source);
        return false;
    }

    private void down(ServerPlayerEntity p, DamageSource cause) {
        State s = new State();
        s.cause = cause;
        s.since = System.currentTimeMillis();
        downed.put(p.getUuid(), s);
        p.setHealth(1);
        p.stopRiding();
        p.clearStatusEffects();
        p.setInvulnerable(true);
        ServerWorld w = p.getServerWorld();
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 1f, 0.6f);
        w.spawnParticles(new DustParticleEffect(new Vector3f(0.6f, 0.02f, 0.02f), 1.6f), p.getX(), p.getY() + 0.5, p.getZ(), 30, 0.4, 0.3, 0.4, 0.02);
        Notify.toast(p, Text.literal("DOWNED").formatted(Formatting.DARK_RED, Formatting.BOLD),
            Text.literal("Hold Sneak to press your wounds. A comrade can bring you back."), 0xB01010, "minecraft:redstone", "downed");
        for (ServerPlayerEntity o : PlayerLookup.around(w, p.getPos(), 96)) {
            if (o == p) continue;
            Notify.toast(o, Text.literal(AotRpg.PROFILES.get(p.getUuid()).name + " is down!").formatted(Formatting.RED),
                Text.literal("Crouch beside them for 4 seconds to revive"), 0xC0463A, "minecraft:golden_apple", "downed:" + p.getUuidAsString());
        }
    }

    /** Every tick: bleeding, crawling, the revive, and the view of it for everyone near. */
    public void tick(int ticks) {
        if (server == null || downed.isEmpty()) return;
        List<Net.DownedEntry> view = new ArrayList<>();
        for (var it = downed.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            State s = e.getValue();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null || p.isDead()) {
                it.remove();
                continue;
            }
            ServerWorld w = p.getServerWorld();
            // Crawling: low, slow, no fighting.
            p.setPose(EntityPose.SWIMMING);
            if (ticks % 10 == 0) {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 20, 3, false, false, false));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 20, 4, false, false, false));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 20, 4, false, false, false));
            }
            boolean pressing = p.isSneaking();
            // Pressing your wounds slows the bleeding (to about 40 seconds in all).
            s.left -= pressing ? 0.03f : 0.05f;
            // Blood: drips while you lie there, spurts as you crawl.
            if (ticks % (pressing ? 8 : 3) == 0) {
                w.spawnParticles(new DustParticleEffect(new Vector3f(0.55f, 0.02f, 0.02f), 1.2f), p.getX(), p.getY() + 0.3, p.getZ(),
                    pressing ? 1 : 3, 0.25, 0.05, 0.25, 0.01);
            }
            if (ticks % 20 == 0 && !pressing) w.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, p.getX(), p.getY() + 0.4, p.getZ(), 1, 0.1, 0.1, 0.1, 0.01);
            // A comrade crouched close beside you brings you back.
            ServerPlayerEntity helper = null;
            for (ServerPlayerEntity o : w.getPlayers()) {
                if (o == p || o.isSpectator() || isDowned(o) || !o.isSneaking() || o.squaredDistanceTo(p) > 2.8 * 2.8) continue;
                helper = o;
                break;
            }
            if (helper != null) {
                s.reviver = helper.getUuid();
                s.revive += 1f / (REVIVE_S * 20);
                if (ticks % 4 == 0) {
                    w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 0.4, p.getZ(), 2, 0.4, 0.3, 0.4, 0.02);
                    w.spawnParticles(new DustParticleEffect(new Vector3f(1f, 0.85f, 0.35f), 1f), helper.getX(), helper.getY() + 1, helper.getZ(), 2, 0.3, 0.3, 0.3, 0);
                }
                if (s.revive >= 1) {
                    it.remove();
                    revive(p, helper);
                    continue;
                }
            } else {
                s.reviver = null;
                s.revive = Math.max(0, s.revive - 0.02f);
            }
            if (s.left <= 0) {
                it.remove();
                bleedOut(p, s);
                continue;
            }
            view.add(new Net.DownedEntry(p.getId(), s.left, BLEED_S, s.revive, pressing,
                s.reviver == null ? "" : AotRpg.PROFILES.get(s.reviver).name));
        }
        if (ticks % 2 != 0) return;
        Net.DownedView msg = new Net.DownedView(view);
        for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
            if (ServerPlayNetworking.canSend(o, Net.DownedView.ID)) ServerPlayNetworking.send(o, msg);
        }
    }

    /** Brought back: a burst of golden light, some health, a moment's protection. */
    private void revive(ServerPlayerEntity p, ServerPlayerEntity by) {
        p.setInvulnerable(false);
        p.setPose(EntityPose.STANDING);
        p.clearStatusEffects();
        p.setHealth(Math.max(4, p.getMaxHealth() * 0.35f));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 100, 1));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 100, 2));
        ServerWorld w = p.getServerWorld();
        w.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1, p.getZ(), 80, 0.5, 0.8, 0.5, 0.35);
        w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1.5, p.getZ(), 40, 0.2, 1.5, 0.2, 0.05);
        w.spawnParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 0.5, p.getZ(), 20, 0.6, 0.4, 0.6, 0);
        w.playSound(null, p.getBlockPos(), SoundEvents.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 0.7f, 1.3f);
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.8f, 1.6f);
        String me = AotRpg.PROFILES.get(p.getUuid()).name, them = AotRpg.PROFILES.get(by.getUuid()).name;
        Notify.toast(p, Text.literal("Revived!").formatted(Formatting.GOLD, Formatting.BOLD),
            Text.literal(them + " brought you back"), 0xF2C14E, "minecraft:totem_of_undying", "downed");
        Notify.toast(by, Text.literal("You saved " + me).formatted(Formatting.GOLD, Formatting.BOLD),
            Text.literal("+" + 40 + " Marks · pass XP"), 0xF2C14E, "minecraft:totem_of_undying", "downed");
        AotRpg.WALLET.earn(by, 40, "a revive");
        AotRpg.SEASON.xp(by, 120);
        AotRpg.REGIMENTS.gain(by, 15);
        sendClear(p);
    }

    private void bleedOut(ServerPlayerEntity p, State s) {
        p.setInvulnerable(false);
        finishing = true;
        try {
            DamageSource src = s.cause != null ? s.cause : p.getDamageSources().generic();
            p.setHealth(0);
            p.onDeath(src);
        } finally {
            finishing = false;
        }
        sendClear(p);
    }

    private void sendClear(ServerPlayerEntity p) {
        Net.DownedView msg = new Net.DownedView(List.of());
        if (downed.isEmpty()) {
            for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
                if (ServerPlayNetworking.canSend(o, Net.DownedView.ID)) ServerPlayNetworking.send(o, msg);
            }
        }
    }

    /** Leaving while downed counts as bleeding out (no dodging death by logging off). */
    public void forget(ServerPlayerEntity p) {
        State s = downed.remove(p.getUuid());
        if (s != null) {
            p.setInvulnerable(false);
            finishing = true;
            try {
                p.setHealth(0);
                p.onDeath(s.cause != null ? s.cause : p.getDamageSources().generic());
            } finally {
                finishing = false;
            }
        }
    }
}
