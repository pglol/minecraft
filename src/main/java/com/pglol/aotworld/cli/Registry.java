package com.pglol.aotworld.cli;

import com.pglol.aotworld.core.AotWorld;
import com.pglol.aotworld.core.Region;
import com.pglol.aotworld.core.build.Plot;
import com.pglol.aotworld.core.build.Poi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Writes machine-readable lists for server tooling: every property plot
 * (plots.csv / plots.json) and every mission location (missions.csv).
 */
final class Registry {
    private Registry() {}

    static void write(Path world, AotWorld w) throws IOException {
        StringBuilder csv = new StringBuilder("id,kind,size,width,depth,x0,z0,x1,z1,floor_y,gate,region\n");
        StringBuilder json = new StringBuilder("[\n");
        String[] gates = {"north", "south", "west", "east"};
        for (int i = 0; i < w.plots.size(); i++) {
            Plot p = w.plots.get(i);
            // Buildable interior is inside the fence.
            int ix0 = p.x0 + 1, iz0 = p.z0 + 1, ix1 = p.x1 - 1, iz1 = p.z1 - 1;
            csv.append(String.format(Locale.ROOT, "%d,%s,%s,%d,%d,%d,%d,%d,%d,%d,%s,\"%s\"%n", p.id, p.kind.name().toLowerCase(Locale.ROOT),
                p.sizeName().toLowerCase(Locale.ROOT), p.width(), p.depth(), ix0, iz0, ix1, iz1, p.y + 1, gates[p.gate], p.region));
            json.append(String.format(Locale.ROOT,
                "  {\"id\": %d, \"kind\": \"%s\", \"size\": \"%s\", \"interior\": {\"x0\": %d, \"z0\": %d, \"x1\": %d, \"z1\": %d}, \"floorY\": %d, \"gate\": \"%s\", \"region\": \"%s\"}%s%n",
                p.id, p.kind.name().toLowerCase(Locale.ROOT), p.sizeName().toLowerCase(Locale.ROOT), ix0, iz0, ix1, iz1, p.y + 1,
                gates[p.gate], p.region, i + 1 < w.plots.size() ? "," : ""));
        }
        json.append("]\n");
        Files.writeString(world.resolve("plots.csv"), csv.toString(), StandardCharsets.UTF_8);
        Files.writeString(world.resolve("plots.json"), json.toString(), StandardCharsets.UTF_8);

        StringBuilder m = new StringBuilder("name,type,x,y,z,entrance_x,entrance_z,level_min,level_max,region\n");
        for (Poi p : w.pois) {
            int[] e = p.entrance();
            Region r = w.atlas.regionAt(p.x, p.y, p.z);
            Region around = r;
            m.append(String.format(Locale.ROOT, "\"%s\",%s,%d,%d,%d,%d,%d,%d,%d,\"%s\"%n", p.name, p.kind.name().toLowerCase(Locale.ROOT),
                p.x, p.y, p.z, e[0], e[1], around == null ? 0 : around.minLevel, around == null ? 0 : around.maxLevel,
                around == null ? "" : around.name));
        }
        Files.writeString(world.resolve("missions.csv"), m.toString(), StandardCharsets.UTF_8);
    }
}
