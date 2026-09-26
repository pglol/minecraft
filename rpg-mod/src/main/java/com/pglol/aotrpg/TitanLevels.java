package com.pglol.aotrpg;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Titan levels and the nape. Every titan gets a level when first seen (the area's level, or the
 * nearby party's best, whichever is higher, plus a little), which scales its health and strength.
 * A strike to the nape only kills when the striker is strong enough: weak blades and low levels
 * need several strikes (shared by everyone fighting it) before the titan falls, and the count
 * resets if the titan is left alone for a few seconds. Big parties face tougher titans.
 */
public final class TitanLevels {
    private static final String LV = "aot_lv:", PARTY = "aot_pz:";
    private static final Identifier HP_MOD = Identifier.of("aot_rpg", "titan_level_hp");
    private static final Identifier DMG_MOD = Identifier.of("aot_rpg", "titan_level_dmg");
    private static final long STRIKE_MEMORY_MS = 8000, APPROVE_MS = 400;

    private static final class Nape {
        int strikes;
        long lastAt, approvedUntil;
    }

    private final Map<UUID, Nape> napes = new HashMap<>();

    /** Severed napes whose titan must fall, finished a tick later (after Danny's own nape kill). */
    private record Finish(LivingEntity titan, ServerPlayerEntity by, DamageSource source) { }

    private final List<Finish> finishing = new ArrayList<>();

    /** Nape, eye and limb hitboxes are separate entities in Danny's mod. */
    public static boolean part(Entity e) {
        String path = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
        return path.contains("nape") || path.contains("eye") || path.contains("grab") || path.contains("hand") || path.contains("leg")
            || path.contains("shell") || path.contains("dummy");
    }

    public static boolean nape(Entity e) {
        return Registries.ENTITY_TYPE.getId(e.getType()).getPath().contains("nape");
    }

    public static boolean root(Entity e) {
        return e instanceof LivingEntity && AotRpg.isTitan(e) && !part(e);
    }

    private static int tagInt(Entity e, String prefix) {
        for (String t : e.getCommandTags()) {
            if (t.startsWith(prefix)) {
                try {
                    return Integer.parseInt(t.substring(prefix.length()));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    public static int level(Entity e) {
        return tagInt(e, LV);
    }

    private static int partySize(Entity e) {
        return Math.max(1, tagInt(e, PARTY));
    }

    // ------------------------------------------------------------------ levels

    /** Once a second: level any new titans near players, and tell players about the ones around them. */
    public void tick(MinecraftServer server, int ticks) {
        if (!finishing.isEmpty()) {
            List<Finish> now = new ArrayList<>(finishing);
            finishing.clear();
            for (Finish f : now) finish(f);
        }
        if (ticks % 20 != 11) return;
        long now = System.currentTimeMillis();
        napes.values().removeIf(n -> now - n.lastAt > 60_000);
        swings.values().removeIf(s -> now - s.at() > 10_000);
        if (rated.size() > 4000) rated.clear();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            ServerWorld w = p.getServerWorld();
            List<Net.TitanTag> tags = new ArrayList<>();
            for (Entity e : w.getOtherEntities(p, new Box(p.getBlockPos()).expand(96, 96, 96), TitanLevels::root)) {
                // Levels follow who is around: re-rated every 20 s, but never mid-fight.
                Nape cut = napes.get(e.getUuid());
                boolean fighting = cut != null && now - cut.lastAt < STRIKE_MEMORY_MS;
                if (level(e) <= 0 || (!fighting && now - rated.getOrDefault(e.getUuid(), 0L) > 20_000)) assign((LivingEntity) e, p);
                unboost((LivingEntity) e);
                Nape n = napes.get(e.getUuid());
                int strikes = n != null && now - n.lastAt < STRIKE_MEMORY_MS ? n.strikes : 0;
                tags.add(new Net.TitanTag(e.getId(), level(e), strikes, needed(p, (LivingEntity) e)));
                if (tags.size() >= 64) break;
            }
            if (ServerPlayNetworking.canSend(p, Net.TitanTags.ID)) ServerPlayNetworking.send(p, new Net.TitanTags(tags));
        }
    }

    private final Map<UUID, Long> rated = new HashMap<>();

    private void assign(LivingEntity t, ServerPlayerEntity near) {
        rated.put(t.getUuid(), System.currentTimeMillis());
        t.getCommandTags().removeIf(tag -> tag.startsWith(LV) || tag.startsWith(PARTY));
        var r = t.getRandom();
        int area = AotRpg.PLACES.levelAt(t.getX(), t.getZ());
        int ref = AotRpg.PROFILES.get(near.getUuid()).level;
        int size = 1;
        Parties.Party party = AotRpg.PARTIES.of(near.getUuid());
        if (party != null) {
            size = 0;
            for (UUID m : party.members) {
                ServerPlayerEntity o = near.getServer().getPlayerManager().getPlayer(m);
                if (o != null && o.getWorld() == t.getWorld() && o.squaredDistanceTo(t) < 128 * 128) size++;
            }
            size = Math.max(1, Math.min(Parties.MAX, size));
            // Only members online and nearby raise the level (an offline veteran doesn't).
            for (UUID m : party.members) {
                ServerPlayerEntity o = near.getServer().getPlayerManager().getPlayer(m);
                if (o != null && o.getWorld() == t.getWorld() && o.squaredDistanceTo(t) < 128 * 128) ref = Math.max(ref, AotRpg.PROFILES.get(m).level);
            }
        }
        int lv = Math.max(area, ref) + Math.floorMod(t.getUuid().hashCode(), 3) + (TitanGuard.isShifter(t) ? 5 : 0) + (t.hasCustomName() ? 3 : 0);
        lv = Math.max(1, Math.min(99, lv));
        t.addCommandTag(LV + lv);
        t.addCommandTag(PARTY + size);
        // Strength grows with level. (Health stays as Danny's mod sets it: its nape blow is sized
        // to it; toughness comes from the nape strikes a titan takes instead.)
        EntityAttributeInstance d = t.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        if (d != null && !d.hasModifier(DMG_MOD)) {
            d.addPersistentModifier(new EntityAttributeModifier(DMG_MOD, 0.035 * lv, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    /** Titans levelled by the first version got extra health that outlasted the nape blow: take it back. */
    private static void unboost(LivingEntity t) {
        EntityAttributeInstance h = t.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (h != null && h.hasModifier(HP_MOD)) {
            h.removeModifier(HP_MOD);
            if (t.getHealth() > t.getMaxHealth()) t.setHealth(t.getMaxHealth());
        }
    }

    /** Who last swung a blade at each titan (reported by the client's slash), for napes hit without a named attacker. */
    private record Swing(ServerPlayerEntity by, long at, boolean nape) { }

    private final Map<UUID, Swing> swings = new HashMap<>();

    /** Who last cut this titan (within a few seconds), or null. */
    public ServerPlayerEntity lastStriker(Entity titan) {
        Swing s = swings.get(titan.getUuid());
        return s != null && System.currentTimeMillis() - s.at() < 4000 && !s.by().isRemoved() ? s.by() : null;
    }

    public void slashed(ServerPlayerEntity p, Entity target) {
        LivingEntity t = root(target) ? (LivingEntity) target : part(target) ? owner(target) : null;
        if (t != null) swings.put(t.getUuid(), new Swing(p, System.currentTimeMillis(), nape(target)));
    }

    /** A blade just struck this titan's nape (Danny's nape passes its hit on to the titan). */
    private boolean napeSlashed(Entity titan) {
        Swing s = swings.get(titan.getUuid());
        return s != null && s.nape() && System.currentTimeMillis() - s.at() < 400;
    }

    /** The player behind a nape hit: the named attacker, else whoever just slashed it, else the nearest fighter. */
    private ServerPlayerEntity striker(DamageSource source, LivingEntity titan, Entity hit) {
        if (source.getAttacker() instanceof ServerPlayerEntity p) return p;
        if (source.getSource() instanceof ServerPlayerEntity p) return p;
        Swing s = swings.get(titan.getUuid());
        if (s != null && System.currentTimeMillis() - s.at() < 1500 && s.by().isAlive()) return s.by();
        ServerPlayerEntity best = null;
        double bd = 16 * 16;
        for (ServerPlayerEntity p : ((ServerWorld) titan.getWorld()).getPlayers()) {
            if (p.isSpectator() || p.isCreative() && !p.hasPermissionLevel(2)) continue;
            double d = p.squaredDistanceTo(hit);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    /** How strong a striker is against titans: their level, and their weapon if they can use it. */
    private static double strength(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        double s = pr.level * 0.6;
        ItemStack w = p.getMainHandStack();
        if (Gear.isGear(w) && Gear.canUse(p, w)) {
            var g = Gear.data(w);
            int rarity = switch (g.getString("rarity")) {
                case "UNCOMMON" -> 1;
                case "RARE" -> 2;
                case "EPIC" -> 3;
                case "LEGENDARY" -> 4;
                default -> 0;
            };
            s += g.getInt("ilvl") * 0.5 + rarity * 3 + g.getInt("up") * 2 + Gear.power(w) * 20;
        }
        return s;
    }

    /** Nape strikes this player needs to fell this titan. */
    public static int needed(ServerPlayerEntity p, LivingEntity t) {
        int lv = Math.max(1, level(t));
        int n = 1 + (int) Math.round((lv + 6 - strength(p)) / 6.0);
        n = Math.max(1, Math.min(6, n));
        if (TitanGuard.isShifter(t)) n += 2;
        n += (partySize(t) - 1) / 2;
        return Math.max(1, Math.min(9, n));
    }

    // ------------------------------------------------------------------ the nape

    public void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !strike(entity, source, amount));
    }

    /** The titan a nape (or other part) belongs to. */
    private static LivingEntity owner(Entity part) {
        if (part.getVehicle() != null && root(part.getVehicle())) return (LivingEntity) part.getVehicle();
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : part.getWorld().getOtherEntities(part, part.getBoundingBox().expand(24), TitanLevels::root)) {
            if (!e.getBoundingBox().expand(4).contains(part.getPos())) continue;
            double d = e.squaredDistanceTo(part);
            if (d < bd) {
                bd = d;
                best = (LivingEntity) e;
            }
        }
        return best;
    }

    /**
     * A hit on a nape (or a killing blow on a titan). Returns true to stop it: the strike is
     * counted instead, until enough have landed.
     */
    public boolean strike(Entity hit, DamageSource source, float amount) {
        if (hit.getWorld().isClient || amount <= 0) return false;
        LivingEntity titan;
        if (part(hit)) {
            if (!nape(hit)) return false;
            titan = owner(hit);
        } else if (root(hit) && (amount >= ((LivingEntity) hit).getHealth() || napeSlashed(hit))) {
            titan = (LivingEntity) hit;
        } else {
            return false;
        }
        if (titan == null || !titan.isAlive() || TitanGuard.isShifter(titan) && titan.hasPassengers()) return false;
        ServerPlayerEntity p = striker(source, titan, hit);
        if (p == null) return false;
        long now = System.currentTimeMillis();
        Nape n = napes.computeIfAbsent(titan.getUuid(), k -> new Nape());
        if (now < n.approvedUntil) return false;
        if (level(titan) <= 0) assign(titan, p);
        if (now - n.lastAt > STRIKE_MEMORY_MS) n.strikes = 0;
        // One strike per swing: Danny's blades can report several hits for one cut.
        if (now - n.lastAt < 180) return true;
        n.strikes++;
        n.lastAt = now;
        int need = needed(p, titan);
        ServerWorld w = (ServerWorld) titan.getWorld();
        boolean kill = n.strikes >= need;
        send(p, titan, n.strikes, need, kill);
        if (kill) {
            n.approvedUntil = now + APPROVE_MS;
            n.strikes = 0;
            finishing.add(new Finish(titan, p, source));
            return false;
        }
        // Not deep enough: the titan staggers, steams, and the cut starts to close.
        w.spawnParticles(ParticleTypes.CLOUD, hit.getX(), hit.getY() + hit.getHeight() / 2, hit.getZ(), 8, 0.3, 0.3, 0.3, 0.02);
        w.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, hit.getX(), hit.getY() + hit.getHeight() / 2, hit.getZ(), 4, 0.2, 0.2, 0.2, 0.1);
        w.playSound(null, hit.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1f, 0.8f);
        titan.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, 2, false, false, false));
        return true;
    }

    /**
     * A severed nape always kills: Danny's nape deals a fixed blow, which falls short of titans made
     * tougher by their level, so whatever health is left goes too (credited to the striker).
     */
    private void finish(Finish f) {
        LivingEntity t = f.titan();
        if (!t.isAlive() || t.isRemoved()) return;
        Nape n = napes.computeIfAbsent(t.getUuid(), k -> new Nape());
        n.approvedUntil = System.currentTimeMillis() + APPROVE_MS;
        DamageSource src = f.by().isAlive() ? f.by().getDamageSources().playerAttack(f.by()) : f.source();
        t.damage(src, t.getHealth() + 1000f);
        if (t.isAlive()) {
            // Titans immune to ordinary blows: end it directly, still crediting the striker.
            t.setHealth(0);
            t.onDeath(src);
        }
    }

    private static void send(ServerPlayerEntity p, LivingEntity titan, int strikes, int need, boolean kill) {
        Net.NapeHit msg = new Net.NapeHit(titan.getId(), strikes, need, kill);
        for (ServerPlayerEntity o : PlayerLookup.around((ServerWorld) titan.getWorld(), titan.getPos(), 64)) {
            if ((o == p || AotRpg.PARTIES.same(p.getUuid(), o.getUuid())) && ServerPlayNetworking.canSend(o, Net.NapeHit.ID)) {
                ServerPlayNetworking.send(o, msg);
            }
        }
    }
}
