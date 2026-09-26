package com.pglol.aotrpg;

import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;

/**
 * The skill trees. Three general branches anyone can learn (Blade, Mobility, Survival) and a tree
 * for each class (Infantry, Tank, Medic, Recon), all eight tiers deep and forking. A skill needs a
 * skill of the tier above in its branch, a minimum level and its point cost. Every tree is open to
 * everyone: grow however you like. The role you choose (any time out of combat) decides which
 * class's actives sit on Z / X and its ultimate on V, and is what others see on you. Abilities
 * marked ✦ trigger in combat; ▶ are actives, ★ the ultimate.
 *
 * Skill points: 1 at enlistment, 1 every 2 levels and 1 more every 10 (61 at level 100). A whole
 * class tree costs 26, a whole general branch 27.
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

    NAPE_SPECIALIST(Branch.BLADE, 6, -1, 45, 3, "Nape Specialist", Items.DIAMOND_SWORD, "✦ 15% chance a nape strike counts twice",
        null, 0, false),
    BLADE_DANCER(Branch.BLADE, 6, 1, 45, 3, "Blade Dancer", Items.GOLDEN_SWORD, "+15% attack speed. ✦ Winning a clash restores 20 stamina",
        EntityAttributes.GENERIC_ATTACK_SPEED, 0.15, true),
    ACKERMAN_INSTINCT(Branch.BLADE, 7, 0, 55, 4, "Ackerman Instinct", Items.NETHERITE_SWORD, "+2 melee damage. ✦ Your first hit on each new foe deals +50%",
        EntityAttributes.GENERIC_ATTACK_DAMAGE, 2, false),

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

    GAS_DISCIPLINE(Branch.MOBILITY, 6, -1, 45, 3, "Gas Discipline", Items.GLASS_BOTTLE, "Everything costs 20% less stamina",
        null, 0, false),
    SLIPSTREAM(Branch.MOBILITY, 6, 1, 45, 3, "Slipstream", Items.WIND_CHARGE, "✦ Take 15% less damage while moving fast",
        null, 0, false),
    FREEDOMS_WINGS(Branch.MOBILITY, 7, 0, 55, 4, "Freedom's Wings", Items.ELYTRA, "+8% speed. ✦ Dodging grants Speed III for 2s and cuts your ability cooldowns by 2s",
        EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.08, true),

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
        EntityAttributes.GENERIC_MAX_HEALTH, 6, false),
    BATTLE_HARDENED(Branch.SURVIVAL, 6, -1, 45, 3, "Battle Hardened", Items.IRON_CHESTPLATE, "+4 armor, +2 armor toughness",
        EntityAttributes.GENERIC_ARMOR, 4, false),
    RESILIENCE(Branch.SURVIVAL, 6, 1, 45, 3, "Resilience", Items.GOLDEN_APPLE, "✦ +15% chance to be downed instead of killed; bleed out 10s slower",
        null, 0, false),
    INDOMITABLE(Branch.SURVIVAL, 7, 0, 55, 4, "Indomitable", Items.ENCHANTED_GOLDEN_APPLE, "+8 health. ✦ Last Stand recovers twice as fast",
        EntityAttributes.GENERIC_MAX_HEALTH, 8, false),

    // ==== Infantry (DPS)
    INF_RUSH(Branch.INFANTRY, 0, 0, 1, 1, "Blade Rush", Items.IRON_SWORD, "▶ Z: Dash forward, cutting every foe you pass (10s cooldown)",
        null, 0, false),
    INF_KEEN(Branch.INFANTRY, 1, -1, 3, 1, "Keen Edge", Items.FLINT, "+10% melee damage",
        EntityAttributes.GENERIC_ATTACK_DAMAGE, 0.10, true),
    INF_HUNTER(Branch.INFANTRY, 1, 1, 3, 1, "Titan Hunter", Items.BONE, "✦ +15% damage to titans",
        null, 0, false),
    INF_RHYTHM(Branch.INFANTRY, 2, 0, 6, 2, "Combat Rhythm", Items.CLOCK, "✦ Each hit within 3s builds +3% damage, up to +30%",
        null, 0, false),
    INF_REND(Branch.INFANTRY, 3, -1, 10, 2, "Rending Rush", Items.REDSTONE, "✦ Blade Rush makes foes bleed for 4s; a kill resets it",
        null, 0, false),
    INF_QUICK(Branch.INFANTRY, 3, 1, 10, 2, "Relentless", Items.SUGAR, "✦ Blade Rush and War Cry recharge 30% faster",
        null, 0, false),
    INF_WARCRY(Branch.INFANTRY, 4, 0, 15, 3, "War Cry", Items.GOAT_HORN, "▶ X: You and your squad within 12 blocks deal +20% damage and gain Speed for 8s (30s cooldown)",
        null, 0, false),
    INF_BERSERK(Branch.INFANTRY, 5, -1, 22, 3, "Berserker", Items.BLAZE_POWDER, "✦ Below half health: +25% damage and 8% lifesteal",
        null, 0, false),
    INF_COORD(Branch.INFANTRY, 5, 1, 22, 3, "Coordinated Strike", Items.TARGET, "✦ Your hits mark foes for 4s: your squad deals +12% to them",
        null, 0, false),
    INF_LONE(Branch.INFANTRY, 6, 0, 28, 3, "Lone Blade", Items.IRON_NUGGET, "✦ With no ally within 40 blocks: +20% damage and 10% lifesteal",
        null, 0, false),
    INF_ULT(Branch.INFANTRY, 7, 0, 35, 5, "Humanity's Strongest", Items.NETHERITE_SWORD, "★ V: 10s of +60% damage and Speed II; every nape strike counts double",
        null, 0, false),

    // ==== Tank
    TNK_TAUNT(Branch.TANK, 0, 0, 1, 1, "Provoke", Items.SHIELD, "▶ Z: Titans and foes within 16 blocks turn on you for 6s; gain Resistance (14s cooldown)",
        null, 0, false),
    TNK_PLATED(Branch.TANK, 1, -1, 3, 1, "Plated", Items.IRON_INGOT, "+3 armor",
        EntityAttributes.GENERIC_ARMOR, 3, false),
    TNK_HARDY(Branch.TANK, 1, 1, 3, 1, "Hardy", Items.COOKED_BEEF, "+6 maximum health",
        EntityAttributes.GENERIC_MAX_HEALTH, 6, false),
    TNK_STALWART(Branch.TANK, 2, 0, 6, 2, "Stalwart", Items.IRON_DOOR, "✦ Blocking costs 25% less stamina; take 8% less damage",
        null, 0, false),
    TNK_WEAKEN(Branch.TANK, 3, -1, 10, 2, "Intimidate", Items.SKELETON_SKULL, "✦ Provoked foes deal 25% less damage",
        null, 0, false),
    TNK_IRONHIDE(Branch.TANK, 3, 1, 10, 2, "Iron Hide", Items.IRON_BLOCK, "✦ Provoke also gives you 8 absorption health",
        null, 0, false),
    TNK_WALL(Branch.TANK, 4, 0, 15, 3, "Bulwark", Items.BRICK_WALL, "▶ X: For 8s your squad within 8 blocks takes 30% less damage, you 20% less (35s cooldown)",
        null, 0, false),
    TNK_BASH(Branch.TANK, 5, -1, 22, 3, "Shield Bash", Items.ANVIL, "✦ After you block, your next hit staggers (Slowness IV, Weakness) for 2s",
        null, 0, false),
    TNK_ANCHOR(Branch.TANK, 5, 1, 22, 3, "Anchor", Items.CHAIN, "+50% knockback resistance. ✦ Break out of a titan's grip in half the strikes",
        EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.5, false),
    TNK_LONE(Branch.TANK, 6, 0, 28, 3, "Last Bastion", Items.CRYING_OBSIDIAN, "✦ With no ally within 40 blocks: take 15% less damage and regenerate",
        null, 0, false),
    TNK_ULT(Branch.TANK, 7, 0, 35, 5, "Armored Resolve", Items.NETHERITE_CHESTPLATE, "★ V: 12s of 60% less damage, no grabs, +25% damage, and foes keep turning on you",
        null, 0, false),

    // ==== Medic
    MED_DRESS(Branch.MEDIC, 0, 0, 1, 1, "Field Dressing", Items.PAPER, "▶ Z: Heal the ally you look at (or yourself) for 6 + level/8 (12s cooldown)",
        null, 0, false),
    MED_STEADY(Branch.MEDIC, 1, -1, 3, 1, "Steady Hands", Items.GLISTERING_MELON_SLICE, "✦ Your healing is 25% stronger",
        null, 0, false),
    MED_QUICK(Branch.MEDIC, 1, 1, 3, 1, "Quick Revive", Items.GOLDEN_CARROT, "✦ Revive the downed in 2.5s; Field Dressing on the downed revives them halfway",
        null, 0, false),
    MED_AURA(Branch.MEDIC, 2, 0, 6, 2, "Triage Aura", Items.BEACON, "✦ You and your squad within 10 blocks regenerate 1 health every 3s",
        null, 0, false),
    MED_ADRENALINE(Branch.MEDIC, 3, -1, 10, 2, "Adrenaline", Items.SUGAR, "✦ Field Dressing also grants Speed and 25 stamina",
        null, 0, false),
    MED_PURGE(Branch.MEDIC, 3, 1, 10, 2, "Purge", Items.MILK_BUCKET, "✦ Field Dressing cleanses harmful effects and recharges 25% faster",
        null, 0, false),
    MED_ZONE(Branch.MEDIC, 4, 0, 15, 3, "Sanctuary", Items.CAMPFIRE, "▶ X: A 6-block circle of Regeneration II and 10% protection for 8s (40s cooldown)",
        null, 0, false),
    MED_LIFELINE(Branch.MEDIC, 5, -1, 22, 3, "Lifeline", Items.LEAD, "✦ An ally within 16 blocks dropping below 25% is healed for 8 at once (20s cooldown)",
        null, 0, false),
    MED_COMBAT(Branch.MEDIC, 5, 1, 22, 3, "Combat Medic", Items.GOLDEN_SWORD, "✦ Your hits heal the most hurt ally near you (or you) for 15% of the damage",
        null, 0, false),
    MED_LONE(Branch.MEDIC, 6, 0, 28, 3, "Self-Sufficient", Items.HONEY_BOTTLE, "✦ With no ally within 40 blocks: Field Dressing heals double; +20% chance to be downed, not killed",
        null, 0, false),
    MED_ULT(Branch.MEDIC, 7, 0, 35, 5, "Blessing of the Walls", Items.TOTEM_OF_UNDYING, "★ V: Revive every downed ally within 20 blocks and heal everyone to full",
        null, 0, false),

    // ==== Recon
    RCN_MARK(Branch.RECON, 0, 0, 1, 1, "Hunter's Mark", Items.SPYGLASS, "▶ Z: Mark the foe you look at (48 blocks): it glows and takes +15% damage for 10s (12s cooldown)",
        null, 0, false),
    RCN_SWIFT(Branch.RECON, 1, -1, 3, 1, "Swift", Items.FEATHER, "+8% movement speed",
        EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.08, true),
    RCN_EYE(Branch.RECON, 1, 1, 3, 1, "Keen Eye", Items.ENDER_EYE, "✦ Your own hits on a marked foe deal +10% more",
        null, 0, false),
    RCN_GAS(Branch.RECON, 2, 0, 6, 2, "Light Gear", Items.PHANTOM_MEMBRANE, "Stamina recovers 25% faster; no fall damage for 4 more blocks",
        EntityAttributes.GENERIC_SAFE_FALL_DISTANCE, 4, false),
    RCN_WEAKSPOT(Branch.RECON, 3, -1, 10, 2, "Weak Spot", Items.SHEARS, "✦ Nape strikes on a marked titan count double",
        null, 0, false),
    RCN_SPOT(Branch.RECON, 3, 1, 10, 2, "Spotter", Items.COMPASS, "✦ Marking also reveals every titan within 40 blocks for 8s",
        null, 0, false),
    RCN_SMOKE(Branch.RECON, 4, 0, 15, 3, "Smoke Bomb", Items.GUNPOWDER, "▶ X: Vanish in smoke: invisible, Speed II, and foes lose you (30s cooldown)",
        null, 0, false),
    RCN_AMBUSH(Branch.RECON, 5, -1, 22, 3, "Ambush", Items.STONE_SWORD, "✦ Your first hit within 5s of a Smoke Bomb deals double",
        null, 0, false),
    RCN_REPORT(Branch.RECON, 5, 1, 22, 3, "Scouting Report", Items.MAP, "✦ While you have a mark up, your squad within 32 blocks deals +10% and moves faster",
        null, 0, false),
    RCN_LONE(Branch.RECON, 6, 0, 28, 3, "Lone Scout", Items.RABBIT_FOOT, "✦ With no ally within 40 blocks: abilities recharge 25% faster, +15% damage to titans",
        null, 0, false),
    RCN_ULT(Branch.RECON, 7, 0, 35, 5, "Hunter's Eye", Items.ENDER_PEARL, "★ V: 10s: foes within 30 blocks are slowed and revealed; +30% damage and nape strikes count double",
        null, 0, false);

    public enum Branch {
        BLADE("Blade", 0xFFC0463A, null), MOBILITY("Mobility", 0xFF5E9A4A, null), SURVIVAL("Survival", 0xFF4A74B0, null),
        INFANTRY("Infantry", 0xFFD0563A, PlayerClass.INFANTRY), TANK("Tank", 0xFF4A7AC0, PlayerClass.TANK),
        MEDIC("Medic", 0xFF5BC06A, PlayerClass.MEDIC), RECON("Recon", 0xFFC9A53A, PlayerClass.RECON);

        public final String title;
        public final int color;
        /** The class this tree belongs to (null for the general trees). */
        public final PlayerClass cls;

        Branch(String title, int color, PlayerClass cls) {
            this.title = title;
            this.color = color;
            this.cls = cls;
        }

        public static Branch of(PlayerClass c) {
            for (Branch b : values()) if (b.cls == c) return b;
            return INFANTRY;
        }
    }

    public static final int TIERS = 8;
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
        return 1 + level / 2 + level / 10;
    }

    /** Why this character can't learn this skill (null if it can). Shared by server and screen. */
    public String blocked(int level, int points, java.util.function.Predicate<Skill> has) {
        if (has.test(this)) return "Learned";
        if (level < this.level) return "Requires level " + this.level;
        if (!unlockedBy(has)) return "Learn a skill above it first";
        if (points < cost) return "Needs " + cost + " skill point" + (cost == 1 ? "" : "s");
        return null;
    }

    /** ▶ active, ★ ultimate: which ability slot (0 Z, 1 X, 2 V) this skill unlocks, or -1. */
    public int slot() {
        if (branch.cls == null) return -1;
        return tier == 0 ? 0 : tier == 4 ? 1 : tier == 7 ? 2 : -1;
    }

    /** Marks for the n-th reset (0-based): the first is free. */
    public static long resetCost(int used) {
        return used <= 0 ? 0 : used == 1 ? 2500 : 7500;
    }
}
