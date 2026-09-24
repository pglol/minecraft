package com.pglol.aotrpg;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Named map locations, read from aot-rpg.json that the world generator writes into the
 * world folder: {"places": {"trost-district": [x, y, z], ...}}.
 */
public final class Places {
    private final Map<String, int[]> places = new HashMap<>();

    public void load(MinecraftServer server) {
        places.clear();
        Path f = server.getSavePath(WorldSavePath.ROOT).resolve("aot-rpg.json");
        if (!Files.exists(f)) {
            AotRpg.LOG.warn("No aot-rpg.json in the world folder; characters will start at world spawn");
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject p = root.getAsJsonObject("places");
            for (Map.Entry<String, JsonElement> e : p.entrySet()) {
                var a = e.getValue().getAsJsonArray();
                places.put(e.getKey(), new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
            }
            AotRpg.LOG.info("Loaded {} map places", places.size());
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read {}", f, e);
        }
    }

    public int[] get(String id) {
        return places.get(id);
    }
}
