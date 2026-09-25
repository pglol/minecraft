package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.CampfireBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cooking at campfires: meals restore hunger and give timed buffs. Ingredients come from the
 * satchel first, then the inventory; meals go into the satchel.
 */
public final class Cooking {
    /** Ingredient groups: any item of the group counts. */
    public enum Group {
        RAW_MEAT("Raw meat", Items.BEEF, Set.of(Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.RABBIT)),
        FISH("Raw fish", Items.COD, Set.of(Items.COD, Items.SALMON)),
        VEGETABLE("Vegetable", Items.CARROT, Set.of(Items.POTATO, Items.CARROT, Items.BEETROOT)),
        GRAIN("Wheat", Items.WHEAT, Set.of(Items.WHEAT)),
        SWEET("Sweetener", Items.SUGAR, Set.of(Items.SUGAR, Items.HONEY_BOTTLE, Items.SWEET_BERRIES, Items.APPLE, Items.GLOW_BERRIES)),
        EGG("Egg", Items.EGG, Set.of(Items.EGG)),
        MUSHROOM("Mushroom", Items.BROWN_MUSHROOM, Set.of(Items.BROWN_MUSHROOM, Items.RED_MUSHROOM));

        public final String title;
        public final Item icon;
        public final Set<Item> items;

        Group(String title, Item icon, Set<Item> items) {
            this.title = title;
            this.icon = icon;
            this.items = items;
        }
    }

    public record Ingredient(Group group, int count) { }

    private record Buff(RegistryEntry<net.minecraft.entity.effect.StatusEffect> effect, int seconds, int amplifier, String label) { }

    public enum Recipe {
        SKEWER("Roast Meat Skewer", Items.COOKED_BEEF, 8, 0.8f,
            List.of(new Ingredient(Group.RAW_MEAT, 1)),
            List.of(new Buff(StatusEffects.STRENGTH, 120, 0, "Strength I"))),
        GRILLED_FISH("Grilled Fish", Items.COOKED_SALMON, 6, 0.7f,
            List.of(new Ingredient(Group.FISH, 1)),
            List.of(new Buff(StatusEffects.REGENERATION, 15, 0, "Regeneration I"), new Buff(StatusEffects.WATER_BREATHING, 120, 0, "Water Breathing"))),
        HEARTY_STEW("Hearty Stew", Items.RABBIT_STEW, 12, 1.0f,
            List.of(new Ingredient(Group.RAW_MEAT, 1), new Ingredient(Group.VEGETABLE, 2)),
            List.of(new Buff(StatusEffects.RESISTANCE, 240, 0, "Resistance I"), new Buff(StatusEffects.REGENERATION, 10, 0, "Regeneration I"))),
        FISH_SOUP("Fisherman's Soup", Items.BEETROOT_SOUP, 10, 0.9f,
            List.of(new Ingredient(Group.FISH, 2), new Ingredient(Group.VEGETABLE, 1)),
            List.of(new Buff(StatusEffects.HASTE, 240, 0, "Haste I"), new Buff(StatusEffects.WATER_BREATHING, 240, 0, "Water Breathing"))),
        RATION("Survey Corps Ration", Items.BREAD, 10, 0.8f,
            List.of(new Ingredient(Group.GRAIN, 3), new Ingredient(Group.RAW_MEAT, 1)),
            List.of(new Buff(StatusEffects.SPEED, 300, 0, "Speed I"))),
        HONEY_CAKE("Honey Cake", Items.PUMPKIN_PIE, 8, 0.6f,
            List.of(new Ingredient(Group.GRAIN, 2), new Ingredient(Group.SWEET, 1), new Ingredient(Group.EGG, 1)),
            List.of(new Buff(StatusEffects.JUMP_BOOST, 180, 0, "Jump Boost I"), new Buff(StatusEffects.ABSORPTION, 120, 0, "Absorption I"))),
        MUSHROOM_BROTH("Mushroom Broth", Items.MUSHROOM_STEW, 6, 0.6f,
            List.of(new Ingredient(Group.MUSHROOM, 2)),
            List.of(new Buff(StatusEffects.NIGHT_VISION, 300, 0, "Night Vision")));

        public final String title;
        public final Item base;
        public final int nutrition;
        public final float saturation;
        public final List<Ingredient> ingredients;
        private final List<Buff> buffs;

        Recipe(String title, Item base, int nutrition, float saturation, List<Ingredient> ingredients, List<Buff> buffs) {
            this.title = title;
            this.base = base;
            this.nutrition = nutrition;
            this.saturation = saturation;
            this.ingredients = ingredients;
            this.buffs = buffs;
        }

        public String buffText() {
            StringBuilder b = new StringBuilder();
            for (Buff f : buffs) {
                if (b.length() > 0) b.append(", ");
                b.append(f.label).append(" ").append(f.seconds >= 60 ? f.seconds / 60 + "m" : f.seconds + "s");
            }
            return b.toString();
        }

        public ItemStack make() {
            ItemStack s = new ItemStack(base);
            FoodComponent.Builder food = new FoodComponent.Builder().nutrition(nutrition).saturationModifier(saturation);
            for (Buff f : buffs) food.statusEffect(new StatusEffectInstance(f.effect, f.seconds * 20, f.amplifier), 1f);
            s.set(DataComponentTypes.FOOD, food.build());
            s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(title).formatted(Formatting.GOLD).styled(st -> st.withItalic(false)));
            List<Text> lore = new ArrayList<>();
            lore.add(Text.literal("+" + nutrition + " hunger").formatted(Formatting.GRAY).styled(st -> st.withItalic(false)));
            lore.add(Text.literal(buffText()).formatted(Formatting.GREEN).styled(st -> st.withItalic(false)));
            lore.add(Text.literal("Cooked over a campfire").formatted(Formatting.DARK_GRAY));
            s.set(DataComponentTypes.LORE, new LoreComponent(lore));
            NbtComponent.set(DataComponentTypes.CUSTOM_DATA, s, n -> n.putString("aot_meal", name().toLowerCase()));
            return s;
        }
    }

    /** The campfire each player is cooking at. */
    private final Map<UUID, BlockPos> at = new HashMap<>();

    public static boolean isLitCampfire(BlockState st) {
        return st.isIn(BlockTags.CAMPFIRES) && st.contains(CampfireBlock.LIT) && st.get(CampfireBlock.LIT);
    }

    public void open(ServerPlayerEntity p, BlockPos fire) {
        at.put(p.getUuid(), fire.toImmutable());
        send(p, true);
    }

    private int craftable(ServerPlayerEntity p, Recipe r) {
        int n = Integer.MAX_VALUE;
        for (Ingredient i : r.ingredients) n = Math.min(n, AotRpg.SATCHEL.count(p, i.group.items) / i.count);
        return n;
    }

    public void send(ServerPlayerEntity p, boolean open) {
        List<Net.RecipeStatus> list = new ArrayList<>();
        for (Recipe r : Recipe.values()) {
            int[] have = new int[r.ingredients.size()];
            for (int i = 0; i < have.length; i++) have[i] = AotRpg.SATCHEL.count(p, r.ingredients.get(i).group.items);
            list.add(new Net.RecipeStatus(r.ordinal(), craftable(p, r), have));
        }
        ServerPlayNetworking.send(p, new Net.CookingState(open, list));
    }

    public void cook(ServerPlayerEntity p, int recipe, int times) {
        BlockPos fire = at.get(p.getUuid());
        if (fire == null || recipe < 0 || recipe >= Recipe.values().length) return;
        ServerWorld w = p.getServerWorld();
        if (p.squaredDistanceTo(fire.toCenterPos()) > 7 * 7 || !isLitCampfire(w.getBlockState(fire))) {
            p.sendMessage(Text.literal("You need to stay by a lit campfire to cook.").formatted(Formatting.RED), true);
            return;
        }
        Recipe r = Recipe.values()[recipe];
        int n = Math.min(Math.max(1, times), Math.min(16, craftable(p, r)));
        if (n <= 0) return;
        for (Ingredient i : r.ingredients) AotRpg.SATCHEL.take(p, i.group.items, i.count * n);
        for (int k = 0; k < n; k++) AotRpg.SATCHEL.add(p, r.make());
        w.playSound(null, fire, SoundEvents.BLOCK_SMOKER_SMOKE, SoundCategory.BLOCKS, 1f, 1f);
        w.playSound(null, fire, SoundEvents.BLOCK_CAMPFIRE_CRACKLE, SoundCategory.BLOCKS, 1f, 1.2f);
        w.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, fire.getX() + 0.5, fire.getY() + 0.8, fire.getZ() + 0.5, 6, 0.2, 0.3, 0.2, 0.01);
        p.sendMessage(Text.literal("Cooked " + n + "x " + r.title + " (in your satchel)").formatted(Formatting.GOLD), true);
        send(p, false);
    }
}
