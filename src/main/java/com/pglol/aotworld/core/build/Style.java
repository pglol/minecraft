package com.pglol.aotworld.core.build;

import com.pglol.aotworld.core.Blocks;

/** Block palette for a building, with every orientation variant pre-resolved. */
public final class Style {
    public static final int N = 0, S = 1, W = 2, E = 3;
    static final String[] FACING = {"north", "south", "west", "east"};

    public final boolean timber, flat;
    public final int frameY, frameX, frameZ;
    public final int plaster, base, floor, roofFull, trim;
    public final int[] roofStair = new int[4];
    public final int[] door = new int[8];
    public final int[] ladder = new int[4];
    /** Indoor staircase steps, matching the floor wood. */
    public final int[] stair = new int[4];
    public final int paneX, paneZ;

    /**
     * @param frame log block id without state, e.g. "dark_oak_log" (or a plain block for non-timber)
     * @param roof  stair block for pitched roofs, e.g. "spruce_stairs"; for flat roofs the parapet block
     */
    public Style(String frame, String plaster, String base, String floor, String roof, String roofFull,
                 String door, String trim, boolean timber, boolean flat) {
        this.timber = timber;
        this.flat = flat;
        boolean log = frame.endsWith("_log") || frame.endsWith("_wood") || frame.endsWith("_pillar");
        frameY = Blocks.id(log ? frame + "[axis=y]" : frame);
        frameX = Blocks.id(log ? frame + "[axis=x]" : frame);
        frameZ = Blocks.id(log ? frame + "[axis=z]" : frame);
        this.plaster = Blocks.id(plaster);
        this.base = Blocks.id(base);
        this.floor = Blocks.id(floor);
        this.roofFull = Blocks.id(roofFull);
        this.trim = Blocks.id(trim);
        for (int f = 0; f < 4; f++) {
            roofStair[f] = flat ? this.roofFull : Blocks.id(roof + "[facing=" + FACING[f] + ",half=bottom]");
            this.door[f * 2] = Blocks.id(door + "[facing=" + FACING[f] + ",half=lower,hinge=left]");
            this.door[f * 2 + 1] = Blocks.id(door + "[facing=" + FACING[f] + ",half=upper,hinge=left]");
            ladder[f] = Blocks.id("ladder[facing=" + FACING[f] + "]");
            String stairName = floor.endsWith("_planks") ? floor.replace("_planks", "_stairs") : "stone_brick_stairs";
            stair[f] = Blocks.id(stairName + "[facing=" + FACING[f] + ",half=bottom]");
        }
        paneX = Blocks.id("glass_pane[east=true,west=true]");
        paneZ = Blocks.id("glass_pane[north=true,south=true]");
    }

    // Paradis: half-timbered houses with tiled roofs, the look of Shiganshina and Trost.
    public static final Style[] PARADIS = {
        new Style("dark_oak_log", "white_terracotta", "cobblestone", "spruce_planks", "brick_stairs", "bricks", "spruce_door", "stone_bricks", true, false),
        new Style("spruce_log", "white_terracotta", "cobblestone", "oak_planks", "spruce_stairs", "spruce_planks", "spruce_door", "stone_bricks", true, false),
        new Style("dark_oak_log", "calcite", "stone_bricks", "spruce_planks", "dark_oak_stairs", "dark_oak_planks", "dark_oak_door", "stone_bricks", true, false),
        new Style("stripped_dark_oak_log", "mushroom_stem", "cobblestone", "spruce_planks", "brick_stairs", "bricks", "spruce_door", "stone_bricks", true, false),
        new Style("spruce_log", "smooth_sandstone", "cobblestone", "spruce_planks", "deepslate_tile_stairs", "deepslate_tiles", "spruce_door", "stone_bricks", true, false),
        new Style("oak_log", "white_terracotta", "stone_bricks", "oak_planks", "granite_stairs", "polished_granite", "oak_door", "stone_bricks", true, false),
    };

    public static final Style[] RURAL = {
        new Style("spruce_log", "spruce_planks", "cobblestone", "oak_planks", "spruce_stairs", "spruce_planks", "spruce_door", "cobblestone", true, false),
        new Style("oak_log", "oak_planks", "cobblestone", "spruce_planks", "dark_oak_stairs", "dark_oak_planks", "oak_door", "cobblestone", true, false),
        new Style("dark_oak_log", "white_terracotta", "cobblestone", "spruce_planks", "spruce_stairs", "spruce_planks", "spruce_door", "cobblestone", true, false),
    };

    // Hill villages: stone cottages with dark roofs.
    public static final Style[] HILL = {
        new Style("spruce_log", "cobblestone", "stone_bricks", "spruce_planks", "deepslate_tile_stairs", "deepslate_tiles", "spruce_door", "stone_bricks", true, false),
        new Style("stripped_spruce_log", "andesite", "cobblestone", "spruce_planks", "spruce_stairs", "spruce_planks", "spruce_door", "cobblestone", true, false),
    };

    // Forest cabins: log walls and mossy stone.
    public static final Style[] CABIN = {
        new Style("spruce_log", "stripped_spruce_log[axis=y]", "mossy_cobblestone", "spruce_planks", "spruce_stairs", "spruce_planks", "spruce_door", "mossy_cobblestone", true, false),
        new Style("dark_oak_log", "stripped_oak_log[axis=y]", "mossy_cobblestone", "oak_planks", "dark_oak_stairs", "dark_oak_planks", "oak_door", "mossy_cobblestone", true, false),
    };

    // Mitras: stone mansions of the nobility.
    public static final Style[] CAPITAL = {
        new Style("stone_bricks", "smooth_quartz", "polished_andesite", "dark_oak_planks", "deepslate_tile_stairs", "deepslate_tiles", "dark_oak_door", "chiseled_stone_bricks", false, false),
        new Style("polished_andesite", "calcite", "stone_bricks", "spruce_planks", "dark_oak_stairs", "dark_oak_planks", "dark_oak_door", "stone_bricks", false, false),
        new Style("stone_bricks", "white_terracotta", "stone_bricks", "oak_planks", "blackstone_stairs", "polished_blackstone", "spruce_door", "polished_andesite", false, false),
    };

    public static final Style PALACE =
        new Style("quartz_pillar", "smooth_quartz", "stone_bricks", "dark_oak_planks", "deepslate_tile_stairs", "deepslate_tiles", "dark_oak_door", "chiseled_stone_bricks", false, false);

    public static final Style CHAPEL =
        new Style("stone_bricks", "stone_bricks", "cobblestone", "spruce_planks", "deepslate_tile_stairs", "deepslate_tiles", "dark_oak_door", "chiseled_stone_bricks", false, false);

    public static final Style CASTLE =
        new Style("stone_bricks", "stone_bricks", "cobblestone", "spruce_planks", "deepslate_tile_stairs", "deepslate_tiles", "spruce_door", "chiseled_stone_bricks", false, false);

    // Marley: brick and stone apartment blocks with flat roofs.
    public static final Style[] MARLEY = {
        new Style("stone_bricks", "bricks", "stone_bricks", "spruce_planks", "stone_bricks", "stone_bricks", "dark_oak_door", "smooth_stone", false, true),
        new Style("polished_andesite", "mud_bricks", "polished_andesite", "oak_planks", "polished_andesite", "polished_andesite", "spruce_door", "smooth_stone", false, true),
        new Style("bricks", "terracotta", "stone_bricks", "spruce_planks", "bricks", "bricks", "dark_oak_door", "stone_bricks", false, true),
        new Style("stone_bricks", "light_gray_terracotta", "stone_bricks", "dark_oak_planks", "stone_bricks", "stone_bricks", "dark_oak_door", "polished_andesite", false, true),
    };

    public static final Style[] LIBERIO = {
        new Style("bricks", "bricks", "cobblestone", "spruce_planks", "bricks", "bricks", "spruce_door", "stone_bricks", false, true),
        new Style("stone_bricks", "brown_terracotta", "cobblestone", "oak_planks", "stone_bricks", "stone_bricks", "spruce_door", "stone_bricks", false, true),
        new Style("mud_bricks", "mud_bricks", "cobblestone", "spruce_planks", "brick_stairs", "bricks", "spruce_door", "stone_bricks", false, false),
    };

    public static final Style MARLEY_GRAND =
        new Style("polished_andesite", "smooth_stone", "stone_bricks", "dark_oak_planks", "stone_bricks", "stone_bricks", "dark_oak_door", "chiseled_stone_bricks", false, true);

    public static final Style[] UNDERGROUND = {
        new Style("cobblestone", "cobblestone", "cobblestone", "spruce_planks", "cobblestone_stairs", "cobblestone", "spruce_door", "mossy_cobblestone", false, false),
        new Style("spruce_log", "mossy_cobblestone", "cobblestone", "spruce_planks", "spruce_stairs", "spruce_planks", "spruce_door", "cobblestone", true, false),
        new Style("stone_bricks", "andesite", "cobblestone", "oak_planks", "stone_brick_stairs", "stone_bricks", "oak_door", "cobblestone", false, true),
    };
}
