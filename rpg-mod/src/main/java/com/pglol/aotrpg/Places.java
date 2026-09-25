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
    /** Map images: "world" plus town plan tiles. */
    private final java.util.Map<String, byte[]> mapBytes = new java.util.LinkedHashMap<>();
    private final java.util.List<Net.MapFile> mapFiles = new java.util.ArrayList<>();
    private boolean mapOutdated;

    public void load(MinecraftServer server) {
        places.clear();
        campfires.clear();
        areas.clear();
        mapBytes.clear();
        mapFiles.clear();
        mapOutdated = false;
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
                mapOutdated = !m.has("version") || m.get("version").getAsInt() < 2;
                addMap("world", f.getParent().resolve(m.get("file").getAsString()), m.get("x0").getAsInt(), m.get("z0").getAsInt(), m.get("bpp").getAsInt());
            }
            if (root.has("tiles")) {
                int i = 0;
                for (JsonElement e : root.getAsJsonArray("tiles")) {
                    JsonObject t = e.getAsJsonObject();
                    addMap("t" + i++, f.getParent().resolve(t.get("file").getAsString()), t.get("x0").getAsInt(), t.get("z0").getAsInt(), t.get("bpp").getAsInt());
                }
            }
            long kb = 0;
            for (byte[] bb : mapBytes.values()) kb += bb.length / 1024;
            AotRpg.LOG.info("Loaded {} places, {} areas, {} campfires, {} map images ({} KB){}", places.size(), areas.size(),
                campfires.size(), mapBytes.size(), kb, mapOutdated ? " - OUTDATED map: run the new add-titans.bat" : "");
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

    /** The level of the nearest area (the middle of its range), 1 when unknown. */
    public int levelAt(double x, double z) {
        Net.Area best = null;
        double bd = Double.MAX_VALUE;
        for (Net.Area a : areas()) {
            double d = (a.x() - x) * (a.x() - x) + (a.z() - z) * (a.z() - z);
            if (d < bd) {
                bd = d;
                best = a;
            }
        }
        return best == null ? 1 : Math.max(1, (best.min() + best.max()) / 2);
    }

    public Net.Area area(String id) {
        for (Net.Area a : areas) if (a.id().equals(id)) return a;
        return null;
    }

    private void addMap(String name, Path file, int x0, int z0, int bpp) throws Exception {
        if (!Files.exists(file)) return;
        byte[] data = Files.readAllBytes(file);
        var md = java.security.MessageDigest.getInstance("SHA-1").digest(data);
        StringBuilder hx = new StringBuilder();
        for (int i = 0; i < 10; i++) hx.append(String.format("%02x", md[i]));
        mapBytes.put(name, data);
        mapFiles.add(new Net.MapFile(name, hx.toString(), x0, z0, bpp, data.length));
    }

    public Net.MapFiles mapInfo() {
        return mapFiles.isEmpty() ? null : new Net.MapFiles(mapOutdated, new java.util.ArrayList<>(mapFiles));
    }

    public byte[] mapBytes(String name) {
        return mapBytes.get(name);
    }

    public int[] get(String id) {
        return places.get(id);
    }
}
