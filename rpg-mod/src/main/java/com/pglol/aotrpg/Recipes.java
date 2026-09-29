package com.pglol.aotrpg;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Everything that can be made, and at which bench. Nothing is crafted the vanilla way: a recipe is
 * known from the start, comes with your level or a lifestyle skill, or is learned from a schematic
 * (found on troops, in titans' leavings and old chests, or bought from a Craftsman).
 */
public final class Recipes {
    private Recipes() {}

    /** The portable workbenches (bought in town, set down for five minutes at a time). */
    public enum Bench {
        BUILDER("Builder's Bench", Items.CRAFTING_TABLE, 450, Crafting.CARPENTRY, 0xC89A5A),
        SMITH("Smith's Forge", Items.SMITHING_TABLE, 1600, Lifestyle.SMITHING, 0xE0703A),
        QUARTERMASTER("Quartermaster's Desk", Items.CARTOGRAPHY_TABLE, 1100, Crafting.ENGINEERING, 0x6FA8DC),
        KITCHEN("Field Kitchen", Items.SMOKER, 650, Lifestyle.COOKING, 0xE8B34A);

        public final String title;
        public final Item look;
        public final long price;
        public final String skill;
        public final int color;

        Bench(String title, Item look, long price, String skill, int color) {
            this.title = title;
            this.look = look;
            this.price = price;
            this.skill = skill;
            this.color = color;
        }
    }

    /** One ingredient: what it shows as, what counts, how many. */
    public record Ing(ItemStack icon, String label, Predicate<ItemStack> test, int count) { }

    /**
     * How a recipe is known: "" from the start, "level:N", "skill:name:N", or "schematic:T" (T is
     * the schematic's tier 1-3: how rare it is to find).
     */
    public record Recipe(String id, Bench bench, String category, String name, ItemStack preview,
                         Function<ServerPlayerEntity, ItemStack> make, List<Ing> inputs, String unlock, int xp) {
        public int tier() {
            return unlock.startsWith("schematic:") ? Integer.parseInt(unlock.substring(10)) : 0;
        }
    }

    private static final Map<String, Recipe> ALL = new LinkedHashMap<>();

    public static Recipe get(String id) {
        return ALL.get(id);
    }

    public static List<Recipe> all() {
        return new ArrayList<>(ALL.values());
    }

    public static List<Recipe> of(Bench b) {
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : ALL.values()) if (r.bench() == b) out.add(r);
        return out;
    }

    // ------------------------------------------------------------------ knowing them

    public static boolean known(ServerPlayerEntity p, Recipe r) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        String u = r.unlock();
        if (u.isEmpty()) return true;
        if (pr.recipes.contains(r.id())) return true;
        if (u.startsWith("level:")) return pr.level >= Integer.parseInt(u.substring(6));
        if (u.startsWith("skill:")) {
            String[] a = u.split(":");
            return Lifestyle.level(pr, a[1]) >= Integer.parseInt(a[2]);
        }
        return false;
    }

    /** What stands between you and a recipe, in a word or two. */
    public static String hint(Recipe r) {
        String u = r.unlock();
        if (u.startsWith("level:")) return "Level " + u.substring(6);
        if (u.startsWith("skill:")) {
            String[] a = u.split(":");
            return Character.toUpperCase(a[1].charAt(0)) + a[1].substring(1) + " " + a[2];
        }
        if (u.startsWith("schematic:")) return "Schematic";
        return "";
    }

    // ------------------------------------------------------------------ schematics

    private static final String SCHEMATIC = "aot_schematic";
    private static final int[] TIER_COLOR = {0xEDE3C8, 0x55FF55, 0x5599FF, 0xC055FF};

    /** A schematic for a recipe: read it (use it) to learn it for good. */
    public static ItemStack schematic(Recipe r) {
        ItemStack s = new ItemStack(Items.PAPER);
        NbtCompound tag = new NbtCompound();
        tag.putString(SCHEMATIC, r.id());
        s.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        int col = TIER_COLOR[Math.max(0, Math.min(3, r.tier()))];
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Schematic: " + r.name()).styled(x -> x.withItalic(false).withColor(col)));
        s.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            Text.literal(r.bench().title).styled(x -> x.withItalic(false).withColor(r.bench().color)),
            Text.literal("Use to learn").styled(x -> x.withItalic(false).withColor(0xB8B0A0)))));
        s.set(DataComponentTypes.MAX_STACK_SIZE, 1);
        return s;
    }

    public static String schematicOf(ItemStack s) {
        if (s.isEmpty() || !s.isOf(Items.PAPER)) return null;
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        if (c == null) return null;
        String id = c.copyNbt().getString(SCHEMATIC);
        return id.isEmpty() ? null : id;
    }

    /** A random schematic up to this tier (rarer tiers come up less), or empty if none fits. */
    public static ItemStack randomSchematic(net.minecraft.util.math.random.Random r, int maxTier) {
        List<Recipe> pool = new ArrayList<>();
        for (Recipe x : ALL.values()) {
            int t = x.tier();
            if (t == 0 || t > maxTier) continue;
            // Tier 1 x6, tier 2 x3, tier 3 x1.
            for (int i = 0; i < (t == 1 ? 6 : t == 2 ? 3 : 1); i++) pool.add(x);
        }
        return pool.isEmpty() ? ItemStack.EMPTY : schematic(pool.get(r.nextInt(pool.size())));
    }

    // ------------------------------------------------------------------ building the list

    private static Ing of(Item it, int n) {
        return new Ing(new ItemStack(it, n), "", s -> s.isOf(it) && !Gear.isGear(s) && schematicOf(s) == null, n);
    }

    private static Ing tag(String label, Item icon, TagKey<Item> t, int n) {
        return new Ing(new ItemStack(icon, n), label, s -> s.isIn(t) && !Gear.isGear(s), n);
    }

    private static Ing any(String label, int n, Item... items) {
        java.util.Set<Item> set = java.util.Set.of(items);
        return new Ing(new ItemStack(items[0], n), label, s -> set.contains(s.getItem()) && !Gear.isGear(s), n);
    }

    private static Ing aot(String path, int n) {
        Item it = AotItems.exact(path);
        return it == null ? null : of(it, n);
    }

    private static void add(String id, Bench b, String cat, ItemStack out, String unlock, int xp, Ing... ins) {
        for (Ing i : ins) if (i == null) return;
        if (out == null || out.isEmpty()) return;
        ItemStack preview = out.copy();
        ALL.put(id, new Recipe(id, b, cat, out.getName().getString(), preview, p -> preview.copy(), List.of(ins), unlock, xp));
    }

    private static void add(String id, Bench b, String cat, Item out, int n, String unlock, int xp, Ing... ins) {
        if (out == null) return;
        add(id, b, cat, new ItemStack(out, n), unlock, xp, ins);
    }

    private static void special(String id, Bench b, String cat, String name, ItemStack preview, Function<ServerPlayerEntity, ItemStack> make, String unlock, int xp,
                                Ing... ins) {
        for (Ing i : ins) if (i == null) return;
        if (preview == null || preview.isEmpty()) return;
        ALL.put(id, new Recipe(id, b, cat, name, preview, make, List.of(ins), unlock, xp));
    }

    /** A named dish of our own: better food than it looks. */
    private static ItemStack dish(Item base, String name, String line, int nutrition, float saturation) {
        ItemStack s = new ItemStack(base);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name).styled(x -> x.withItalic(false).withColor(0xE8C47A)));
        s.set(DataComponentTypes.LORE, new LoreComponent(List.of(Text.literal(line).styled(x -> x.withItalic(false).withColor(0xB8B0A0)))));
        s.set(DataComponentTypes.FOOD, new FoodComponent.Builder().nutrition(nutrition).saturationModifier(saturation).build());
        return s;
    }

    private record Wood(String name, Item[] logs, Item planks, Item stairs, Item slab, Item fence, Item gate, Item door, Item trapdoor, Item sign, Item boat) { }

    static {
        Bench B = Bench.BUILDER, S = Bench.SMITH, Q = Bench.QUARTERMASTER, K = Bench.KITCHEN;

        // ---------------- Builder's Bench: lumber and woodwork, one set per kind of tree.
        Wood[] woods = {
            new Wood("oak", new Item[] {Items.OAK_LOG, Items.OAK_WOOD, Items.STRIPPED_OAK_LOG, Items.STRIPPED_OAK_WOOD}, Items.OAK_PLANKS, Items.OAK_STAIRS,
                Items.OAK_SLAB, Items.OAK_FENCE, Items.OAK_FENCE_GATE, Items.OAK_DOOR, Items.OAK_TRAPDOOR, Items.OAK_SIGN, Items.OAK_BOAT),
            new Wood("spruce", new Item[] {Items.SPRUCE_LOG, Items.SPRUCE_WOOD, Items.STRIPPED_SPRUCE_LOG, Items.STRIPPED_SPRUCE_WOOD}, Items.SPRUCE_PLANKS,
                Items.SPRUCE_STAIRS, Items.SPRUCE_SLAB, Items.SPRUCE_FENCE, Items.SPRUCE_FENCE_GATE, Items.SPRUCE_DOOR, Items.SPRUCE_TRAPDOOR, Items.SPRUCE_SIGN,
                Items.SPRUCE_BOAT),
            new Wood("birch", new Item[] {Items.BIRCH_LOG, Items.BIRCH_WOOD, Items.STRIPPED_BIRCH_LOG, Items.STRIPPED_BIRCH_WOOD}, Items.BIRCH_PLANKS,
                Items.BIRCH_STAIRS, Items.BIRCH_SLAB, Items.BIRCH_FENCE, Items.BIRCH_FENCE_GATE, Items.BIRCH_DOOR, Items.BIRCH_TRAPDOOR, Items.BIRCH_SIGN,
                Items.BIRCH_BOAT),
            new Wood("jungle", new Item[] {Items.JUNGLE_LOG, Items.JUNGLE_WOOD, Items.STRIPPED_JUNGLE_LOG, Items.STRIPPED_JUNGLE_WOOD}, Items.JUNGLE_PLANKS,
                Items.JUNGLE_STAIRS, Items.JUNGLE_SLAB, Items.JUNGLE_FENCE, Items.JUNGLE_FENCE_GATE, Items.JUNGLE_DOOR, Items.JUNGLE_TRAPDOOR, Items.JUNGLE_SIGN,
                Items.JUNGLE_BOAT),
            new Wood("acacia", new Item[] {Items.ACACIA_LOG, Items.ACACIA_WOOD, Items.STRIPPED_ACACIA_LOG, Items.STRIPPED_ACACIA_WOOD}, Items.ACACIA_PLANKS,
                Items.ACACIA_STAIRS, Items.ACACIA_SLAB, Items.ACACIA_FENCE, Items.ACACIA_FENCE_GATE, Items.ACACIA_DOOR, Items.ACACIA_TRAPDOOR, Items.ACACIA_SIGN,
                Items.ACACIA_BOAT),
            new Wood("dark_oak", new Item[] {Items.DARK_OAK_LOG, Items.DARK_OAK_WOOD, Items.STRIPPED_DARK_OAK_LOG, Items.STRIPPED_DARK_OAK_WOOD},
                Items.DARK_OAK_PLANKS, Items.DARK_OAK_STAIRS, Items.DARK_OAK_SLAB, Items.DARK_OAK_FENCE, Items.DARK_OAK_FENCE_GATE, Items.DARK_OAK_DOOR,
                Items.DARK_OAK_TRAPDOOR, Items.DARK_OAK_SIGN, Items.DARK_OAK_BOAT),
            new Wood("mangrove", new Item[] {Items.MANGROVE_LOG, Items.MANGROVE_WOOD, Items.STRIPPED_MANGROVE_LOG, Items.STRIPPED_MANGROVE_WOOD},
                Items.MANGROVE_PLANKS, Items.MANGROVE_STAIRS, Items.MANGROVE_SLAB, Items.MANGROVE_FENCE, Items.MANGROVE_FENCE_GATE, Items.MANGROVE_DOOR,
                Items.MANGROVE_TRAPDOOR, Items.MANGROVE_SIGN, Items.MANGROVE_BOAT),
            new Wood("cherry", new Item[] {Items.CHERRY_LOG, Items.CHERRY_WOOD, Items.STRIPPED_CHERRY_LOG, Items.STRIPPED_CHERRY_WOOD}, Items.CHERRY_PLANKS,
                Items.CHERRY_STAIRS, Items.CHERRY_SLAB, Items.CHERRY_FENCE, Items.CHERRY_FENCE_GATE, Items.CHERRY_DOOR, Items.CHERRY_TRAPDOOR, Items.CHERRY_SIGN,
                Items.CHERRY_BOAT),
        };
        for (Wood w : woods) {
            String n = w.name();
            add(n + "_planks", B, "Lumber", w.planks(), 4, "", 1, any("Any " + n.replace('_', ' ') + " log", 1, w.logs()));
            add(n + "_stairs", B, "Woodwork", w.stairs(), 4, "", 2, of(w.planks(), 6));
            add(n + "_slab", B, "Woodwork", w.slab(), 6, "", 2, of(w.planks(), 3));
            add(n + "_fence", B, "Woodwork", w.fence(), 3, "skill:carpentry:2", 3, of(w.planks(), 4), of(Items.STICK, 2));
            add(n + "_gate", B, "Woodwork", w.gate(), 1, "skill:carpentry:2", 3, of(w.planks(), 2), of(Items.STICK, 4));
            add(n + "_door", B, "Woodwork", w.door(), 3, "skill:carpentry:4", 4, of(w.planks(), 6));
            add(n + "_trapdoor", B, "Woodwork", w.trapdoor(), 2, "skill:carpentry:4", 4, of(w.planks(), 6));
            add(n + "_sign", B, "Woodwork", w.sign(), 3, "skill:carpentry:3", 3, of(w.planks(), 6), of(Items.STICK, 1));
            add(n + "_boat", B, "Camp", w.boat(), 1, "skill:carpentry:6", 6, of(w.planks(), 5));
        }
        add("sticks", B, "Lumber", Items.STICK, 4, "", 1, tag("Any planks", Items.OAK_PLANKS, ItemTags.PLANKS, 2));
        add("charcoal_kiln", B, "Lumber", Items.CHARCOAL, 2, "skill:carpentry:3", 3, tag("Any log", Items.OAK_LOG, ItemTags.LOGS, 3), of(Items.COAL, 1));

        // Masonry.
        add("stone_bricks", B, "Masonry", Items.STONE_BRICKS, 4, "", 2, of(Items.STONE, 4));
        add("stone_brick_stairs", B, "Masonry", Items.STONE_BRICK_STAIRS, 4, "", 2, of(Items.STONE_BRICKS, 6));
        add("stone_brick_slab", B, "Masonry", Items.STONE_BRICK_SLAB, 6, "", 2, of(Items.STONE_BRICKS, 3));
        add("stone_brick_wall", B, "Masonry", Items.STONE_BRICK_WALL, 6, "skill:carpentry:2", 3, of(Items.STONE_BRICKS, 6));
        add("mossy_stone_bricks", B, "Masonry", Items.MOSSY_STONE_BRICKS, 1, "skill:carpentry:5", 3, of(Items.STONE_BRICKS, 1), any("Vines or moss", 1, Items.VINE, Items.MOSS_BLOCK));
        add("chiseled_stone_bricks", B, "Masonry", Items.CHISELED_STONE_BRICKS, 1, "schematic:1", 5, of(Items.STONE_BRICK_SLAB, 2));
        add("cobblestone_stairs", B, "Masonry", Items.COBBLESTONE_STAIRS, 4, "", 2, of(Items.COBBLESTONE, 6));
        add("cobblestone_slab", B, "Masonry", Items.COBBLESTONE_SLAB, 6, "", 2, of(Items.COBBLESTONE, 3));
        add("cobblestone_wall", B, "Masonry", Items.COBBLESTONE_WALL, 6, "", 2, of(Items.COBBLESTONE, 6));
        add("stone_stairs", B, "Masonry", Items.STONE_STAIRS, 4, "", 2, of(Items.STONE, 6));
        add("stone_slab", B, "Masonry", Items.STONE_SLAB, 6, "", 2, of(Items.STONE, 3));
        add("polished_andesite", B, "Masonry", Items.POLISHED_ANDESITE, 4, "skill:carpentry:1", 2, of(Items.ANDESITE, 4));
        add("polished_diorite", B, "Masonry", Items.POLISHED_DIORITE, 4, "skill:carpentry:1", 2, of(Items.DIORITE, 4));
        add("polished_granite", B, "Masonry", Items.POLISHED_GRANITE, 4, "skill:carpentry:1", 2, of(Items.GRANITE, 4));
        add("polished_deepslate", B, "Masonry", Items.POLISHED_DEEPSLATE, 4, "skill:carpentry:3", 3, of(Items.COBBLED_DEEPSLATE, 4));
        add("deepslate_bricks", B, "Masonry", Items.DEEPSLATE_BRICKS, 4, "skill:carpentry:4", 3, of(Items.POLISHED_DEEPSLATE, 4));
        add("deepslate_tiles", B, "Masonry", Items.DEEPSLATE_TILES, 4, "skill:carpentry:6", 4, of(Items.DEEPSLATE_BRICKS, 4));
        add("tuff_bricks", B, "Masonry", Items.TUFF_BRICKS, 4, "skill:carpentry:5", 3, of(Items.TUFF, 4));
        add("bricks", B, "Masonry", Items.BRICKS, 1, "", 2, of(Items.BRICK, 4));
        add("brick_stairs", B, "Masonry", Items.BRICK_STAIRS, 4, "skill:carpentry:2", 2, of(Items.BRICKS, 6));
        add("brick_slab", B, "Masonry", Items.BRICK_SLAB, 6, "skill:carpentry:2", 2, of(Items.BRICKS, 3));
        add("mud_bricks", B, "Masonry", Items.MUD_BRICKS, 4, "skill:carpentry:3", 2, of(Items.PACKED_MUD, 4));
        add("sandstone", B, "Masonry", Items.SANDSTONE, 1, "", 1, of(Items.SAND, 4));
        add("cut_sandstone", B, "Masonry", Items.CUT_SANDSTONE, 4, "skill:carpentry:2", 2, of(Items.SANDSTONE, 4));
        add("glass_pane", B, "Masonry", Items.GLASS_PANE, 16, "", 2, of(Items.GLASS, 6));
        add("furnace", B, "Masonry", Items.FURNACE, 1, "", 3, tag("Any cobblestone", Items.COBBLESTONE, ItemTags.STONE_CRAFTING_MATERIALS, 8));
        add("smoker", B, "Masonry", Items.SMOKER, 1, "skill:carpentry:4", 4, of(Items.FURNACE, 1), tag("Any log", Items.OAK_LOG, ItemTags.LOGS, 4));
        add("blast_furnace", B, "Masonry", Items.BLAST_FURNACE, 1, "schematic:2", 8, of(Items.FURNACE, 1), of(Items.IRON_INGOT, 5), of(Items.SMOOTH_STONE, 3));

        // Furnishing.
        add("chest", B, "Furnishing", Items.CHEST, 1, "", 3, tag("Any planks", Items.OAK_PLANKS, ItemTags.PLANKS, 8));
        add("barrel", B, "Furnishing", Items.BARREL, 1, "", 3, tag("Any planks", Items.OAK_PLANKS, ItemTags.PLANKS, 6), tag("Any wooden slab", Items.OAK_SLAB, ItemTags.WOODEN_SLABS, 2));
        add("bed", B, "Furnishing", Items.WHITE_BED, 1, "", 4, tag("Any wool", Items.WHITE_WOOL, ItemTags.WOOL, 3), tag("Any planks", Items.OAK_PLANKS, ItemTags.PLANKS, 3));
        add("carpet", B, "Furnishing", Items.WHITE_CARPET, 3, "", 1, of(Items.WHITE_WOOL, 2));
        add("ladder", B, "Furnishing", Items.LADDER, 3, "", 2, of(Items.STICK, 7));
        add("torch", B, "Furnishing", Items.TORCH, 4, "", 1, of(Items.STICK, 1), any("Coal or charcoal", 1, Items.COAL, Items.CHARCOAL));
        add("bookshelf", B, "Furnishing", Items.BOOKSHELF, 1, "skill:carpentry:5", 5, tag("Any planks", Items.OAK_PLANKS, ItemTags.PLANKS, 6), of(Items.BOOK, 3));
        add("lectern", B, "Furnishing", Items.LECTERN, 1, "schematic:1", 5, tag("Any wooden slab", Items.OAK_SLAB, ItemTags.WOODEN_SLABS, 4), of(Items.BOOKSHELF, 1));
        add("item_frame", B, "Furnishing", Items.ITEM_FRAME, 1, "skill:carpentry:2", 2, of(Items.STICK, 8), of(Items.LEATHER, 1));
        add("painting", B, "Furnishing", Items.PAINTING, 1, "skill:carpentry:3", 3, of(Items.STICK, 8), tag("Any wool", Items.WHITE_WOOL, ItemTags.WOOL, 1));
        add("flower_pot", B, "Furnishing", Items.FLOWER_POT, 1, "", 1, of(Items.BRICK, 3));
        add("armor_stand", B, "Furnishing", Items.ARMOR_STAND, 1, "skill:carpentry:6", 6, of(Items.STICK, 6), of(Items.SMOOTH_STONE_SLAB, 1));
        add("candle", B, "Furnishing", Items.CANDLE, 2, "skill:carpentry:2", 2, of(Items.STRING, 1), of(Items.HONEYCOMB, 1));
        add("loom", B, "Furnishing", Items.LOOM, 1, "schematic:1", 4, of(Items.STRING, 2), tag("Any planks", Items.OAK_PLANKS, ItemTags.PLANKS, 2));
        add("banner", B, "Furnishing", Items.WHITE_BANNER, 1, "skill:carpentry:3", 3, tag("Any wool", Items.WHITE_WOOL, ItemTags.WOOL, 6), of(Items.STICK, 1));
        add("training_banner", B, "Furnishing", AotItems.exact("training_corps_banner"), 1, "schematic:2", 8, of(Items.WHITE_BANNER, 1), of(Items.BLUE_DYE, 2), of(Items.BROWN_DYE, 1));

        // Tools and camp.
        add("stone_pickaxe", B, "Tools", Items.STONE_PICKAXE, 1, "", 3, tag("Any cobblestone", Items.COBBLESTONE, ItemTags.STONE_TOOL_MATERIALS, 3), of(Items.STICK, 2));
        add("stone_axe", B, "Tools", Items.STONE_AXE, 1, "", 3, tag("Any cobblestone", Items.COBBLESTONE, ItemTags.STONE_TOOL_MATERIALS, 3), of(Items.STICK, 2));
        add("stone_shovel", B, "Tools", Items.STONE_SHOVEL, 1, "", 2, tag("Any cobblestone", Items.COBBLESTONE, ItemTags.STONE_TOOL_MATERIALS, 1), of(Items.STICK, 2));
        add("stone_hoe", B, "Tools", Items.STONE_HOE, 1, "", 2, tag("Any cobblestone", Items.COBBLESTONE, ItemTags.STONE_TOOL_MATERIALS, 2), of(Items.STICK, 2));
        add("fishing_rod", B, "Tools", Items.FISHING_ROD, 1, "", 3, of(Items.STICK, 3), of(Items.STRING, 2));
        add("bowl", B, "Tools", Items.BOWL, 4, "", 1, tag("Any planks", Items.OAK_PLANKS, ItemTags.PLANKS, 3));
        add("campfire", B, "Camp", Items.CAMPFIRE, 1, "", 3, of(Items.STICK, 3), any("Coal or charcoal", 1, Items.COAL, Items.CHARCOAL), tag("Any log", Items.OAK_LOG, ItemTags.LOGS, 3));
        add("scaffolding", B, "Camp", Items.SCAFFOLDING, 6, "skill:carpentry:3", 3, of(Items.BAMBOO, 6), of(Items.STRING, 1));
        add("lead", B, "Camp", Items.LEAD, 2, "skill:carpentry:2", 2, of(Items.STRING, 4), of(Items.SLIME_BALL, 1));
        add("saddle", B, "Camp", Items.SADDLE, 1, "schematic:2", 8, of(Items.LEATHER, 5), of(Items.IRON_INGOT, 2), of(Items.STRING, 2));
        add("white_wool", B, "Camp", Items.WHITE_WOOL, 1, "", 1, of(Items.STRING, 4));

        // ---------------- Smith's Forge.
        add("iron_nuggets", S, "Metal", Items.IRON_NUGGET, 9, "", 1, of(Items.IRON_INGOT, 1));
        add("iron_ingot", S, "Metal", Items.IRON_INGOT, 1, "", 1, of(Items.IRON_NUGGET, 9));
        add("gold_nuggets", S, "Metal", Items.GOLD_NUGGET, 9, "", 1, of(Items.GOLD_INGOT, 1));
        add("gold_ingot", S, "Metal", Items.GOLD_INGOT, 1, "", 1, of(Items.GOLD_NUGGET, 9));
        add("iron_block", S, "Metal", Items.IRON_BLOCK, 1, "skill:smithing:2", 3, of(Items.IRON_INGOT, 9));
        add("iron_bars", S, "Metal", Items.IRON_BARS, 16, "", 3, of(Items.IRON_INGOT, 6));
        add("chain", S, "Metal", Items.CHAIN, 2, "skill:smithing:1", 2, of(Items.IRON_INGOT, 1), of(Items.IRON_NUGGET, 2));
        add("iron_door", S, "Metal", Items.IRON_DOOR, 3, "skill:smithing:3", 4, of(Items.IRON_INGOT, 6));
        add("iron_trapdoor", S, "Metal", Items.IRON_TRAPDOOR, 1, "skill:smithing:3", 3, of(Items.IRON_INGOT, 4));
        add("lantern", S, "Metal", Items.LANTERN, 1, "", 2, of(Items.IRON_NUGGET, 8), of(Items.TORCH, 1));
        add("soul_lantern", S, "Metal", Items.SOUL_LANTERN, 1, "schematic:1", 3, of(Items.IRON_NUGGET, 8), of(Items.SOUL_TORCH, 1));
        add("cauldron", S, "Metal", Items.CAULDRON, 1, "skill:smithing:4", 4, of(Items.IRON_INGOT, 7));
        add("anvil", S, "Metal", Items.ANVIL, 1, "schematic:2", 12, of(Items.IRON_BLOCK, 3), of(Items.IRON_INGOT, 4));
        add("iron_pickaxe", S, "Tools", Items.IRON_PICKAXE, 1, "level:5", 6, of(Items.IRON_INGOT, 3), of(Items.STICK, 2));
        add("iron_axe", S, "Tools", Items.IRON_AXE, 1, "level:5", 6, of(Items.IRON_INGOT, 3), of(Items.STICK, 2));
        add("iron_shovel", S, "Tools", Items.IRON_SHOVEL, 1, "level:5", 4, of(Items.IRON_INGOT, 1), of(Items.STICK, 2));
        add("iron_hoe", S, "Tools", Items.IRON_HOE, 1, "level:5", 4, of(Items.IRON_INGOT, 2), of(Items.STICK, 2));
        add("diamond_pickaxe", S, "Tools", Items.DIAMOND_PICKAXE, 1, "schematic:2", 14, of(Items.DIAMOND, 3), of(Items.STICK, 2));
        add("diamond_axe", S, "Tools", Items.DIAMOND_AXE, 1, "schematic:2", 14, of(Items.DIAMOND, 3), of(Items.STICK, 2));
        add("diamond_shovel", S, "Tools", Items.DIAMOND_SHOVEL, 1, "schematic:2", 10, of(Items.DIAMOND, 1), of(Items.STICK, 2));
        add("shears", S, "Tools", Items.SHEARS, 1, "", 3, of(Items.IRON_INGOT, 2));
        add("bucket", S, "Tools", Items.BUCKET, 1, "", 3, of(Items.IRON_INGOT, 3));
        add("flint_and_steel", S, "Tools", Items.FLINT_AND_STEEL, 1, "", 2, of(Items.IRON_INGOT, 1), of(Items.FLINT, 1));
        add("brush", S, "Tools", Items.BRUSH, 1, "skill:smithing:2", 3, of(Items.FEATHER, 1), of(Items.COPPER_INGOT, 1), of(Items.STICK, 1));
        add("rail", S, "Rails", Items.RAIL, 16, "skill:smithing:3", 6, of(Items.IRON_INGOT, 6), of(Items.STICK, 1));
        add("powered_rail", S, "Rails", Items.POWERED_RAIL, 6, "schematic:2", 8, of(Items.GOLD_INGOT, 6), of(Items.STICK, 1), of(Items.REDSTONE, 1));
        add("minecart", S, "Rails", Items.MINECART, 1, "skill:smithing:3", 5, of(Items.IRON_INGOT, 5));
        add("chest_minecart", S, "Rails", Items.CHEST_MINECART, 1, "skill:smithing:5", 5, of(Items.MINECART, 1), of(Items.CHEST, 1));
        // The corps' steel and gear.
        add("ultrahard_steel", S, "ODM", AotItems.exact("ultrahard_steel_ingot"), 1, "skill:smithing:4", 10, of(Items.IRON_INGOT, 4), of(Items.COAL, 2));
        add("blade_components", S, "ODM", AotItems.exact("blade_component"), 8, "level:4", 6, aot("ultrahard_steel_ingot", 1), of(Items.IRON_INGOT, 2));
        add("gas_canister", S, "ODM", AotItems.exact("gas_canister"), 1, "level:8", 10, aot("ultrahard_steel_ingot", 1), of(Items.IRON_INGOT, 4));
        add("thunder_spears", S, "Ordnance", AotItems.exact("thunder_spear"), 2, "schematic:3", 30, aot("ultrahard_steel_ingot", 4), of(Items.GUNPOWDER, 6), of(Items.BLAZE_POWDER, 2));
        add("odm_boots", S, "ODM", AotItems.exact("odm_boots"), 1, "schematic:2", 20, aot("ultrahard_leather", 4), of(Items.IRON_INGOT, 2));
        add("odm_harness", S, "ODM", AotItems.exact("odm_gear"), 1, "schematic:3", 40, aot("ultrahard_steel_ingot", 10), aot("ultrahard_leather", 8), aot("gas_canister", 2));
        Item gripItem = AotItems.exact("blade");
        if (gripItem != null) {
            special("odm_grip", S, "ODM", "ODM Grip", new ItemStack(gripItem),
                p -> Gear.make(p.getRandom(), gripItem, Gear.Rarity.COMMON, Math.max(1, AotRpg.PROFILES.get(p.getUuid()).level), null),
                "schematic:2", 25, aot("ultrahard_steel_ingot", 6), of(Items.LEATHER, 2), of(Items.IRON_INGOT, 4));
        }
        Item gun = AotItems.exact("apg_gun");
        if (gun != null) {
            special("apg_gun", S, "Ordnance", "Anti-Personnel Gun", new ItemStack(gun),
                p -> Gear.make(p.getRandom(), gun, Gear.Rarity.COMMON, Math.max(1, AotRpg.PROFILES.get(p.getUuid()).level), null),
                "schematic:3", 30, aot("ultrahard_steel_ingot", 8), of(Items.REDSTONE, 4), of(Items.GUNPOWDER, 2));
        }

        // ---------------- Quartermaster's Desk: supplies, signals, survey.
        add("apg_cartridges", Q, "Supplies", AotItems.exact("apg_cartridge"), 8, "level:5", 5, of(Items.IRON_INGOT, 1), of(Items.GUNPOWDER, 2));
        add("ice_burst_clusters", Q, "Supplies", AotItems.exact("ice_burst_cluster"), 8, "", 4, aot("ice_burst_stone", 1));
        add("ice_burst_shards", Q, "Supplies", AotItems.exact("ice_burst_cluster"), 2, "", 2, aot("small_ice_burst_shard", 4));
        add("ultrahard_leather", Q, "Supplies", AotItems.exact("ultrahard_leather"), 1, "skill:engineering:2", 6, of(Items.LEATHER, 3), of(Items.IRON_INGOT, 1));
        add("leather", Q, "Supplies", Items.LEATHER, 1, "", 1, of(Items.ROTTEN_FLESH, 4));
        add("gunpowder", Q, "Supplies", Items.GUNPOWDER, 2, "schematic:1", 3, of(Items.CHARCOAL, 1), of(Items.BONE_MEAL, 2));
        add("string", Q, "Supplies", Items.STRING, 4, "", 1, tag("Any wool", Items.WHITE_WOOL, ItemTags.WOOL, 1));
        add("flare_gun", Q, "Signals", AotItems.exact("flare_gun"), 1, "schematic:2", 15, of(Items.IRON_INGOT, 3), of(Items.STICK, 1), of(Items.GUNPOWDER, 1));
        add("flares_yellow", Q, "Signals", AotItems.exact("yellow_flare_cartridge"), 4, "level:3", 3, of(Items.GUNPOWDER, 1), of(Items.PAPER, 1), of(Items.YELLOW_DYE, 1));
        add("flares_red", Q, "Signals", AotItems.exact("red_flare_cartridge"), 4, "level:3", 3, of(Items.GUNPOWDER, 1), of(Items.PAPER, 1), of(Items.RED_DYE, 1));
        add("flares_green", Q, "Signals", AotItems.exact("green_flare_cartridge"), 4, "level:3", 3, of(Items.GUNPOWDER, 1), of(Items.PAPER, 1), of(Items.GREEN_DYE, 1));
        add("flares_black", Q, "Signals", AotItems.exact("black_flare_cartridge"), 4, "schematic:1", 4, of(Items.GUNPOWDER, 1), of(Items.PAPER, 1), of(Items.BLACK_DYE, 1));
        add("paper", Q, "Survey", Items.PAPER, 3, "", 1, of(Items.SUGAR_CANE, 3));
        add("book", Q, "Survey", Items.BOOK, 1, "", 2, of(Items.PAPER, 3), of(Items.LEATHER, 1));
        add("writable_book", Q, "Survey", Items.WRITABLE_BOOK, 1, "skill:engineering:1", 2, of(Items.BOOK, 1), of(Items.INK_SAC, 1), of(Items.FEATHER, 1));
        add("map", Q, "Survey", Items.MAP, 1, "skill:engineering:2", 3, of(Items.PAPER, 8));
        add("compass", Q, "Survey", Items.COMPASS, 1, "level:6", 5, of(Items.IRON_INGOT, 4), of(Items.REDSTONE, 1));
        add("clock", Q, "Survey", Items.CLOCK, 1, "level:8", 5, of(Items.GOLD_INGOT, 4), of(Items.REDSTONE, 1));
        add("spyglass", Q, "Survey", Items.SPYGLASS, 1, "schematic:1", 8, of(Items.COPPER_INGOT, 2), of(Items.AMETHYST_SHARD, 1));
        add("name_tag", Q, "Survey", Items.NAME_TAG, 1, "schematic:2", 6, of(Items.PAPER, 2), of(Items.STRING, 1), of(Items.GOLD_NUGGET, 1));
        add("dyes_white", Q, "Dyes", Items.WHITE_DYE, 2, "", 1, of(Items.BONE_MEAL, 1));
        add("bone_meal", Q, "Dyes", Items.BONE_MEAL, 3, "", 1, of(Items.BONE, 1));
        add("dyes_red", Q, "Dyes", Items.RED_DYE, 2, "", 1, any("A red flower or beetroot", 1, Items.POPPY, Items.ROSE_BUSH, Items.RED_TULIP, Items.BEETROOT));
        add("dyes_yellow", Q, "Dyes", Items.YELLOW_DYE, 2, "", 1, any("A yellow flower", 1, Items.DANDELION, Items.SUNFLOWER));
        add("dyes_blue", Q, "Dyes", Items.BLUE_DYE, 2, "", 1, any("Lapis or cornflower", 1, Items.LAPIS_LAZULI, Items.CORNFLOWER));
        add("dyes_black", Q, "Dyes", Items.BLACK_DYE, 2, "", 1, any("Ink or wither rose", 1, Items.INK_SAC, Items.WITHER_ROSE));
        add("dyes_green", Q, "Dyes", Items.GREEN_DYE, 1, "skill:engineering:1", 1, of(Items.CACTUS, 1));
        add("dyes_brown", Q, "Dyes", Items.BROWN_DYE, 1, "", 1, of(Items.COCOA_BEANS, 1));

        // ---------------- Field Kitchen.
        add("cook_beef", K, "Cooking", Items.COOKED_BEEF, 1, "", 1, of(Items.BEEF, 1));
        add("cook_porkchop", K, "Cooking", Items.COOKED_PORKCHOP, 1, "", 1, of(Items.PORKCHOP, 1));
        add("cook_chicken", K, "Cooking", Items.COOKED_CHICKEN, 1, "", 1, of(Items.CHICKEN, 1));
        add("cook_mutton", K, "Cooking", Items.COOKED_MUTTON, 1, "", 1, of(Items.MUTTON, 1));
        add("cook_rabbit", K, "Cooking", Items.COOKED_RABBIT, 1, "", 1, of(Items.RABBIT, 1));
        add("cook_cod", K, "Cooking", Items.COOKED_COD, 1, "", 1, of(Items.COD, 1));
        add("cook_salmon", K, "Cooking", Items.COOKED_SALMON, 1, "", 1, of(Items.SALMON, 1));
        add("bake_potato", K, "Cooking", Items.BAKED_POTATO, 1, "", 1, of(Items.POTATO, 1));
        add("dried_kelp", K, "Cooking", Items.DRIED_KELP, 1, "", 1, of(Items.KELP, 1));
        add("bread", K, "Baking", Items.BREAD, 1, "", 2, of(Items.WHEAT, 3));
        add("sugar", K, "Baking", Items.SUGAR, 1, "", 1, of(Items.SUGAR_CANE, 1));
        add("cookies", K, "Baking", Items.COOKIE, 8, "skill:cooking:1", 3, of(Items.WHEAT, 2), of(Items.COCOA_BEANS, 1));
        add("pumpkin_pie", K, "Baking", Items.PUMPKIN_PIE, 1, "skill:cooking:2", 3, of(Items.PUMPKIN, 1), of(Items.SUGAR, 1), of(Items.EGG, 1));
        add("cake", K, "Baking", Items.CAKE, 1, "skill:cooking:4", 6, of(Items.MILK_BUCKET, 1), of(Items.SUGAR, 2), of(Items.EGG, 1), of(Items.WHEAT, 3));
        add("mushroom_stew", K, "Stews", Items.MUSHROOM_STEW, 1, "", 2, of(Items.BOWL, 1), of(Items.RED_MUSHROOM, 1), of(Items.BROWN_MUSHROOM, 1));
        add("beetroot_soup", K, "Stews", Items.BEETROOT_SOUP, 1, "", 2, of(Items.BOWL, 1), of(Items.BEETROOT, 6));
        add("rabbit_stew", K, "Stews", Items.RABBIT_STEW, 1, "skill:cooking:3", 4, of(Items.BOWL, 1), of(Items.COOKED_RABBIT, 1), of(Items.CARROT, 1),
            of(Items.BAKED_POTATO, 1), any("A mushroom", 1, Items.RED_MUSHROOM, Items.BROWN_MUSHROOM));
        add("golden_carrot", K, "Stews", Items.GOLDEN_CARROT, 1, "skill:cooking:5", 5, of(Items.GOLD_NUGGET, 8), of(Items.CARROT, 1));
        add("golden_apple", K, "Stews", Items.GOLDEN_APPLE, 1, "schematic:3", 20, of(Items.GOLD_INGOT, 8), of(Items.APPLE, 1));
        // Our own rations: better than they look.
        add("survey_ration", K, "Rations", dish(Items.BREAD, "Survey Corps Ration", "Hard bread and dried meat for the road", 12, 1.0f), "", 6,
            of(Items.BREAD, 1), any("Any cooked meat", 1, Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_MUTTON, Items.COOKED_CHICKEN), of(Items.APPLE, 1));
        add("garrison_stew", K, "Rations", dish(Items.RABBIT_STEW, "Garrison Stew", "The barracks' pot, still warm", 16, 1.2f), "skill:cooking:2", 8,
            of(Items.BOWL, 1), of(Items.COOKED_MUTTON, 1), of(Items.CARROT, 1), of(Items.BAKED_POTATO, 1));
        add("steamed_potato", K, "Rations", dish(Items.BAKED_POTATO, "Steamed Potato", "Worth stealing from the officers' kitchen", 9, 1.4f), "skill:cooking:1", 4,
            of(Items.POTATO, 2));
        add("marleyan_tins", K, "Rations", dish(Items.COOKED_BEEF, "Marleyan Tinned Meat", "Keeps for years; tastes like it", 14, 0.9f), "schematic:1", 8,
            of(Items.IRON_NUGGET, 1), any("Any cooked meat", 2, Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_MUTTON, Items.COOKED_CHICKEN));
        add("officers_supper", K, "Rations", dish(Items.PUMPKIN_PIE, "Officer's Supper", "Meat, bread and a sweet: a rare night", 20, 1.4f), "schematic:2", 14,
            of(Items.COOKED_BEEF, 1), of(Items.BREAD, 1), of(Items.SUGAR, 1), of(Items.APPLE, 1));
        add("sweet_berry_jam", K, "Rations", dish(Items.HONEY_BOTTLE, "Berry Preserve", "Summer in a jar", 8, 1.1f), "skill:cooking:3", 4,
            of(Items.GLASS_BOTTLE, 1), of(Items.SWEET_BERRIES, 6), of(Items.SUGAR, 1));
    }
}
