package com.pglol.aotrpg;

import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;

/**
 * The skill tree: three branches of four skills. A skill needs the one above it, a minimum
 * level and one skill point. Skill points: 1 at enlistment, then 1 every 5 levels.
 */
public enum Skill {
    SHARPENED_EDGE(Branch.BLADE, 0, 1, "Sharpened Edge", Items.IRON_SWORD, "+1 melee damage",
        EntityAttributes.GENERIC_ATTACK_DAMAGE, 1, false),
    NAPE_STRIKE(Branch.BLADE, 1, 10, "Nape Strike", Items.SHEARS, "+10% attack speed",
        EntityAttributes.GENERIC_ATTACK_SPEED, 0.10, true),
    TITAN_SLAYER(Branch.BLADE, 2, 20, "Titan Slayer", Items.BONE, "+25% XP from titans",
        null, 0, false),
    SPINNING_SLASH(Branch.BLADE, 3, 35, "Spinning Slash", Items.NETHERITE_SWORD, "+3 melee damage",
        EntityAttributes.GENERIC_ATTACK_DAMAGE, 3, false),

    LIGHT_STEP(Branch.MOBILITY, 0, 1, "Light Step", Items.FEATHER, "+5% movement speed",
        EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.05, true),
    GRAPPLE_INSTINCT(Branch.MOBILITY, 1, 10, "Grapple Instinct", Items.LEAD, "Take no fall damage for 3 more blocks",
        EntityAttributes.GENERIC_SAFE_FALL_DISTANCE, 3, false),
    SECOND_WIND(Branch.MOBILITY, 2, 20, "Second Wind", Items.SUGAR, "+30 maximum stamina",
        null, 0, false),
    WINGS_OF_FREEDOM(Branch.MOBILITY, 3, 35, "Wings of Freedom", Items.ELYTRA, "+10% speed and +15% jump",
        EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.10, true),

    THICK_SKIN(Branch.SURVIVAL, 0, 1, "Thick Skin", Items.LEATHER, "+2 armor",
        EntityAttributes.GENERIC_ARMOR, 2, false),
    IRON_WILL(Branch.SURVIVAL, 1, 10, "Iron Will", Items.IRON_INGOT, "+4 maximum health",
        EntityAttributes.GENERIC_MAX_HEALTH, 4, false),
    DEEP_BREATH(Branch.SURVIVAL, 2, 20, "Deep Breath", Items.GLASS_BOTTLE, "Stamina recovers 40% faster",
        null, 0, false),
    UNBREAKABLE(Branch.SURVIVAL, 3, 35, "Unbreakable", Items.NETHERITE_CHESTPLATE, "+6 health, +25% knockback resistance",
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

    public final Branch branch;
    public final int tier, level;
    public final String title, effect;
    public final Item icon;
    public final RegistryEntry<EntityAttribute> attribute;
    public final double amount;
    public final boolean multiply;

    Skill(Branch branch, int tier, int level, String title, Item icon, String effect,
          RegistryEntry<EntityAttribute> attribute, double amount, boolean multiply) {
        this.branch = branch;
        this.tier = tier;
        this.level = level;
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

    /** The skill that must be learned first, or null for the first of a branch. */
    public Skill previous() {
        for (Skill s : values()) if (s.branch == branch && s.tier == tier - 1) return s;
        return null;
    }

    public static Skill of(Branch b, int tier) {
        for (Skill s : values()) if (s.branch == b && s.tier == tier) return s;
        return null;
    }

    public static int pointsForLevel(int level) {
        return 1 + level / 5;
    }
}
