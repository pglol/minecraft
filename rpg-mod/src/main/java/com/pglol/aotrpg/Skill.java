package com.pglol.aotrpg;

import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;

/**
 * The skill trees: three branches, six tiers deep, forking twice. A skill needs a skill of the
 * tier above in its branch, a minimum level and its point cost; deeper skills cost more, so going
 * far down one branch leaves little for the others. Abilities marked ✦ trigger in combat and grow
 * stronger with your level.
 *
 * Skill points: 1 at enlistment, then 1 every 3 levels (34 at level 100). One full path down a
 * branch costs 12; a whole branch with both forks 17.
 */
public enum Skill {
    // ---- Blade
    SHARPENED_EDGE(Branch.BLADE, 0, 0, 1, 1, "Sharpened Edge", Items.IRON_SWORD, "+1 melee damage",
        EntityAttributes.GENERIC_ATTACK_DAMAGE, 1, false),
    NAPE_STRIKE(Branch.BLADE, 1, 0, 5, 1, "Nape Strike", Items.SHEARS, "+10% attack speed",
        EntityAttributes.GENERIC_ATTACK_SPEED, 0.10, true),
    FLURRY(Branch.BLADE, 2, -1, 10, 2, "Flurry", Items.GOLDEN_SWORD, "✦ Every 4th hit in a row lands for +40% damage (+0.6% per level)",
        null, 0, false),
    EXECUTIONER(Branch.BLADE, 2, 1, 10, 2, "Executioner", Items.IRON_AXE, "✦ +30% damage to foes below 30% health (+0.4% per level)",
        null, 0, false),
    TITAN_SLAYER(Branch.BLADE, 3, 0, 18, 2, "Titan Slayer", Items.BONE, "+15% damage to titans, +25% XP from them",
        null, 0, false),
    RIPOSTE(Branch.BLADE, 4, -1, 26, 3, "Riposte", Items.SHIELD, "✦ After you block a hit, your next strike within 2s deals double damage and heals you",
        null, 0, false),
    BLOODLUST(Branch.BLADE, 4, 1, 26, 3, "Bloodlust", Items.REDSTONE, "✦ Killing blows restore 25% stamina and grant Strength for 5s",
        null, 0, false),
    SPINNING_SLASH(Branch.BLADE, 5, 0, 35, 3, "Spinning Slash", Items.NETHERITE_SWORD, "+3 melee damage. ✦ 20% of hits sweep all foes around you for 60%",
        EntityAttributes.GENERIC_ATTACK_DAMAGE, 3, false),

    // ---- Mobility
    LIGHT_STEP(Branch.MOBILITY, 0, 0, 1, 1, "Light Step", Items.FEATHER, "+5% movement speed",
        EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.05, true),
    GRAPPLE_INSTINCT(Branch.MOBILITY, 1, 0, 5, 1, "Grapple Instinct", Items.LEAD, "Take no fall damage for 3 more blocks",
        EntityAttributes.GENERIC_SAFE_FALL_DISTANCE, 3, false),
    EVASION(Branch.MOBILITY, 2, -1, 10, 2, "Evasion", Items.PHANTOM_MEMBRANE, "✦ 8% chance to dodge a melee hit entirely (+0.1% per level)",
        null, 0, false),
    MOMENTUM(Branch.MOBILITY, 2, 1, 10, 2, "Momentum", Items.RABBIT_FOOT, "✦ Hits while flying fast deal +20% damage (+0.3% per level)",
        null, 0, false),
    SECOND_WIND(Branch.MOBILITY, 3, 0, 18, 2, "Second Wind", Items.SUGAR, "+30 maximum stamina",
        null, 0, false),
    AFTERIMAGE(Branch.MOBILITY, 4, -1, 26, 3, "Afterimage", Items.ENDER_EYE, "✦ Falling below 30% health: vanish and dash away (Speed II, invisible 3s; 60s cooldown)",
        null, 0, false),
    AERIAL_ACE(Branch.MOBILITY, 4, 1, 26, 3, "Aerial Ace", Items.ELYTRA, "✦ Strikes from the air deal +25% damage (+0.3% per level)",
        null, 0, false),
    WINGS_OF_FREEDOM(Branch.MOBILITY, 5, 0, 35, 3, "Wings of Freedom", Items.FIREWORK_ROCKET, "+10% speed, +15% jump. ✦ Kills give Speed and Jump Boost for 6s",
        EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.10, true),

    // ---- Survival
    THICK_SKIN(Branch.SURVIVAL, 0, 0, 1, 1, "Thick Skin", Items.LEATHER, "+2 armor",
        EntityAttributes.GENERIC_ARMOR, 2, false),
    IRON_WILL(Branch.SURVIVAL, 1, 0, 5, 1, "Iron Will", Items.IRON_INGOT, "+4 maximum health",
        EntityAttributes.GENERIC_MAX_HEALTH, 4, false),
    BULWARK(Branch.SURVIVAL, 2, -1, 10, 2, "Bulwark", Items.IRON_DOOR, "Blocking costs 35% less stamina; titan hits through your guard are halved again",
        null, 0, false),
    FIELD_MEDIC(Branch.SURVIVAL, 2, 1, 10, 2, "Field Medic", Items.GLISTERING_MELON_SLICE, "✦ Kills heal you for 2 (+1 per 20 levels)",
        null, 0, false),
    DEEP_BREATH(Branch.SURVIVAL, 3, 0, 18, 2, "Deep Breath", Items.GLASS_BOTTLE, "Stamina recovers 40% faster",
        null, 0, false),
    LAST_STAND(Branch.SURVIVAL, 4, -1, 26, 3, "Last Stand", Items.TOTEM_OF_UNDYING, "✦ A killing blow leaves you at 1 health with Resistance for 3s (90s cooldown)",
        null, 0, false),
    RETALIATION(Branch.SURVIVAL, 4, 1, 26, 3, "Retaliation", Items.CACTUS, "✦ 25% of melee hits on you strike back for 35% (+0.3% per level)",
        null, 0, false),
    UNBREAKABLE(Branch.SURVIVAL, 5, 0, 35, 3, "Unbreakable", Items.NETHERITE_CHESTPLATE, "+6 health, +25% knockback resistance. Take 15% less damage below half health",
        EntityAttributes.GENERIC_MAX_HEALTH, 6, false);

    public enum Branch {
        BLADE("Blade", 0xFFC0463A), MOBILITY("Mobility", 0xFF5E9A4A), SURVIVAL("Survival", 0xFF4A74B0);

        public final String title;
        public final int color;

        Branch(String title, int color) {
            this.title = title;
            this.color = color;
        }
    }

    public static final int TIERS = 6;
    public static final int MAX_RESETS = 3;

    public final Branch branch;
    /** Row (0 at the top) and lane (-1 left fork, 0 the trunk, 1 right fork). */
    public final int tier, lane, level, cost;
    public final String title, effect;
    public final Item icon;
    public final RegistryEntry<EntityAttribute> attribute;
    public final double amount;
    public final boolean multiply;

    Skill(Branch branch, int tier, int lane, int level, int cost, String title, Item icon, String effect,
          RegistryEntry<EntityAttribute> attribute, double amount, boolean multiply) {
        this.branch = branch;
        this.tier = tier;
        this.lane = lane;
        this.level = level;
        this.cost = cost;
        this.title = title;
        this.icon = icon;
        this.effect = effect;
        this.attribute = attribute;
        this.amount = amount;
        this.multiply = multiply;
    }

    public EntityAttributeModifier.Operation op() {
        return multiply ? EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE : EntityAttributeModifier.Operation.ADD_VALUE;
    }

    /** Is the skill above this one learned (any skill of the previous tier in the branch)? */
    public boolean unlockedBy(java.util.function.Predicate<Skill> has) {
        if (tier == 0) return true;
        for (Skill s : values()) if (s.branch == branch && s.tier == tier - 1 && has.test(s)) return true;
        return false;
    }

    public static java.util.List<Skill> at(Branch b, int tier) {
        java.util.List<Skill> out = new java.util.ArrayList<>();
        for (Skill s : values()) if (s.branch == b && s.tier == tier) out.add(s);
        return out;
    }

    public static int pointsForLevel(int level) {
        return 1 + level / 3;
    }

    /** Marks for the n-th reset (0-based): the first is free. */
    public static long resetCost(int used) {
        return used <= 0 ? 0 : used == 1 ? 2500 : 7500;
    }
}
