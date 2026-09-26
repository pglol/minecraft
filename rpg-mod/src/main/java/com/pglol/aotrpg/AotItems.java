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

    public static volatile String namespace;
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
        b.append("# Starter kit picks: harness (legs)=").append(exact("odm_gear") != null ? namespace + ":odm_gear" : "none").append(" grips(x2, sheathed)=").append(exact("blade") != null ? namespace + ":blade" : "none")
            .append(" blades=").append(exact("blade_component") != null ? namespace + ":blade_component" : best(BLADE, "grip", "handle"))
            .append(" gas=").append(best(GAS)).append(" ice burst=").append(exact("ice_burst_cluster") != null ? namespace + ":ice_burst_cluster" : String.valueOf(best(CLUSTER)))
            .append(" uniform=").append(exact("uniform") != null ? namespace + ":uniform" : "none").append("\n\n");
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

    /** Variants that are not the standard issue gear. */
    public static final String[] SPECIAL = {"anti", "personnel", "ap_", "_ap", "kenny", "broken", "damaged", "upgrade", "creative",
        "thunder", "spear", "cannon"};

    /**
     * The best standard item for the keywords: earlier keywords win, special variants
     * (anti-personnel, broken, ...) and ids containing "avoid" words are skipped, shorter ids win.
     */
    public static Identifier best(String[] keywords, String... avoid) {
        for (String k : keywords) {
            Identifier pick = null;
            for (Identifier id : ITEMS) {
                String p = id.getPath();
                if (!p.contains(k)) continue;
                boolean bad = false;
                for (String x : SPECIAL) if (p.contains(x)) bad = true;
                for (String x : avoid) if (p.contains(x)) bad = true;
                if (bad) continue;
                if (pick == null || p.length() < pick.getPath().length()) pick = id;
            }
            if (pick != null) return pick;
        }
        return null;
    }

    public static ItemStack bestStack(int count, String[] keywords, String... avoid) {
        Identifier id = best(keywords, avoid);
        if (id == null) return ItemStack.EMPTY;
        Item item = Registries.ITEM.get(id);
        return new ItemStack(item, Math.max(1, Math.min(count, item.getMaxCount())));
    }

    public static final String[] ODM = {"odm_gear", "odm", "maneuver", "3dmg"};
    public static final String[] CLUSTER = {"ice_burst_cluster", "ice_burst", "iceburst", "cluster"};
    /** Consumables that live in the satchel and move into the inventory while armed. */
    public static final String[] SUPPLY_PATHS = {"blade_component", "apg_cartridge", "ice_burst_cluster"};
    /** Gear that draws on those supplies while held. */
    public static final String[] USER_PATHS = {"blade", "odm_apg", "apg_gun", "gas_canister"};
    public static final String[] GRIP = {"grip", "handle", "trigger"};
    public static final String[] BLADE = {"blade"};
    public static final String[] GAS = {"gas_canister", "gas", "canister"};

    /** The AoT mod's item with exactly this path (e.g. "uniform"), or null. */
    public static Item exact(String path) {
        if (namespace == null) return null;
        Identifier id = Identifier.of(namespace, path);
        return Registries.ITEM.containsId(id) ? Registries.ITEM.get(id) : null;
    }

    public static boolean isAot(ItemStack s) {
        return namespace != null && !s.isEmpty() && Registries.ITEM.getId(s.getItem()).getNamespace().equals(namespace);
    }

    private static String path(ItemStack s) {
        return Registries.ITEM.getId(s.getItem()).getPath();
    }

    /** Blades, APG cartridges and Ice Burst clusters: satchel supplies. */
    public static boolean isSupply(ItemStack s) {
        if (!isAot(s)) return false;
        String p = path(s);
        for (String x : SUPPLY_PATHS) if (p.equals(x)) return true;
        return false;
    }

    /** Items that use supplies: ODM grips, the APG gun, gas canisters. */
    public static boolean usesSupplies(ItemStack s) {
        if (!isAot(s) || isSupply(s)) return false;
        String p = path(s);
        for (String x : USER_PATHS) if (p.equals(x)) return true;
        return false;
    }

    /** ODM gas canisters: held to refill the gear's gas. */
    public static boolean isGas(ItemStack s) {
        return isAot(s) && path(s).contains("gas_canister");
    }

    public static boolean isApgGun(ItemStack s) {
        return isAot(s) && path(s).contains("apg_gun");
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
