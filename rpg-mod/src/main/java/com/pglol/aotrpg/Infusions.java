package com.pglol.aotrpg;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Vector3f;

/**
 * Infused blades: some Epic and Legendary weapons, and every Mythic one, carry an element. It
 * colours the blade strongly (see the client's RarityTint), trails particles while it's held
 * (everyone sees them), and every hit it lands does something:
 *
 *   Frostborne   chills: slowed hard, snow bursting off them
 *   Emberheart   sets them burning
 *   Voidborn      weakens and drags them in toward you
 *   Stormcaller  sparks leap to up to two more enemies nearby
 *   Viperfang    poisons
 *   Dawnlight    a little of your health back on every hit
 *   Bloodsworn   drinks a share of the damage dealt as health
 *
 * Mythic infusions hit harder and last longer.
 */
public final class Infusions {
    private Infusions() {}

    public enum Infusion {
        FROST("Frostborne", 0x7FE0FF, Formatting.AQUA, "Hits chill and slow",
            new String[]{"Rimebitten", "Hoarfrost", "Glacier-kissed", "Frostfell"},
            new String[]{"Winterthorn", "Whitefang", "Rimecleaver", "Paleglass"},
            new String[]{"Absolute Zero", "The Long Winter", "Heart of Frostfell", "Ymir's Last Snow"}),
        EMBER("Emberheart", 0xFF7A20, Formatting.GOLD, "Hits set foes burning",
            new String[]{"Cinderbrand", "Ashen", "Kiln-forged", "Smoldering"},
            new String[]{"Pyreblade", "Sunscar", "The Wall-burner", "Emberfang"},
            new String[]{"Colossal Flame", "The Burning Marleyan Sky", "Hearth of Shiganshina", "Worldpyre"}),
        VOID("Voidborn", 0x6A2AB0, Formatting.DARK_PURPLE, "Hits weaken and drag foes in",
            new String[]{"Hollow", "Duskwrought", "Umbral", "Starless"},
            new String[]{"Nightfall", "The Quiet Dark", "Oblivion's Edge", "Eclipse"},
            new String[]{"Paths' End", "The Abyss That Watches", "Nullblade", "Endless Night"}),
        STORM("Stormcaller", 0xFFE84A, Formatting.YELLOW, "Hits spark to nearby foes",
            new String[]{"Crackling", "Thunderstruck", "Galvanic", "Static-kissed"},
            new String[]{"Skysplitter", "Thunderclap", "The Transformation Flash", "Arcfang"},
            new String[]{"Wrath of the Heavens", "The Colossal Strike", "Stormbreaker", "Lightning Made Steel"}),
        VENOM("Viperfang", 0x6AE04A, Formatting.GREEN, "Hits poison",
            new String[]{"Blighted", "Viper's", "Toxic", "Nettled"},
            new String[]{"Serpent's Kiss", "Mirewhisper", "Nightshade", "Spinal Fluid"},
            new String[]{"The Jaw's Venom", "Plaguebringer", "Zeke's Serum", "Rot of Ages"}),
        RADIANT("Dawnlight", 0xFFF4C0, Formatting.WHITE, "Hits restore your health",
            new String[]{"Gleaming", "Blessed", "Sunlit", "Hallowed"},
            new String[]{"Dawnbringer", "Wings of Freedom", "Lightkeeper", "Halo"},
            new String[]{"Hope of Humanity", "The Last Sunrise", "Dedicate Your Heart", "Wings of Liberty"}),
        BLOOD("Bloodsworn", 0xC01020, Formatting.DARK_RED, "Hits drink life",
            new String[]{"Crimson", "Blood-drinker", "Scarlet", "Sanguine"},
            new String[]{"Heartseeker", "The Red Oath", "Veinripper", "Bloodmoon"},
            new String[]{"Ymir's Blood", "The Crimson Rumbling", "Sovereign of Blood", "Vowkeeper"});

        public final String title, effect;
        public final int color;
        public final Formatting format;

        /** Epic blades take a word before their name; Legendary and Mythic ones get a name of their own. */
        final String[] epic, legendary, mythic;

        Infusion(String title, int color, Formatting format, String effect, String[] epic, String[] legendary, String[] mythic) {
            this.title = title;
            this.epic = epic;
            this.legendary = legendary;
            this.mythic = mythic;
            this.color = color;
            this.format = format;
            this.effect = effect;
        }
    }

    public static Infusion of(ItemStack s) {
        if (!Gear.isGear(s)) return null;
        String id = Gear.data(s).getString("infusion");
        if (id.isEmpty()) return null;
        try {
            return Infusion.valueOf(id);
        } catch (Exception e) {
            return null;
        }
    }

    /** At the roll: weapons only; a chance from Epic, always at Mythic. */
    static void roll(Random r, ItemStack s, Gear.Rarity rarity, NbtCompound g) {
        if (Gear.wornSlot(s) != null) return;
        float chance = switch (rarity) {
            case EPIC -> 0.25f;
            case LEGENDARY -> 0.5f;
            case MYTHIC -> 1f;
            default -> 0f;
        };
        if (r.nextFloat() >= chance) return;
        Infusion[] all = Infusion.values();
        g.putString("infusion", all[r.nextInt(all.length)].name());
    }

    /** A name worth the find. */
    static String name(Random r, Infusion inf, Gear.Rarity rarity, String item) {
        return switch (rarity) {
            case MYTHIC -> inf.mythic[r.nextInt(inf.mythic.length)];
            case LEGENDARY -> inf.legendary[r.nextInt(inf.legendary.length)];
            default -> inf.epic[r.nextInt(inf.epic.length)] + " " + item;
        };
    }

    private static boolean mythic(ItemStack s) {
        return "MYTHIC".equals(Gear.data(s).getString("rarity"));
    }

    private static boolean busy;

    public static void register() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((victim, source, base, taken, blocked) -> {
            if (busy || blocked || taken <= 0 || !(source.getAttacker() instanceof ServerPlayerEntity p) || victim == p) return;
            ItemStack w = p.getMainHandStack();
            Infusion inf = of(w);
            if (inf == null) {
                w = p.getOffHandStack();
                inf = of(w);
            }
            if (inf == null) return;
            hit(p, victim, inf, mythic(w), taken);
        });
    }

    private static void hit(ServerPlayerEntity p, LivingEntity v, Infusion inf, boolean mythic, float dealt) {
        ServerWorld w = p.getServerWorld();
        float k = mythic ? 1.6f : 1f;
        Vec3d at = v.getPos().add(0, v.getHeight() * 0.6, 0);
        double spread = Math.max(0.3, v.getWidth() * 0.4);
        switch (inf) {
            case FROST -> {
                v.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, (int) (50 * k), mythic ? 3 : 1));
                v.setFrozenTicks(Math.max(v.getFrozenTicks(), (int) (80 * k)));
                w.spawnParticles(ParticleTypes.SNOWFLAKE, at.x, at.y, at.z, (int) (18 * k), spread, spread, spread, 0.08);
                w.playSound(null, v.getBlockPos(), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 0.5f, 1.6f);
            }
            case EMBER -> {
                v.setOnFireFor(3 * k);
                w.spawnParticles(ParticleTypes.FLAME, at.x, at.y, at.z, (int) (14 * k), spread, spread, spread, 0.04);
                w.spawnParticles(ParticleTypes.LAVA, at.x, at.y, at.z, 3, spread, spread, spread, 0);
                w.playSound(null, v.getBlockPos(), SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.PLAYERS, 0.5f, 1.3f);
            }
            case VOID -> {
                v.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, (int) (60 * k), mythic ? 1 : 0));
                Vec3d pull = p.getPos().subtract(v.getPos()).normalize().multiply(v.getHeight() > 4 ? 0.15 : 0.45 * k);
                v.addVelocity(pull.x, 0.05, pull.z);
                v.velocityModified = true;
                w.spawnParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, (int) (30 * k), spread, spread, spread, 0.1);
                w.spawnParticles(ParticleTypes.SQUID_INK, at.x, at.y, at.z, 6, spread, spread, spread, 0.02);
                w.playSound(null, v.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.4f, 0.6f);
            }
            case STORM -> {
                int jumps = 0;
                busy = true;
                try {
                    for (LivingEntity o : w.getEntitiesByClass(LivingEntity.class, v.getBoundingBox().expand(5), e ->
                        e != v && e != p && e.isAlive() && (e instanceof HostileEntity || AotRpg.isTitan(e)))) {
                        if (jumps >= (mythic ? 3 : 2)) break;
                        o.damage(w.getDamageSources().playerAttack(p), 2 * k);
                        arc(w, at, o.getPos().add(0, o.getHeight() * 0.6, 0));
                        jumps++;
                    }
                } finally {
                    busy = false;
                }
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, (int) (20 * k), spread, spread, spread, 0.2);
                w.playSound(null, v.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.35f, 1.8f);
            }
            case VENOM -> {
                v.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, (int) (70 * k), mythic ? 1 : 0));
                w.spawnParticles(new DustParticleEffect(new Vector3f(0.4f, 0.9f, 0.3f), 1.1f), at.x, at.y, at.z, (int) (16 * k), spread, spread, spread, 0);
                w.spawnParticles(ParticleTypes.SNEEZE, at.x, at.y, at.z, 4, spread, spread, spread, 0.02);
            }
            case RADIANT -> {
                p.heal(mythic ? 2f : 1f);
                w.spawnParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, (int) (14 * k), spread, spread, spread, 0.06);
                w.spawnParticles(ParticleTypes.WAX_ON, p.getX(), p.getY() + 1, p.getZ(), 5, 0.3, 0.5, 0.3, 0.02);
            }
            case BLOOD -> {
                p.heal(Math.min(4, dealt * (mythic ? 0.2f : 0.12f)));
                w.spawnParticles(new DustParticleEffect(new Vector3f(0.7f, 0.02f, 0.05f), 1.4f), at.x, at.y, at.z, (int) (20 * k), spread, spread, spread, 0);
                w.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, at.x, at.y, at.z, 4, spread, spread, spread, 0.1);
            }
        }
    }

    /** A spark's path from one foe to the next. */
    private static void arc(ServerWorld w, Vec3d a, Vec3d b) {
        for (int i = 0; i <= 8; i++) {
            Vec3d q = a.lerp(b, i / 8.0);
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, q.x, q.y, q.z, 2, 0.1, 0.1, 0.1, 0);
        }
    }

    /** A few times a second: infused blades trail their element (everyone around sees it). */
    public static void trail(ServerPlayerEntity p, int ticks) {
        if (ticks % 4 != 0 || p.isSpectator() || p.isInvisible()) return;
        ItemStack main = p.getMainHandStack(), off = p.getOffHandStack();
        Infusion a = of(main), b = of(off);
        if (a == null && b == null) return;
        ServerWorld w = p.getServerWorld();
        double yaw = Math.toRadians(p.getBodyYaw());
        Vec3d side = new Vec3d(Math.cos(yaw), 0, Math.sin(yaw)), fwd = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3d base = p.getPos().add(0, 0.9, 0).add(fwd.multiply(0.35));
        if (a != null) puff(w, a, mythic(main), base.add(side.multiply(-0.4)));
        if (b != null) puff(w, b, mythic(off), base.add(side.multiply(0.4)));
    }

    private static void puff(ServerWorld w, Infusion inf, boolean mythic, Vec3d at) {
        int n = mythic ? 3 : 1;
        ParticleEffect fx = switch (inf) {
            case FROST -> ParticleTypes.SNOWFLAKE;
            case EMBER -> ParticleTypes.SMALL_FLAME;
            case VOID -> ParticleTypes.REVERSE_PORTAL;
            case STORM -> ParticleTypes.ELECTRIC_SPARK;
            case VENOM -> new DustParticleEffect(new Vector3f(0.4f, 0.9f, 0.3f), 0.8f);
            case RADIANT -> ParticleTypes.END_ROD;
            case BLOOD -> new DustParticleEffect(new Vector3f(0.6f, 0.02f, 0.05f), 0.9f);
        };
        w.spawnParticles(fx, at.x, at.y, at.z, n, 0.08, 0.2, 0.08, inf == Infusion.VOID ? 0.02 : 0.005);
        if (mythic && inf == Infusion.VOID) w.spawnParticles(ParticleTypes.SQUID_INK, at.x, at.y, at.z, 1, 0.05, 0.1, 0.05, 0);
        if (mythic && inf == Infusion.FROST) w.spawnParticles(ParticleTypes.WHITE_ASH, at.x, at.y, at.z, 2, 0.15, 0.2, 0.15, 0);
    }
}
