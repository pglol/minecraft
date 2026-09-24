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
        for (Region r : regs) {
            int y = safeY(w, buf, r);
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
        Files.writeString(fn.resolve("places.mcfunction"),
            "tellraw @s {\"text\":\"Places (click to travel)\",\"color\":\"yellow\",\"bold\":true}\n" + list, StandardCharsets.UTF_8);
        Files.writeString(world.resolve("aot-places.txt"), places.toString(), StandardCharsets.UTF_8);
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
