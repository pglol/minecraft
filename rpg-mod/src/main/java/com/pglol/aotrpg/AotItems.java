package com.pglol.aotrpg;

import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Items from the installed Attack on Titan mod (Danny's AoT), found at runtime: the mod's namespace
 * is the one its titan entities use. Items are looked up by keywords so the kit and quest rewards
 * work without hard-coding ids. The full list is written to <world>/aot_rpg/aot-items.txt.
 */
public final class AotItems {
    private AotItems() {}

    private static String namespace;
    private static final List<Identifier> ITEMS = new ArrayList<>();

    public static void scan(MinecraftServer server) {
        Map<String, Integer> votes = new HashMap<>();
        for (EntityType<?> t : Registries.ENTITY_TYPE) {
            Identifier id = Registries.ENTITY_TYPE.getId(t);
            if (id.getPath().contains("titan") && !id.getNamespace().equals("minecraft") && !id.getNamespace().equals(AotRpg.MOD_ID)) {
                votes.merge(id.getNamespace(), 1, Integer::sum);
            }
        }
        namespace = votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        ITEMS.clear();
        if (namespace != null) {
            for (Identifier id : Registries.ITEM.getIds()) if (id.getNamespace().equals(namespace)) ITEMS.add(id);
            ITEMS.sort((a, b) -> a.getPath().compareTo(b.getPath()));
        }
        StringBuilder b = new StringBuilder("# Items from the Attack on Titan mod (" + namespace + "), found by the AoT RPG mod.\n");
        b.append("# Kit/quest picks: odm=").append(find("odm", "maneuver", "3dmg", "gear"))
            .append(" blade=").append(find("blade", "sword")).append(" gas=").append(find("gas", "canister", "tank")).append("\n\n");
        for (Identifier id : ITEMS) b.append(id).append('\n');
        try {
            var f = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("aot-items.txt");
            Files.createDirectories(f.getParent());
            Files.writeString(f, b.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not write aot-items.txt", e);
        }
        AotRpg.LOG.info("AoT mod namespace: {} ({} items)", namespace, ITEMS.size());
    }

    public static boolean present() {
        return namespace != null && !ITEMS.isEmpty();
    }

    /** First AoT item whose id contains any of the keywords (earlier keywords win). */
    public static Identifier find(String... keywords) {
        for (String k : keywords) {
            for (Identifier id : ITEMS) if (id.getPath().contains(k)) return id;
        }
        return null;
    }

    /** A stack of the first matching item, or the fallback if the AoT mod has none. */
    public static ItemStack stack(int count, Item fallback, String... keywords) {
        Identifier id = find(keywords);
        Item item = id == null ? fallback : Registries.ITEM.get(id);
        if (item == null || item == Items.AIR) return fallback == null ? ItemStack.EMPTY : new ItemStack(fallback, count);
        return new ItemStack(item, Math.min(count, item.getMaxCount()));
    }

    public static List<Identifier> all() {
        return ITEMS;
    }
}
