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
    private final java.util.List<int[]> campfires = new java.util.ArrayList<>();
    private Path extraFile;

    public void load(MinecraftServer server) {
        places.clear();
        campfires.clear();
        extraFile = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("campfires.json");
        loadExtra();
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
            if (root.has("campfires")) {
                for (JsonElement e : root.getAsJsonArray("campfires")) {
                    var a = e.getAsJsonArray();
                    campfires.add(new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
                }
            }
            AotRpg.LOG.info("Loaded {} map places and {} campfires", places.size(), campfires.size());
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read {}", f, e);
        }
    }

    /** Campfires placed with /aotrpg campfire (kept apart from the generated list). */
    private void loadExtra() {
        try {
            if (!Files.exists(extraFile)) return;
            for (JsonElement e : JsonParser.parseString(Files.readString(extraFile, StandardCharsets.UTF_8)).getAsJsonArray()) {
                var a = e.getAsJsonArray();
                campfires.add(new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
            }
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read {}", extraFile, e);
        }
    }

    public void addCampfire(int x, int y, int z) {
        campfires.add(new int[] {x, y, z});
        try {
            java.util.List<int[]> extra = new java.util.ArrayList<>();
            if (Files.exists(extraFile)) {
                for (JsonElement e : JsonParser.parseString(Files.readString(extraFile, StandardCharsets.UTF_8)).getAsJsonArray()) {
                    var a = e.getAsJsonArray();
                    extra.add(new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
                }
            }
            extra.add(new int[] {x, y, z});
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < extra.size(); i++) {
                int[] c = extra.get(i);
                b.append(i == 0 ? "" : ",").append("[").append(c[0]).append(",").append(c[1]).append(",").append(c[2]).append("]");
            }
            Files.createDirectories(extraFile.getParent());
            Files.writeString(extraFile, b.append("]").toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save {}", extraFile, e);
        }
    }

    public int[] campfireArray() {
        int[] out = new int[campfires.size() * 3];
        for (int i = 0; i < campfires.size(); i++) System.arraycopy(campfires.get(i), 0, out, i * 3, 3);
        return out;
    }

    public int[] get(String id) {
        return places.get(id);
    }
}
