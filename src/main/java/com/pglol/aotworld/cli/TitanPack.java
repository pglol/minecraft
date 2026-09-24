package com.pglol.aotworld.cli;

import com.pglol.aotworld.core.AotWorld;
import com.pglol.aotworld.core.Atlas;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Writes a datapack that spawns a mod's titans around players in the
 * overworld, following the map's zones: Wall Maria territory and the wilds
 * outside the walls get titans; everything inside Wall Rose, the district
 * towns, Paradis Port, the Hidden Grove and Marley stay safe. Titans only
 * appear by day. Works with any mod, since it only uses /summon.
 */
final class TitanPack {
    private TitanPack() {}

    static final class Entry {
        final String zone, id;
        final int weight;

        Entry(String zone, String id, int weight) {
            this.zone = zone;
            this.id = id;
            this.weight = weight;
        }
    }

    static void write(Path world, AotWorld w, Path config) throws IOException {
        int interval = 10, chance = 35, cap = 4, radius = 70;
        List<Entry> entries = new ArrayList<>();
        for (String raw : Files.readAllLines(config, StandardCharsets.UTF_8)) {
            String line = raw.replaceAll("#.*", "").trim();
            if (line.isEmpty()) continue;
            String[] p = line.split("\\s+");
            switch (p[0]) {
                case "interval": interval = Integer.parseInt(p[1]); break;
                case "chance": chance = Integer.parseInt(p[1]); break;
                case "cap": cap = Integer.parseInt(p[1]); break;
                case "radius": radius = Integer.parseInt(p[1]); break;
                case "maria": case "wild": case "any":
                    if (p.length < 2 || !p[1].contains(":")) throw new IllegalArgumentException("bad line: " + raw);
                    entries.add(new Entry(p[0], p[1], p.length > 2 ? Integer.parseInt(p[2]) : 10));
                    break;
                default: throw new IllegalArgumentException("unknown setting in " + config + ": " + raw);
            }
        }
        if (entries.isEmpty()) throw new IllegalArgumentException(config + " lists no titans - add lines like: any dannys_aot:titan 10");

        Atlas a = w.atlas;
        Path root = world.resolve("datapacks").resolve("aot_titans");
        Path fn = root.resolve("data/aot_titans/function");
        Files.createDirectories(fn);
        Files.createDirectories(root.resolve("data/aot_titans/predicate"));
        Files.createDirectories(root.resolve("data/aot_titans/tags/entity_type"));
        Files.createDirectories(root.resolve("data/minecraft/tags/function"));
        write(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":48,\"description\":\"Attack on Titan map: titan spawning by zone\"}}");
        write(root.resolve("data/minecraft/tags/function/load.json"), "{\"values\":[\"aot_titans:load\"]}");
        write(root.resolve("data/aot_titans/predicate/daytime.json"),
            "{\"condition\":\"minecraft:time_check\",\"value\":{\"min\":0,\"max\":12000},\"period\":24000}");
        Set<String> ids = new LinkedHashSet<>();
        for (Entry e : entries) ids.add(e.id);
        StringBuilder tag = new StringBuilder("{\"values\":[");
        int i = 0;
        for (String id : ids) tag.append(i++ > 0 ? "," : "").append("{\"id\":\"").append(id).append("\",\"required\":false}");
        write(root.resolve("data/aot_titans/tags/entity_type/titans.json"), tag.append("]}").toString());

        write(fn.resolve("load.mcfunction"), String.join("\n",
            "scoreboard objectives add aot_titans dummy",
            "execute unless score #enabled aot_titans matches 0..1 run scoreboard players set #enabled aot_titans 1",
            "schedule function aot_titans:loop " + interval + "s replace"));
        write(fn.resolve("loop.mcfunction"), String.join("\n",
            "schedule function aot_titans:loop " + interval + "s replace",
            "execute if score #enabled aot_titans matches 1 as @a[gamemode=!spectator] at @s if dimension minecraft:overworld if predicate aot_titans:daytime run function aot_titans:player"));
        write(fn.resolve("on.mcfunction"), "scoreboard players set #enabled aot_titans 1\ntellraw @s {\"text\":\"Titan spawning on\",\"color\":\"red\"}");
        write(fn.resolve("off.mcfunction"), "scoreboard players set #enabled aot_titans 0\ntellraw @s {\"text\":\"Titan spawning off\",\"color\":\"green\"}");

        // Safe zones.
        StringBuilder p = new StringBuilder("# Safe inside Wall Rose.\n");
        p.append(String.format(Locale.ROOT, "execute positioned 0 ~ 0 if entity @s[distance=..%d] run return 0%n", (int) a.rose.radius + 20));
        double seaMid = (a.marleyCoastX(a.marleyCentreZ()) + a.site(Atlas.Kind.PARADIS_PORT).x) / 2;
        p.append("# Safe at sea and in Marley.\n");
        p.append(String.format(Locale.ROOT, "execute if entity @s[x=%d,y=-64,z=%d,dx=%d,dy=500,dz=%d] run return 0%n",
            a.minX - 100, a.minZ - 100, (int) (seaMid - a.minX + 100), a.maxZ - a.minZ + 200));
        p.append("# Safe in district towns, Paradis Port and the Hidden Grove.\n");
        for (Atlas.District d : a.districts) {
            if (d.wall == a.sina) continue;
            p.append(String.format(Locale.ROOT, "execute positioned %d ~ %d if entity @s[distance=..%d] run return 0%n",
                (int) d.cx, (int) d.cz, (int) d.radius + 25));
        }
        Atlas.Site port = a.site(Atlas.Kind.PARADIS_PORT);
        p.append(String.format(Locale.ROOT, "execute positioned %d ~ %d if entity @s[distance=..%d] run return 0%n", port.x, port.z, port.radius + 60));
        p.append(String.format(Locale.ROOT, "execute positioned %d ~ %d if entity @s[distance=..60] run return 0%n",
            w.giantForest.groveX, w.giantForest.groveZ));
        p.append("# Cap and chance.\n");
        p.append("execute store result score #near aot_titans if entity @e[type=#aot_titans:titans,distance=..128]\n");
        p.append("execute if score #near aot_titans matches " + cap + ".. run return 0\n");
        p.append("execute store result score #roll aot_titans run random value 1..100\n");
        p.append("execute if score #roll aot_titans matches " + (chance + 1) + ".. run return 0\n");
        boolean maria = zone(fn, "maria", entries, radius), wild = zone(fn, "wild", entries, radius);
        p.append(String.format(Locale.ROOT, "execute positioned 0 ~ 0 if entity @s[distance=..%d] run return %s%n",
            (int) a.maria.radius + 20, maria ? "run function aot_titans:spawn_maria" : "0"));
        if (wild) p.append("function aot_titans:spawn_wild\n");
        write(fn.resolve("player.mcfunction"), p.toString());
    }

    private static boolean zone(Path fn, String zone, List<Entry> entries, int radius) throws IOException {
        List<Entry> list = new ArrayList<>();
        for (Entry e : entries) if (e.zone.equals(zone) || e.zone.equals("any")) list.add(e);
        if (list.isEmpty()) return false;
        int total = 0;
        for (Entry e : list) total += e.weight;
        StringBuilder s = new StringBuilder();
        s.append("summon marker ~ ~ ~ {Tags:[\"aot_sp\"]}\n");
        s.append("spreadplayers ~ ~ 0 ").append(radius).append(" under 250 false @e[type=marker,tag=aot_sp,limit=1,sort=nearest]\n");
        s.append("# Too close to someone (or no dry ground found): skip.\n");
        s.append("execute as @e[type=marker,tag=aot_sp] at @s if entity @a[distance=..24] run kill @s\n");
        s.append("execute store result score #pick aot_titans run random value 1..").append(total).append('\n');
        int from = 1;
        for (Entry e : list) {
            int to = from + e.weight - 1;
            s.append(String.format(Locale.ROOT, "execute if score #pick aot_titans matches %d..%d at @e[type=marker,tag=aot_sp,limit=1] run summon %s%n",
                from, to, e.id));
            from = to + 1;
        }
        s.append("kill @e[type=marker,tag=aot_sp]\n");
        write(fn.resolve("spawn_" + zone + ".mcfunction"), s.toString());
        return true;
    }

    private static void write(Path p, String s) throws IOException {
        Files.writeString(p, s.endsWith("\n") ? s : s + "\n", StandardCharsets.UTF_8);
    }
}
