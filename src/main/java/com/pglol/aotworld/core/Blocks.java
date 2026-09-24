package com.pglol.aotworld.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Global palette mapping Minecraft block-state strings to small integer ids.
 * Id 0 is always air. The generator core only deals in these ids; the Bukkit
 * adapter and the preview renderer translate them.
 */
public final class Blocks {
    private static final Map<String, Integer> IDS = new HashMap<>();
    private static final List<String> STATES = new ArrayList<>();
    private static volatile String[] snapshot = new String[0];

    private Blocks() {}

    public static synchronized int id(String state) {
        String key = state.startsWith("minecraft:") ? state : "minecraft:" + state;
        Integer existing = IDS.get(key);
        if (existing != null) return existing;
        int id = STATES.size();
        if (id >= Short.MAX_VALUE) throw new IllegalStateException("block palette overflow");
        STATES.add(key);
        IDS.put(key, id);
        snapshot = STATES.toArray(new String[0]);
        return id;
    }

    public static String state(int id) {
        return snapshot[id];
    }

    public static int size() {
        return snapshot.length;
    }

    /** "minecraft:oak_stairs[facing=north]" -> "oak_stairs". */
    public static String baseName(int id) {
        String s = snapshot[id];
        int b = s.indexOf('[');
        return s.substring(10, b < 0 ? s.length() : b);
    }

    public static final int AIR = id("air");
    public static final int STONE = id("stone");
    public static final int DEEPSLATE = id("deepslate");
    public static final int BEDROCK = id("bedrock");
    public static final int DIRT = id("dirt");
    public static final int COARSE_DIRT = id("coarse_dirt");
    public static final int PODZOL = id("podzol");
    public static final int MOSS = id("moss_block");
    public static final int GRASS = id("grass_block");
    public static final int SAND = id("sand");
    public static final int SANDSTONE = id("sandstone");
    public static final int GRAVEL = id("gravel");
    public static final int CLAY = id("clay");
    public static final int WATER = id("water");
    public static final int DIRT_PATH = id("dirt_path");
    public static final int SNOW_BLOCK = id("snow_block");
    public static final int ANDESITE = id("andesite");
    public static final int TUFF = id("tuff");
    public static final int POLISHED_ANDESITE = id("polished_andesite");
    public static final int SMOOTH_STONE = id("smooth_stone");
    public static final int COBBLE = id("cobblestone");
    public static final int MOSSY_COBBLE = id("mossy_cobblestone");
    public static final int STONE_BRICKS = id("stone_bricks");
    public static final int CRACKED_STONE_BRICKS = id("cracked_stone_bricks");
    public static final int MOSSY_STONE_BRICKS = id("mossy_stone_bricks");
    public static final int CHISELED_STONE_BRICKS = id("chiseled_stone_bricks");
    public static final int STONE_BRICK_WALL = id("stone_brick_wall");
    public static final int BRICKS = id("bricks");
    public static final int CALCITE = id("calcite");
    public static final int AMETHYST = id("amethyst_block");
    public static final int SMOOTH_QUARTZ = id("smooth_quartz");
    public static final int QUARTZ_PILLAR = id("quartz_pillar[axis=y]");
    public static final int SEA_LANTERN = id("sea_lantern");
    public static final int GLOWSTONE = id("glowstone");
    public static final int POLISHED_BLACKSTONE = id("polished_blackstone");
    public static final int BLACK_CONCRETE = id("black_concrete");
    public static final int WHITE_CONCRETE = id("white_concrete");
    public static final int RED_CONCRETE = id("red_concrete");

    public static final int SHORT_GRASS = id("short_grass");
    public static final int FERN = id("fern");
    public static final int DANDELION = id("dandelion");
    public static final int POPPY = id("poppy");
    public static final int CORNFLOWER = id("cornflower");
    public static final int OXEYE = id("oxeye_daisy");
    public static final int AZURE_BLUET = id("azure_bluet");
    public static final int SEAGRASS = id("seagrass");

    public static final int IRON_BARS_X = id("iron_bars[east=true,west=true]");
    public static final int IRON_BARS_Z = id("iron_bars[north=true,south=true]");
    public static final int SCAFFOLDING = id("scaffolding[distance=0,bottom=false]");
    public static final int LANTERN = id("lantern[hanging=false]");
    public static final int LANTERN_HANGING = id("lantern[hanging=true]");
    public static final int TORCH = id("torch");
    public static final int CAMPFIRE = id("campfire[lit=true]");
    public static final int BARREL = id("barrel[facing=up]");
    public static final int CRAFTING_TABLE = id("crafting_table");
    public static final int HAY = id("hay_block[axis=y]");
    public static final int SPRUCE_FENCE = id("spruce_fence");
    public static final int SPRUCE_FENCE_X = id("spruce_fence[east=true,west=true]");
    public static final int SPRUCE_FENCE_Z = id("spruce_fence[north=true,south=true]");
    public static final int OAK_FENCE = id("oak_fence");
    public static final int SPRUCE_PLANKS = id("spruce_planks");
    public static final int SPRUCE_LOG = id("spruce_log[axis=y]");
    public static final int OAK_PLANKS = id("oak_planks");
    public static final int COBBLE_WALL = id("cobblestone_wall");

    public static final int OAK_LOG = id("oak_log[axis=y]");
    public static final int OAK_LEAVES = id("oak_leaves[persistent=true]");
    public static final int BIRCH_LOG = id("birch_log[axis=y]");
    public static final int BIRCH_LEAVES = id("birch_leaves[persistent=true]");
    public static final int SPRUCE_LEAVES = id("spruce_leaves[persistent=true]");
    public static final int DARK_OAK_LOG = id("dark_oak_log[axis=y]");
    public static final int DARK_OAK_LEAVES = id("dark_oak_leaves[persistent=true]");
    public static final int OAK_WOOD = id("oak_wood[axis=y]");
    public static final int OAK_LOG_X = id("oak_log[axis=x]");
    public static final int OAK_LOG_Z = id("oak_log[axis=z]");

    public static final int FARMLAND = id("farmland[moisture=7]");
    public static final int WHEAT = id("wheat[age=7]");
    public static final int CARROTS = id("carrots[age=7]");
    public static final int POTATOES = id("potatoes[age=7]");
    public static final int BEETROOTS = id("beetroots[age=3]");

    public static final int COAL_ORE = id("coal_ore");
    public static final int IRON_ORE = id("iron_ore");
    public static final int COPPER_ORE = id("copper_ore");
    public static final int GOLD_ORE = id("gold_ore");
    public static final int DEEPSLATE_IRON_ORE = id("deepslate_iron_ore");
    public static final int DEEPSLATE_GOLD_ORE = id("deepslate_gold_ore");
    public static final int DEEPSLATE_DIAMOND_ORE = id("deepslate_diamond_ore");
    public static final int DEEPSLATE_REDSTONE_ORE = id("deepslate_redstone_ore");

    public static final int RED_WOOL = id("red_wool");
    public static final int WHITE_WOOL = id("white_wool");
    public static final int GREEN_WOOL = id("green_wool");
    public static final int BLUE_WOOL = id("blue_wool");
}
