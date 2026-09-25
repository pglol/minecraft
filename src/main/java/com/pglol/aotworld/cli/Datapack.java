package com.pglol.aotworld.cli;

import com.pglol.aotworld.core.AotWorld;
import com.pglol.aotworld.core.ChunkBuffer;
import com.pglol.aotworld.core.Region;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes a small datapack into the world with a warp function per region
 * (e.g. /function aot:warp/trost-district) and a list of places.
 */
final class Datapack {
    private Datapack() {}

    static void write(Path world, AotWorld w) throws IOException {
        Path root = world.resolve("datapacks").resolve("aot_map");
        Path fn = root.resolve("data").resolve("aot").resolve("function");
        Files.createDirectories(fn.resolve("warp"));
        Files.writeString(root.resolve("pack.mcmeta"),
            "{\"pack\":{\"pack_format\":48,\"description\":\"Attack on Titan map: warps and places\"}}\n");

        List<Region> regs = new ArrayList<>(w.atlas.regions());
        regs.sort((a, b) -> Integer.compare(a.minLevel, b.minLevel));
        StringBuilder list = new StringBuilder();
        StringBuilder places = new StringBuilder("# Attack on Titan map - places\n\n");
        ChunkBuffer buf = new ChunkBuffer();
        StringBuilder json = new StringBuilder("{\n  \"places\": {");
        boolean first = true;
        for (Region r : regs) {
            int y = safeY(w, buf, r);
            json.append(first ? "\n" : ",\n").append(String.format(Locale.ROOT, "    \"%s\": [%d, %d, %d]", r.id(), r.warpX, y, r.warpZ));
            first = false;
            String cmd = String.format(Locale.ROOT, "tp @s %d %d %d", r.warpX, y, r.warpZ);
            Files.writeString(fn.resolve("warp").resolve(r.id() + ".mcfunction"),
                "# " + r.name + " (" + r.levelText() + ")\n" + cmd + "\n"
                    + "title @s subtitle {\"text\":\"" + r.levelText() + " - " + r.subtitle + "\",\"color\":\"gray\"}\n"
                    + "title @s title {\"text\":\"" + r.name + "\",\"color\":\"gold\"}\n",
                StandardCharsets.UTF_8);
            list.append("tellraw @s [{\"text\":\"").append(r.levelText()).append("  \",\"color\":\"gray\"},")
                .append("{\"text\":\"").append(r.name).append("\",\"color\":\"gold\",\"clickEvent\":{\"action\":\"run_command\",")
                .append("\"value\":\"/function aot:warp/").append(r.id()).append("\"}}]\n");
            places.append(String.format(Locale.ROOT, "%-9s %-28s x=%-7d y=%-4d z=%-7d /function aot:warp/%s%s%n",
                r.levelText(), r.name, r.warpX, y, r.warpZ, r.id(), r.titanLevel > 0 ? "   titans" : ""));
        }
        // Stable Masters: a clickable list and a warp to each.
        Files.createDirectories(fn.resolve("stable"));
        StringBuilder st = new StringBuilder("tellraw @s {\"text\":\"Stable Masters (click to travel)\",\"color\":\"gold\",\"bold\":true}\n");
        places.append("\n# Stable Masters (horse vendors)\n\n");
        int n = 1;
        for (Object[] e : w.stables) {
            String name = (String) e[0];
            int sx = (Integer) e[1], sz = (Integer) e[2];
            int y = w.terrain.height(sx, sz) + 1;
            Files.writeString(fn.resolve("stable").resolve(n + ".mcfunction"),
                String.format(Locale.ROOT, "tp @s %d %d %d%n", sx + 2, y, sz + 2), StandardCharsets.UTF_8);
            st.append("tellraw @s [{\"text\":\"  Stable Master - \",\"color\":\"gray\"},{\"text\":\"").append(name)
                .append("\",\"color\":\"yellow\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/function aot:stable/")
                .append(n).append("\"}}]\n");
            places.append(String.format(Locale.ROOT, "Stable Master  %-32s x=%-7d z=%-7d /function aot:stable/%d%n", name, sx, sz, n));
            n++;
        }
        Files.writeString(fn.resolve("stables.mcfunction"), st.toString(), StandardCharsets.UTF_8);
        Files.writeString(fn.resolve("places.mcfunction"),
            "tellraw @s {\"text\":\"Places (click to travel)\",\"color\":\"yellow\",\"bold\":true}\n" + list, StandardCharsets.UTF_8);
        Files.writeString(world.resolve("aot-places.txt"), places.toString(), StandardCharsets.UTF_8);
        // Read by the AoT RPG server mod (character origins, quest locations).
        json.append("\n  },\n  \"campfires\": [");
        boolean firstFire = true;
        // Worlds generated before rest stops existed do not have them: leave them off the list.
        Path gen = world.resolve("aot-generator.txt");
        boolean restStops = Files.exists(gen) && Files.readString(gen).contains("reststops");
        for (com.pglol.aotworld.core.build.Poi p : w.pois) {
            int[] f = p.campfire();
            if (f == null || (p.kind == com.pglol.aotworld.core.build.Poi.Kind.REST_STOP && !restStops)) continue;
            json.append(firstFire ? "\n    " : ",\n    ").append(String.format(Locale.ROOT, "[%d, %d, %d]", f[0], f[1], f[2]));
            firstFire = false;
        }
        json.append("\n  ],\n  \"areas\": [");
        boolean firstArea = true;
        for (Region r : regs) {
            int y = r.maxY < 100 ? 12 : w.terrain.height(r.warpX, r.warpZ) + 1;
            json.append(firstArea ? "\n    " : ",\n    ").append(String.format(Locale.ROOT,
                "{\"id\": \"%s\", \"name\": \"%s\", \"sub\": \"%s\", \"min\": %d, \"max\": %d, \"titans\": %d, \"look\": \"%s\", \"prio\": %d, \"x\": %d, \"y\": %d, \"z\": %d}",
                r.id(), r.name.replace("\"", "'"), r.subtitle.replace("\"", "'"), r.minLevel, r.maxLevel, r.titanLevel, look(r), r.priority,
                r.warpX, y, r.warpZ));
            firstArea = false;
        }
        // Places titans may not enter (the RPG mod removes them): everything inside Wall Rose,
        // plus districts, the capital and the story sites.
        com.pglol.aotworld.core.Atlas at = w.atlas;
        json.append("\n  ],\n  \"safe\": {\"rose\": ").append((int) at.rose.radius).append(", \"zones\": [");
        List<String> zones = new ArrayList<>();
        for (com.pglol.aotworld.core.Atlas.District d : at.districts) {
            zones.add(String.format(Locale.ROOT, "[%d, %d, %d]", (int) d.cx, (int) d.cz, (int) d.radius + 40));
        }
        zones.add(String.format(Locale.ROOT, "[0, 0, %d]", (int) at.capitalRadius + 40));
        for (com.pglol.aotworld.core.Atlas.Site site : at.sites) {
            switch (site.kind) {
                case TRAINING_CAMP: case SURVEY_HQ: case NAMED_VILLAGE: case PARADIS_PORT: case REISS_CHAPEL:
                    zones.add(String.format(Locale.ROOT, "[%d, %d, %d]", site.x, site.z, site.radius + 40));
                    break;
                default: break;
            }
        }
        json.append(String.join(", ", zones)).append("]}");
        int bpp = com.pglol.aotworld.preview.MapPreview.gameMapBpp(w);
        json.append(String.format(Locale.ROOT, ",\n  \"map\": {\"file\": \"aot-map.png\", \"x0\": %d, \"z0\": %d, \"bpp\": %d, \"version\": 2}",
            w.atlas.minX - com.pglol.aotworld.preview.MapPreview.GAME_MAP_PAD, w.atlas.minZ, bpp));
        System.out.println("Drawing the in-game world map...");
        javax.imageio.ImageIO.write(com.pglol.aotworld.preview.MapPreview.gameMap(w), "png", world.resolve("aot-map.png").toFile());
        // Detailed town plans that fade in when the map is zoomed in.
        System.out.println("Drawing town plans for the map (this takes a minute)...");
        List<com.pglol.aotworld.preview.MapPreview.Tile> tiles = com.pglol.aotworld.preview.MapPreview.planTiles(w);
        Path tileDir = world.resolve("aot-map");
        Files.createDirectories(tileDir);
        final int tbpp = 2;
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        java.util.stream.IntStream.range(0, tiles.size()).parallel().forEach(i -> {
            try {
                javax.imageio.ImageIO.write(com.pglol.aotworld.preview.MapPreview.planTile(w, tiles.get(i), tbpp), "png",
                    tileDir.resolve("t" + i + ".png").toFile());
                int k = done.incrementAndGet();
                if (k % 10 == 0) System.out.println("  " + k + " / " + tiles.size());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        json.append(",\n  \"tiles\": [");
        for (int i = 0; i < tiles.size(); i++) {
            var t = tiles.get(i);
            json.append(i == 0 ? "\n    " : ",\n    ").append(String.format(Locale.ROOT,
                "{\"file\": \"aot-map/t%d.png\", \"x0\": %d, \"z0\": %d, \"bpp\": %d}", i, t.cx() - t.half(), t.cz() - t.half(), tbpp));
        }
        json.append("\n  ]\n}\n");
        Files.writeString(world.resolve("aot-rpg.json"), json.toString(), StandardCharsets.UTF_8);
    }

    /** Map label colour theme for a region (matches the entry titles). */
    private static String look(Region r) {
        String n = r.name, s = r.subtitle;
        if (s.equals("Titan Cave")) return "cave";
        if (s.equals("Survey Corps Camp")) return "camp";
        if (n.equals("The Sea")) return "sea";
        if (s.startsWith("Marley") || s.equals("The Marleyan Empire")) return "marley";
        if (n.endsWith("District") || n.equals("Mitras") || n.contains("Village") || n.contains("Port")) return "town";
        if (s.equals("Inside Wall Rose") || s.equals("Interior")) return "safe";
        if (s.equals("Titan Territory") || r.titanLevel > 0 || n.equals("Outside the Walls")) return "danger";
        return "landmark";
    }

    /** First standing spot at or above the terrain, found by generating the chunk. */
    private static int safeY(AotWorld w, ChunkBuffer buf, Region r) {
        if (r.maxY < 100) return 12; // Underground City floor
        w.composer.compose(Math.floorDiv(r.warpX, 16), Math.floorDiv(r.warpZ, 16), buf);
        int ground = w.terrain.height(r.warpX, r.warpZ);
        for (int y = Math.max(ground, 64); y < ChunkBuffer.MAX_Y - 2; y++) {
            int b = buf.get(r.warpX, y, r.warpZ);
            if (b != 0 && buf.get(r.warpX, y + 1, r.warpZ) == 0 && buf.get(r.warpX, y + 2, r.warpZ) == 0) return y + 1;
        }
        return ground + 1;
    }
}
