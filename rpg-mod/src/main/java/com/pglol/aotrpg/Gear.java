package com.pglol.aotrpg;

import java.util.UUID;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;

/**
 * Loot is earned, not found in chests: gear rolls a rarity (Common to Legendary), an item level
 * from where it dropped, and bonus stats. It drops from titans (bosses and shifters drop better),
 * quests and events, and the forge upgrades it (+1 to +10).
 */
public final class Gear {
    public enum Rarity {
        COMMON("Common", Formatting.WHITE, 0, 60),
        UNCOMMON("Uncommon", Formatting.GREEN, 1, 25),
        RARE("Rare", Formatting.BLUE, 2, 10),
        EPIC("Epic", Formatting.DARK_PURPLE, 3, 4),
        LEGENDARY("Legendary", Formatting.GOLD, 4, 1),
        /** Above legendary, in red: never drops by chance, only from the rarest crate rolls and rewards. */
        MYTHIC("Mythic", Formatting.RED, 5, 0);

        public final String title;
        public final Formatting color;
        public final int affixes, weight;

        Rarity(String title, Formatting color, int affixes, int weight) {
            this.title = title;
            this.color = color;
            this.affixes = affixes;
            this.weight = weight;
        }
    }

    private record Affix(String id, String label, RegistryEntry<EntityAttribute> attr, double perLevel, boolean percent) { }

    private static final Affix[] WEAPON = {
        new Affix("dmg", "Damage", EntityAttributes.GENERIC_ATTACK_DAMAGE, 0.09, false),
        new Affix("spd", "Attack Speed", EntityAttributes.GENERIC_ATTACK_SPEED, 0.012, false),
        new Affix("kb", "Knockback", EntityAttributes.GENERIC_ATTACK_KNOCKBACK, 0.02, false),
        new Affix("hp", "Max Health", EntityAttributes.GENERIC_MAX_HEALTH, 0.12, false),
        new Affix("luck", "Luck", EntityAttributes.GENERIC_LUCK, 0.03, false),
    };
    private static final Affix[] ARMOR = {
        new Affix("arm", "Armor", EntityAttributes.GENERIC_ARMOR, 0.05, false),
        new Affix("tough", "Toughness", EntityAttributes.GENERIC_ARMOR_TOUGHNESS, 0.035, false),
        new Affix("hp", "Max Health", EntityAttributes.GENERIC_MAX_HEALTH, 0.12, false),
        new Affix("move", "Speed", EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0009, true),
        new Affix("kbres", "Knockback Resistance", EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.004, true),
    };

    /** Danny's AoT weapons: ODM blades and the APG gun. Their gear bonus is Power (more damage). */
    private static final String[] AOT_WEAPONS = {"blade", "apg_gun"};

    public static boolean aotWeapon(ItemStack s) {
        var id = net.minecraft.registry.Registries.ITEM.getId(s.getItem());
        if (!id.getNamespace().equals("dannys-aot") && !id.getNamespace().equals(AotItems.namespace)) return false;
        for (String p : AOT_WEAPONS) if (id.getPath().equals(p)) return true;
        return false;
    }

    /** Extra damage fraction from gear Power (0.12 = +12%). */
    public static double power(ItemStack s) {
        if (s.isEmpty() || !isGear(s)) return 0;
        return data(s).getDouble("power");
    }

    private static final Item[] WEAPONS = {Items.IRON_SWORD, Items.IRON_AXE, Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.NETHERITE_SWORD};
    private static final Item[] ARMORS = {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
        Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS,
        Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
    private static final String[][] PREFIX = {
        {"Worn", "Chipped", "Recruit's", "Trainee's", "Dented"},
        {"Sturdy", "Honed", "Garrison", "Cadet's", "Stohess"},
        {"Survey Corps", "Veteran's", "Tempered", "Scout's", "Trost-tested"},
        {"Commander's", "Ackerman", "Wall-forged", "Levi's Spare", "Titan-scarred"},
        {"Coordinate", "Founder's", "Paths-touched", "Warhammer", "Kingslayer"},
        {"Ymir's", "Rumbling", "Eldian Oath", "Freedom's", "Attack Titan's"}};

    public static boolean isGear(ItemStack s) {
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        return c != null && c.copyNbt().contains("aot_gear");
    }

    public static NbtCompound data(ItemStack s) {
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        return c == null ? new NbtCompound() : c.copyNbt().getCompound("aot_gear");
    }

    /** Rolls a rarity; luck shifts weight towards the rare end (0 normal, 1 boss, 2 shifter). */
    public static Rarity rollRarity(Random r, int luck) {
        int[] w = new int[Rarity.values().length];
        int total = 0;
        for (Rarity x : Rarity.values()) {
            int v = x.weight;
            if (luck > 0 && x.ordinal() >= 2) v *= 1 + 2 * luck;
            if (luck > 0 && x == Rarity.COMMON) v /= 1 + luck * 2;
            w[x.ordinal()] = v;
            total += v;
        }
        int pick = r.nextInt(total);
        for (Rarity x : Rarity.values()) {
            pick -= w[x.ordinal()];
            if (pick < 0) return x;
        }
        return Rarity.COMMON;
    }

    /** Where a wearable goes (head, chest, legs, feet), or null for anything held. */
    public static net.minecraft.entity.EquipmentSlot wornSlot(ItemStack s) {
        net.minecraft.item.Equipment eq = net.minecraft.item.Equipment.fromStack(s);
        if (eq == null) return null;
        var slot = eq.getSlotType();
        return slot.getType() == net.minecraft.entity.EquipmentSlot.Type.HUMANOID_ARMOR ? slot : null;
    }

    private static List<Item> clothing;

    /** Danny's AoT clothing (uniforms, coats, cloaks, boots, hats): the only armor that drops. */
    public static List<Item> clothing() {
        if (clothing != null && !clothing.isEmpty()) return clothing;
        List<Item> out = new ArrayList<>();
        for (Item it : net.minecraft.registry.Registries.ITEM) {
            Identifier id = net.minecraft.registry.Registries.ITEM.getId(it);
            if (!id.getNamespace().equals("dannys-aot") || id.getPath().equals("odm_gear") || id.getPath().contains("spawn_egg")) continue;
            // Real clothing only: keys, tokens and other wearable trinkets are not armor, even when
            // Danny's mod lets them sit in an armor slot.
            if (trinket(id.getPath())) continue;
            if (!(it instanceof net.minecraft.item.ArmorItem) && !clothingName(id.getPath())) continue;
            // Titan and shifter parts are "worn" by the transformation, not clothes anyone puts on.
            if (bodyPart(id.getPath())) continue;
            if (wornSlot(new ItemStack(it)) != null) out.add(it);
        }
        clothing = out;
        return out;
    }

    /** Pieces of titans and shifters (muscles, skin, crystal...): worn by transformations, never gear. */
    static boolean bodyPart(String path) {
        for (String w : new String[] {"shifter", "titan", "muscle", "flesh", "skin", "bone", "crystal", "nape", "hardening", "_body", "body_"}) {
            if (path.contains(w)) return true;
        }
        return false;
    }

    /** Keys, tokens, badges, notes: things you carry or hang on a slot, never gear. */
    static boolean trinket(String path) {
        for (String w : new String[] {"key", "token", "badge", "note", "basement", "letter", "map", "coin", "medal", "ticket"}) {
            if (path.contains(w)) return true;
        }
        return false;
    }

    private static boolean clothingName(String path) {
        if (trinket(path)) return false;
        for (String w : new String[] {"uniform", "coat", "cloak", "cape", "jacket", "shirt", "pants", "trousers", "boots", "shoes",
            "hat", "helmet", "cap", "hood", "vest", "armor", "armour", "belt", "harness", "gloves", "scarf", "dress", "robe", "suit"}) {
            if (path.contains(w)) return true;
        }
        return false;
    }

    private static ItemStack armorBase(Random r, Rarity rarity, int ilvl) {
        List<Item> cl = clothing();
        if (!cl.isEmpty()) return new ItemStack(cl.get(r.nextInt(cl.size())));
        int reach = Math.min(ARMORS.length, 2 + ilvl / 12 + rarity.ordinal());
        return new ItemStack(ARMORS[r.nextInt(reach)]);
    }

    /**
     * Rare blade perk, Twin Cut: every nape strike counts twice. Only ODM blades, and only from
     * Rare up (8% Rare, 15% Epic, 30% Legendary).
     */
    private static void rollPerk(Random r, ItemStack s, Rarity rarity, NbtCompound g) {
        if (s.getItem() instanceof net.minecraft.item.ArmorItem || wornSlot(s) != null) {
            // Second Wind (armor and clothing): a killing blow is likelier to leave you downed, not dead.
            float wind = switch (rarity) {
                case UNCOMMON -> 0.10f;
                case RARE -> 0.20f;
                case EPIC -> 0.30f;
                case LEGENDARY -> 0.45f;
                case MYTHIC -> 0.7f;
                default -> 0f;
            };
            if (r.nextFloat() < wind) g.putDouble("secondwind", switch (rarity) {
                case MYTHIC -> 0.35;
                case LEGENDARY -> 0.25;
                case EPIC -> 0.18;
                case RARE -> 0.12;
                default -> 0.08;
            });
            return;
        }
        if (!Loadout.isGrip(s) || AotItems.isApgGun(s)) return;
        float chance = switch (rarity) {
            case RARE -> 0.08f;
            case EPIC -> 0.15f;
            case LEGENDARY -> 0.30f;
            case MYTHIC -> 0.5f;
            default -> 0f;
        };
        if (r.nextFloat() < chance) g.putBoolean("twin", true);
        // Tempered Steel: blades that wear far slower (Uncommon up).
        float temper = switch (rarity) {
            case UNCOMMON -> 0.10f;
            case RARE -> 0.20f;
            case EPIC -> 0.30f;
            case LEGENDARY -> 0.45f;
            case MYTHIC -> 0.7f;
            default -> 0f;
        };
        if (r.nextFloat() < temper) g.putBoolean("tempered", true);
    }

    /** Nape strikes this weapon deals per cut (2 with Twin Cut, when you can use it). */
    public static int napeStrikes(ServerPlayerEntity p, ItemStack s) {
        return isGear(s) && canUse(p, s) && data(s).getBoolean("twin") ? 2 : 1;
    }

    /** A new piece of gear of this rarity and item level. */
    public static ItemStack roll(Random r, Rarity rarity, int ilvl) {
        boolean weapon = r.nextFloat() < 0.5f;
        ItemStack s;
        // Weapons are the AoT mod's ODM grip blades (mostly) and APG guns whenever it is installed.
        Item aot = weapon ? AotItems.exact(r.nextFloat() < 0.65f ? "blade" : "apg_gun") : null;
        if (weapon && aot == null) aot = AotItems.exact(AOT_WEAPONS[r.nextInt(AOT_WEAPONS.length)]);
        if (aot != null) {
            // Rare ODM blades and APG guns: the gear that matters against titans and in PvP.
            s = new ItemStack(aot);
        } else {
            if (weapon) {
                int reach = Math.min(WEAPONS.length, 2 + ilvl / 12 + rarity.ordinal());
                s = new ItemStack(WEAPONS[r.nextInt(reach)]);
            } else {
                s = armorBase(r, rarity, ilvl);
            }
        }
        NbtCompound g = new NbtCompound();
        g.putString("rarity", rarity.name());
        g.putInt("ilvl", ilvl);
        g.putInt("up", 0);
        g.putLong("seed", r.nextLong());
        rollPerk(r, s, rarity, g);
        Infusions.roll(r, s, rarity, g);
        NbtCompound tag = new NbtCompound();
        tag.put("aot_gear", g);
        s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        String[] names = PREFIX[rarity.ordinal()];
        String inf = g.getString("infusion");
        String item = s.getItem().getName().getString();
        String title = inf.isEmpty() ? names[r.nextInt(names.length)] + " " + item : Infusions.name(r, Infusions.Infusion.valueOf(inf), rarity, item);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(title)
            .formatted(rarity.color).styled(st -> st.withItalic(false)));
        apply(s);
        return s;
    }

    /** A new piece on a given base item (the forge). */
    public static ItemStack rollAs(Random r, Rarity rarity, int ilvl, Item base) {
        String path = net.minecraft.registry.Registries.ITEM.getId(base).getPath();
        if (trinket(path) || bodyPart(path)) return rollArmor(r, rarity, ilvl);
        return finish(r, new ItemStack(base), rarity, ilvl);
    }

    /** A new armor piece (the forge). */
    public static ItemStack rollArmor(Random r, Rarity rarity, int ilvl) {
        return finish(r, armorBase(r, rarity, ilvl), rarity, ilvl);
    }

    private static ItemStack finish(Random r, ItemStack s, Rarity rarity, int ilvl) {
        return finish(r, s, rarity, ilvl, null);
    }

    /**
     * The catalog: exactly this item at this rarity and level. Infusion null rolls as usual, "" is
     * none, otherwise that element (weapons only).
     */
    public static ItemStack make(Random r, Item base, Rarity rarity, int ilvl, String infusion) {
        return finish(r, new ItemStack(base), rarity, Math.max(1, ilvl), infusion);
    }

    private static ItemStack finish(Random r, ItemStack s, Rarity rarity, int ilvl, String infusion) {
        NbtCompound g = new NbtCompound();
        g.putString("rarity", rarity.name());
        g.putInt("ilvl", ilvl);
        g.putInt("up", 0);
        g.putLong("seed", r.nextLong());
        rollPerk(r, s, rarity, g);
        if (infusion == null) Infusions.roll(r, s, rarity, g);
        else if (!infusion.isEmpty() && wornSlot(s) == null) g.putString("infusion", infusion);
        NbtCompound tag = new NbtCompound();
        tag.put("aot_gear", g);
        s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        String[] names = PREFIX[rarity.ordinal()];
        String inf = g.getString("infusion");
        String item = s.getItem().getName().getString();
        String title = inf.isEmpty() ? names[r.nextInt(names.length)] + " " + item : Infusions.name(r, Infusions.Infusion.valueOf(inf), rarity, item);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(title)
            .formatted(rarity.color).styled(st -> st.withItalic(false)));
        apply(s);
        return s;
    }

    /** Recomputes stats and tooltip from the gear data (after rolling or a forge upgrade). */
    public static void apply(ItemStack s) {
        NbtCompound g = data(s);
        Rarity rarity;
        try {
            rarity = Rarity.valueOf(g.getString("rarity"));
        } catch (Exception e) {
            return;
        }
        int ilvl = g.getInt("ilvl"), up = g.getInt("up");
        Random r = Random.create(g.getLong("seed"));
        net.minecraft.entity.EquipmentSlot worn = wornSlot(s);
        boolean weapon = worn == null;
        Affix[] pool = weapon ? WEAPON : ARMOR;
        AttributeModifierSlot slot = weapon ? AttributeModifierSlot.MAINHAND : AttributeModifierSlot.forEquipmentSlot(worn);

        // Start from the item's own stats (sword damage, armour points) and add the bonuses.
        AttributeModifiersComponent base = s.getItem().getComponents().getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS,
            AttributeModifiersComponent.DEFAULT);
        AttributeModifiersComponent.Builder b = AttributeModifiersComponent.builder();
        for (var e : base.modifiers()) b.add(e.attribute(), e.modifier(), e.slot());
        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal(rarity.title + (weapon ? " weapon" : " armor")).formatted(rarity.color).styled(st -> st.withItalic(false)));
        lore.add(Text.literal("Item level " + ilvl + (up > 0 ? "   ·   +" + up : "")).formatted(Formatting.GRAY).styled(st -> st.withItalic(false)));
        lore.add(Text.literal("Requires level " + ilvl).formatted(Formatting.DARK_GRAY).styled(st -> st.withItalic(false)));
        double scale = (1 + ilvl) * (1 + 0.25 * rarity.ordinal()) * (1 + 0.08 * up);
        boolean aotWeapon = aotWeapon(s);
        if (aotWeapon) {
            // Power: +2% per rarity step, +0.25% per item level, +3% per forge upgrade.
            double power = 0.02 * rarity.ordinal() + 0.0025 * ilvl + 0.03 * up + 0.01;
            power = Math.round(power * 1000) / 1000.0;
            g.putDouble("power", power);
            NbtCompound tagP = s.get(DataComponentTypes.CUSTOM_DATA).copyNbt();
            tagP.put("aot_gear", g);
            s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tagP));
        }
        int n = rarity.affixes;
        List<Affix> picked = new ArrayList<>();
        List<Affix> left = new ArrayList<>(List.of(pool));
        for (int i = 0; i < n && !left.isEmpty(); i++) picked.add(left.remove(r.nextInt(left.size())));
        // Every piece gets its main stat scaled by level and upgrades (Danny's weapons: Power instead).
        if (aotWeapon) {
            picked.remove(pool[0]);
            lore.add(Text.literal(String.format(java.util.Locale.ROOT, "+%.1f%% Power", g.getDouble("power") * 100))
                .formatted(Formatting.GOLD).styled(st -> st.withItalic(false)));
        } else if (!picked.contains(pool[0])) picked.add(0, pool[0]);
        int idx = 0;
        for (Affix a : picked) {
            double v = a.perLevel() * scale * (0.8 + 0.4 * r.nextDouble());
            v = Math.round(v * 100) / 100.0;
            if (v <= 0) continue;
            Identifier mid = Identifier.of("aot_rpg", "gear_" + a.id() + "_" + slot.asString() + "_" + idx++);
            b.add(a.attr(), new EntityAttributeModifier(mid, a.percent() ? v : v,
                a.percent() ? EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE : EntityAttributeModifier.Operation.ADD_VALUE), slot);
            String shown = a.percent() ? String.format(java.util.Locale.ROOT, "+%.1f%%", v * 100) : String.format(java.util.Locale.ROOT, "+%.2f", v);
            lore.add(Text.literal(shown + " " + a.label()).formatted(Formatting.BLUE).styled(st -> st.withItalic(false)));
        }
        if (Loadout.isGrip(s) && !AotItems.isApgGun(s)) {
            double t = BladeCare.temper(s);
            // The edge is graded by how much wear it shrugs off, not "X% slower".
            if (t > 0) {
                String grade = t >= 0.85 ? "Unbreaking" : t >= 0.75 ? "Flawless" : t >= 0.65 ? "Masterwork" : t >= 0.5 ? "Hardened" : t >= 0.35 ? "Keen" : "Honed";
                lore.add(Text.literal("✦ " + grade + " Edge" + (g.getBoolean("tempered") ? " · Tempered Steel" : "")).formatted(Formatting.AQUA, Formatting.BOLD).styled(st -> st.withItalic(false)));
                lore.add(Text.literal("  Shrugs off " + Math.round(t * 100) + "% of blade wear").formatted(Formatting.AQUA).styled(st -> st.withItalic(false)));
            }
        }
        if (g.getDouble("secondwind") > 0) {
            lore.add(Text.literal("✦ Second Wind").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD).styled(st -> st.withItalic(false)));
            lore.add(Text.literal("  +" + Math.round(g.getDouble("secondwind") * 100) + "% chance to be downed, not killed").formatted(Formatting.LIGHT_PURPLE).styled(st -> st.withItalic(false)));
        }
        if (g.getBoolean("twin")) {
            lore.add(Text.literal("✦ Twin Cut").formatted(Formatting.GOLD, Formatting.BOLD).styled(st -> st.withItalic(false)));
            lore.add(Text.literal("  Nape strikes count double").formatted(Formatting.YELLOW).styled(st -> st.withItalic(false)));
        }
        Infusions.Infusion infusion = null;
        try {
            if (!g.getString("infusion").isEmpty()) infusion = Infusions.Infusion.valueOf(g.getString("infusion"));
        } catch (Exception ignored) { }
        if (infusion != null) {
            Infusions.Infusion fi = infusion;
            lore.add(Text.literal("\u2726 " + fi.title).formatted(fi.format, Formatting.BOLD).styled(st -> st.withItalic(false)));
            lore.add(Text.literal("  " + fi.effect).formatted(fi.format).styled(st -> st.withItalic(false)));
        }
        if (weapon && rarity.ordinal() >= Rarity.EPIC.ordinal()) s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        // Boots break a fall like the ODM boots do (70%), whatever they are.
        if (worn == net.minecraft.entity.EquipmentSlot.FEET && !net.minecraft.registry.Registries.ITEM.getId(s.getItem()).getPath().contains("odm")) {
            b.add(EntityAttributes.GENERIC_FALL_DAMAGE_MULTIPLIER, new EntityAttributeModifier(Identifier.of("aot_rpg", "gear_fall"), -0.7,
                EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL), AttributeModifierSlot.FEET);
            lore.add(Text.literal("70% Fall Damage Reduction").formatted(Formatting.GREEN).styled(st -> st.withItalic(false)));
        }
        s.set(DataComponentTypes.ATTRIBUTE_MODIFIERS, b.build());
        s.set(DataComponentTypes.LORE, new LoreComponent(lore));
        s.set(DataComponentTypes.RARITY, switch (rarity) {
            case COMMON, UNCOMMON -> net.minecraft.util.Rarity.COMMON;
            case RARE -> net.minecraft.util.Rarity.RARE;
            case EPIC, LEGENDARY, MYTHIC -> net.minecraft.util.Rarity.EPIC;
        });
        // Rare and up shimmer in the inventory; dropped ones glow in their colour.
        if (rarity.ordinal() >= 2) s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        // Keep the name colour in step with the rarity.
        Text name = s.get(DataComponentTypes.CUSTOM_NAME);
        if (name != null) {
            String plain = name.getString().replaceAll(" \\+\\d+$", "");
            s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(plain + (up > 0 ? " +" + up : "")).formatted(rarity.color).styled(st -> st.withItalic(false)));
        }
    }

    public static MutableText label(Rarity r) {
        return Text.literal(r.title).formatted(r.color, Formatting.BOLD);
    }

    /** The item level a character may use: no higher than their own level. */
    public static int requiredLevel(ItemStack s) {
        return isGear(s) ? data(s).getInt("ilvl") : 0;
    }

    public static boolean canUse(ServerPlayerEntity p, ItemStack s) {
        return !isGear(s) || AotRpg.PROFILES.get(p.getUuid()).level >= requiredLevel(s);
    }

    /**
     * Item level for a drop: close to the finder's own level (never the area's alone), a little
     * higher for bosses, and now and then a rare find well above it (locked until they grow into it).
     */
    public static int dropLevel(ServerPlayerEntity p, int source, int bonus) {
        int lv = AotRpg.PROFILES.get(p.getUuid()).level;
        // Harder places pay better: up to a few levels past you (or your party's best), locked until you catch up.
        int ref = lv;
        Parties.Party party = AotRpg.PARTIES.of(p.getUuid());
        if (party != null) {
            for (UUID m : party.members) {
                ServerPlayerEntity o = p.getServer().getPlayerManager().getPlayer(m);
                if (o != null && o.getWorld() == p.getWorld() && o.squaredDistanceTo(p) < 128 * 128) ref = Math.max(ref, AotRpg.PROFILES.get(m).level);
            }
        }
        Random r = p.getRandom();
        int base = Math.max(1, Math.min(source, ref + 4) + bonus + r.nextInt(3) - 1);
        base = Math.min(base, Math.max(lv + 3, Math.min(source, ref) + 5));
        if (r.nextFloat() < 0.04f) base = lv + 6 + r.nextInt(10); // a rare find, above your level
        return Math.max(1, base);
    }

    /** Once a second: armor you are not high enough level for comes off. */
    public void enforceLevels(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created || p.isCreative()) return;
        // Anything that isn't a weapon or wearable loses gear stats it picked up by mistake; a titan
        // part or a key that was handed out as gear becomes a real piece of clothing of the same rarity and level.
        for (int a : AotRpg.SATCHEL.addresses(p)) {
            ItemStack s = AotRpg.SATCHEL.at(p, a);
            if (missingFall(s) || staleLore(s)) apply(s);
            if (isGear(s) && (bodyPart(net.minecraft.registry.Registries.ITEM.getId(s.getItem()).getPath()) || trinket(net.minecraft.registry.Registries.ITEM.getId(s.getItem()).getPath()))) {
                Rarity rar;
                try {
                    rar = Rarity.valueOf(data(s).getString("rarity"));
                } catch (Exception e) {
                    rar = Rarity.COMMON;
                }
                ItemStack fixed = rollArmor(p.getRandom(), rar, Math.max(1, requiredLevel(s)));
                if (!fixed.isEmpty()) {
                    AotRpg.SATCHEL.set(p, a, fixed);
                    Notify.toast(p, Text.literal("Gear replaced").formatted(Formatting.GOLD), fixed.getName().copy(), 0xE0B96A, null, null);
                }
                continue;
            }
            if (isGear(s) && !real(s) && !aotWeapon(s) && wornSlot(s) == null) {
                String path = net.minecraft.registry.Registries.ITEM.getId(s.getItem()).getPath();
                if (trinket(path)) strip(s);
            }
        }
        for (net.minecraft.entity.EquipmentSlot slot : new net.minecraft.entity.EquipmentSlot[] {net.minecraft.entity.EquipmentSlot.HEAD,
            net.minecraft.entity.EquipmentSlot.CHEST, net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.FEET}) {
            ItemStack s = p.getEquippedStack(slot);
            if (missingFall(s)) apply(s);
            if (isGear(s) && (bodyPart(net.minecraft.registry.Registries.ITEM.getId(s.getItem()).getPath()) || trinket(net.minecraft.registry.Registries.ITEM.getId(s.getItem()).getPath()))) {
                // A titan part worn as gear: off, and a real piece in its place.
                Rarity rar;
                try {
                    rar = Rarity.valueOf(data(s).getString("rarity"));
                } catch (Exception e) {
                    rar = Rarity.COMMON;
                }
                p.equipStack(slot, ItemStack.EMPTY);
                ItemStack fixed = rollArmor(p.getRandom(), rar, Math.max(1, requiredLevel(s)));
                if (!fixed.isEmpty()) AotRpg.SATCHEL.add(p, fixed);
                continue;
            }
            if (s.isEmpty() || canUse(p, s)) continue;
            String name = s.getName().getString();
            int need = requiredLevel(s);
            p.equipStack(slot, ItemStack.EMPTY);
            p.getInventory().offerOrDrop(s);
            Notify.toast(p, Text.literal("Too heavy for you yet").formatted(Formatting.RED),
                Text.literal(name + " needs level " + need), 0xC0463A, null, null);
        }
    }

    /** A quest reward: one piece of gear for the quest's level (bonus shifts rarity up). */
    public void reward(ServerPlayerEntity p, int level, int bonus) {
        Random r = p.getRandom();
        Rarity rar = rollRarity(r, bonus);
        ItemStack s = roll(r, rar, dropLevel(p, Math.max(1, level), 0));
        if (s.isEmpty()) return;
        // Announce first: handing the stack over empties it (and an empty stack is named "Air").
        announce(p, s, rar);
        p.getInventory().offerOrDrop(s);
    }

    /**
     * A titan died: maybe drop gear where it fell. Big titans drop more often; shifters and
     * named bosses always drop and roll better.
     */
    public void titanDrop(ServerPlayerEntity killer, LivingEntity titan, int areaLevel) {
        Random r = killer.getRandom();
        float size = titan.getWidth() * titan.getHeight();
        boolean boss = titan.hasCustomName() || titan.getMaxHealth() >= 300;
        boolean shifter = TitanGuard.isShifter(titan);
        float chance = shifter || boss ? 1f : Math.min(0.6f, 0.12f + size / 400f);
        if (r.nextFloat() >= chance) return;
        int luck = (shifter ? 2 : boss ? 1 : 0) + (Extraction.inRun(killer.getUuid()) ? 1 : 0);
        Rarity rar = rollRarity(r, luck);
        int ilvl = dropLevel(killer, areaLevel, shifter ? 3 : boss ? 1 : 0);
        ItemStack s = roll(r, rar, ilvl);
        if (s.isEmpty()) return;
        ServerWorld w = (ServerWorld) titan.getWorld();
        ItemEntity e = new ItemEntity(w, titan.getX(), titan.getY() + 1, titan.getZ(), s,
            (r.nextDouble() - 0.5) * 0.3, 0.3, (r.nextDouble() - 0.5) * 0.3);
        e.setPickupDelay(10);
        // Yours alone: nobody else sees it or can pick it up.
        Loot.own(e, killer);
        w.spawnEntity(e);
        if (rar.ordinal() >= 2) {
            // A good drop gets its moment (the loot is on the ground, yours alone).
            Reveal.show(killer, rar.title.toUpperCase() + " DROP", s.getName().getString(), net.minecraft.registry.Registries.ITEM.getId(s.getItem()).toString(),
                rar.ordinal());
        }
    }

    private static void announce(ServerPlayerEntity p, ItemStack s, Rarity r) {
        if (s.isEmpty()) return;
        p.sendMessage(Text.literal("Loot: ").formatted(Formatting.GRAY).append(s.getName().copy()), false);
        if (r.ordinal() >= 3) p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.6f, 1.4f);
    }

    /** Gear whose tooltip predates the current wording (e.g. "Blades wear 50% slower"). */
    private static boolean staleLore(ItemStack s) {
        if (!isGear(s)) return false;
        var lore = s.get(DataComponentTypes.LORE);
        if (lore == null) return false;
        for (Text l : lore.lines()) if (l.getString().contains("Blades wear")) return true;
        return false;
    }

    /** Boots that don't break a fall yet (rolled before boots all did). */
    private static boolean missingFall(ItemStack s) {
        if (!isGear(s) || wornSlot(s) != net.minecraft.entity.EquipmentSlot.FEET) return false;
        if (net.minecraft.registry.Registries.ITEM.getId(s.getItem()).getPath().contains("odm")) return false;
        var mods = s.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (mods == null) return true;
        for (var e : mods.modifiers()) if (e.attribute().equals(EntityAttributes.GENERIC_FALL_DAMAGE_MULTIPLIER)) return false;
        return true;
    }

    /** Real gear: a weapon or something worn. Keys, tokens and notes that caught gear stats once are not. */
    public static boolean real(ItemStack s) {
        if (!isGear(s)) return false;
        String path = net.minecraft.registry.Registries.ITEM.getId(s.getItem()).getPath();
        if (trinket(path) || bodyPart(path)) return false;
        return wornSlot(s) != null || aotWeapon(s) || s.getItem() instanceof net.minecraft.item.SwordItem
            || s.getItem() instanceof net.minecraft.item.AxeItem || s.getItem() instanceof net.minecraft.item.RangedWeaponItem;
    }

    /** Takes gear stats off something that should never have had them (back to the plain item). */
    public static void strip(ItemStack s) {
        s.remove(DataComponentTypes.CUSTOM_NAME);
        s.remove(DataComponentTypes.LORE);
        s.remove(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        if (c == null) return;
        NbtCompound tag = c.copyNbt();
        tag.remove("aot_gear");
        if (tag.isEmpty()) s.remove(DataComponentTypes.CUSTOM_DATA);
        else s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
    }

    /** Forge tempering: sets the item level (its stats follow). */
    public static void setLevel(ItemStack s, int ilvl) {
        NbtCompound tag = s.get(DataComponentTypes.CUSTOM_DATA).copyNbt();
        NbtCompound g = tag.getCompound("aot_gear");
        g.putInt("ilvl", ilvl);
        tag.put("aot_gear", g);
        s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        apply(s);
    }

    public static int rarityOf(ItemStack s) {
        try {
            return Rarity.valueOf(data(s).getString("rarity")).ordinal();
        } catch (Exception e) {
            return 0;
        }
    }

}
