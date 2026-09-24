package com.pglol.aotworld.preview;

import com.pglol.aotworld.core.Blocks;

import java.util.LinkedHashMap;
import java.util.Map;

/** Approximate map colours for block ids, used by the preview renderer. */
final class BlockColors {
    private static final Map<String, Integer> EXACT = new LinkedHashMap<>();
    private static final String[][] CONTAINS = {
        {"stone_brick", "7c7c7c"}, {"polished_andesite", "8a8a8e"}, {"deepslate_tile", "3b3b40"},
        {"water", "3f76e4"}, {"seagrass", "3f76e4"}, {"leaves", "3f7d2a"}, {"grass_block", "6aa84f"},
        {"short_grass", "6aa84f"}, {"fern", "5a9a40"}, {"birch_leaves", "80a755"}, {"spruce_leaves", "3d6040"},
        {"dark_oak_leaves", "2f5a1f"}, {"sand", "dbd3a0"}, {"gravel", "8a8581"}, {"dirt_path", "b29663"},
        {"coarse_dirt", "7a5a3c"}, {"podzol", "5b3f1f"}, {"moss", "5a7a30"}, {"dirt", "86603f"},
        {"farmland", "5d3d1e"}, {"wheat", "d8c35a"}, {"carrots", "e0902a"}, {"potatoes", "c8b560"}, {"beetroots", "a03030"},
        {"snow", "f4f8fb"}, {"white_terracotta", "d6c6b4"}, {"terracotta", "a0604a"}, {"calcite", "e2e3dd"},
        {"quartz", "ece6df"}, {"mushroom_stem", "d8d2c8"}, {"sandstone", "d9cb94"},
        {"brick_stairs", "a0503c"}, {"bricks", "96503c"}, {"mud_bricks", "8a6a4f"}, {"granite", "a06a55"},
        {"deepslate_tile", "3b3b40"}, {"blackstone", "2e2a30"}, {"dark_oak", "4a3120"}, {"spruce", "6d4f30"},
        {"birch", "c8b77a"}, {"oak", "a8864f"}, {"andesite", "8a8a8e"}, {"stone_brick", "7c7c7c"},
        {"cobblestone", "737373"}, {"smooth_stone", "a0a0a0"}, {"stone", "7f7f7f"}, {"tuff", "6c6d66"},
        {"iron_bars", "5a5a5a"}, {"lantern", "f0c060"}, {"glowstone", "f5d88a"}, {"sea_lantern", "c8e8e0"},
        {"amethyst", "8f63c7"}, {"wool", "c04040"}, {"hay", "c8a830"}, {"concrete", "d0d0d0"},
        {"copper", "4fa088"}, {"scaffolding", "c8a860"}, {"bell", "e0b030"}, {"campfire", "8a5a30"},
        {"barrel", "8a6035"}, {"crafting", "8a6035"}, {"ladder", "8a6a40"}, {"door", "6a4a30"},
        {"poppy", "c02020"}, {"dandelion", "e8d020"}, {"cornflower", "4060c0"}, {"oxeye", "e8e8e8"},
        {"azure", "d0d8e8"}, {"clay", "a0a6b0"}, {"bedrock", "303030"}, {"cobweb", "e0e0e0"},
        {"ore", "7f7f7f"}, {"glass", "b0d0e0"}
    };
    private static int[] cache = new int[0];

    static {
        EXACT.put("air", 0);
    }

    static synchronized int color(int id) {
        if (id >= cache.length) {
            int[] n = new int[Blocks.size() + 64];
            System.arraycopy(cache, 0, n, 0, cache.length);
            for (int i = cache.length; i < n.length; i++) n[i] = -1;
            cache = n;
        }
        if (cache[id] != -1) return cache[id];
        String name = Blocks.baseName(id);
        int c = 0x888888;
        Integer e = EXACT.get(name);
        if (e != null) c = e;
        else {
            for (String[] p : CONTAINS) {
                if (name.contains(p[0])) {
                    c = Integer.parseInt(p[1], 16);
                    break;
                }
            }
        }
        cache[id] = c;
        return c;
    }
}
