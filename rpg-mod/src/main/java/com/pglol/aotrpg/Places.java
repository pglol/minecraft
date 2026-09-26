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
            homes.clear();
            if (root.has("homes")) {
                for (JsonElement e : root.getAsJsonArray("homes")) {
                    var a = e.getAsJsonArray();
                    int[] h = new int[a.size()];
                    for (int i = 0; i < h.length; i++) h[i] = a.get(i).getAsInt();
                    homes.add(h);
                }
            }
            if (root.has("walls")) {
                JsonObject wl = root.getAsJsonObject("walls");
                walls = new int[] {wl.get("sina").getAsInt(), wl.get("rose").getAsInt(), wl.get("maria").getAsInt()};
            }
            if (root.has("safe")) {
                JsonObject sf = root.getAsJsonObject("safe");
                if (walls == null) {
                    int rose = sf.get("rose").getAsInt();
                    walls = new int[] {rose * 250 / 380, rose, rose * 480 / 380};
                }
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
        loadPlots(f.getParent().resolve("plots.json"));
        AotRpg.HOMES.index();
    }

    /** A property plot: interior box, floor height, gate side (north/south/west/east), region. */
    public record PlotInfo(int id, String kind, String size, int x0, int z0, int x1, int z1, int y, String gate, String region) { }

    public final java.util.List<PlotInfo> plots = new java.util.ArrayList<>();

    private void loadPlots(Path file) {
        plots.clear();
        if (!Files.exists(file)) return;
        try {
            for (JsonElement e : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject(), in = o.getAsJsonObject("interior");
                plots.add(new PlotInfo(o.get("id").getAsInt(), o.get("kind").getAsString(), o.get("size").getAsString(),
                    in.get("x0").getAsInt(), in.get("z0").getAsInt(), in.get("x1").getAsInt(), in.get("z1").getAsInt(),
                    o.get("floorY").getAsInt(), o.get("gate").getAsString(), o.get("region").getAsString()));
            }
            AotRpg.LOG.info("Loaded {} property plots", plots.size());
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read {}", file, e);
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

    /** Every town house: {x0, z0, x1, z1, floorY, roofY, doorX, doorZ}. */
    public final java.util.List<int[]> homes = new java.util.ArrayList<>();

    /** Wall radii {Sina, Rose, Maria} around the capital at 0,0 (null before the json is read). */
    public int[] walls;

    /** The nearest area of any of these looks (town, safe, marley...), or null. */
    public Net.Area nearest(double x, double z, double maxDist, String... looks) {
        Net.Area best = null;
        double bd = maxDist * maxDist;
        for (Net.Area a : areas()) {
            boolean ok = looks.length == 0;
            for (String l : looks) if (a.look().equals(l)) ok = true;
            if (!ok) continue;
            double d = (a.x() - x) * (a.x() - x) + (a.z() - z) * (a.z() - z);
            if (d < bd) {
                bd = d;
                best = a;
            }
        }
        return best;
    }

    private static final java.util.Set<String> WILDS = java.util.Set.of("Outside the Walls", "Northern Highlands", "Southern Reaches", "Sand Barrens");

    private Net.Area named(String name) {
        for (Net.Area a : areas()) if (a.name().equals(name)) return a;
        return null;
    }

    /**
     * The region a point lies in, as the entry titles have it: a town or camp you are right at,
     * otherwise the ring between the walls, or beyond Wall Maria the open wilds (Outside the
     * Walls, the Highlands at the island's ends), or Marley across the sea.
     */
    public Net.Area areaAt(double x, double z) {
        int[] w = walls;
        double d = Math.hypot(x, z);
        Net.Area marley = nearest(x, z, 900, "marley");
        if (marley != null && (w == null || d > w[2] * 1.3)) return marley;
        if (w != null && d > w[2] + 40) {
            Net.Area town = nearest(x, z, 90, "town", "camp", "cave");
            if (town != null) return town;
            double edge = w[2] + 1500;
            Net.Area a = z < -edge ? named("Northern Highlands") : z > edge ? named("Southern Reaches") : null;
            if (a != null) return a;
            Net.Area sand = named("Sand Barrens");
            if (sand != null && Math.hypot(sand.x() - x, sand.z() - z) < 450) return sand;
            Net.Area out = named("Outside the Walls");
            if (out != null) return out;
        }
        Net.Area town = nearest(x, z, 150, "town", "camp", "cave", "landmark");
        if (town != null) return town;
        Net.Area best = null;
        double bd = Double.MAX_VALUE;
        for (Net.Area a : areas()) {
            if (WILDS.contains(a.name()) || a.look().equals("marley") || a.look().equals("sea")) continue;
            double dd = (a.x() - x) * (a.x() - x) + (a.z() - z) * (a.z() - z);
            if (dd < bd) {
                bd = dd;
                best = a;
            }
        }
        return best;
    }

    /** The level of the region here (the middle of its range), 1 when unknown. */
    public int levelAt(double x, double z) {
        Net.Area a = areaAt(x, z);
        return a == null ? 1 : Math.max(1, (a.min() + a.max()) / 2);
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
