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
    private final java.util.List<Net.Area> areas = new java.util.ArrayList<>();
    private Path mapFile;
    private int mapX0, mapZ0, mapBpp;
    private byte[] mapBytes;
    private String mapHash = "";

    public void load(MinecraftServer server) {
        places.clear();
        campfires.clear();
        areas.clear();
        mapBytes = null;
        mapHash = "";
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
            if (root.has("areas")) {
                for (JsonElement e : root.getAsJsonArray("areas")) {
                    JsonObject o = e.getAsJsonObject();
                    areas.add(new Net.Area(o.get("id").getAsString(), o.get("name").getAsString(), o.get("sub").getAsString(),
                        o.get("min").getAsInt(), o.get("max").getAsInt(), o.get("titans").getAsInt(), o.get("look").getAsString(),
                        o.get("prio").getAsInt(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()));
                }
            }
            if (root.has("safe")) {
                JsonObject sf = root.getAsJsonObject("safe");
                java.util.List<int[]> zones = new java.util.ArrayList<>();
                for (JsonElement e : sf.getAsJsonArray("zones")) {
                    var a = e.getAsJsonArray();
                    zones.add(new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
                }
                AotRpg.GUARD.set(sf.get("rose").getAsInt(), zones);
            }
            if (root.has("map")) {
                JsonObject m = root.getAsJsonObject("map");
                mapFile = f.getParent().resolve(m.get("file").getAsString());
                mapX0 = m.get("x0").getAsInt();
                mapZ0 = m.get("z0").getAsInt();
                mapBpp = m.get("bpp").getAsInt();
                if (Files.exists(mapFile)) {
                    mapBytes = Files.readAllBytes(mapFile);
                    var md = java.security.MessageDigest.getInstance("SHA-1").digest(mapBytes);
                    StringBuilder hx = new StringBuilder();
                    for (int i = 0; i < 10; i++) hx.append(String.format("%02x", md[i]));
                    mapHash = hx.toString();
                }
            }
            AotRpg.LOG.info("Loaded {} places, {} areas, {} campfires, map {}", places.size(), areas.size(), campfires.size(),
                mapBytes == null ? "missing" : (mapBytes.length / 1024) + " KB");
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

    public java.util.List<Net.Area> areas() {
        return areas;
    }

    public Net.Area area(String id) {
        for (Net.Area a : areas) if (a.id().equals(id)) return a;
        return null;
    }

    public Net.MapInfo mapInfo() {
        return mapBytes == null ? null : new Net.MapInfo(mapHash, mapX0, mapZ0, mapBpp, mapBytes.length);
    }

    public byte[] mapBytes() {
        return mapBytes;
    }

    public int[] get(String id) {
        return places.get(id);
    }
}
