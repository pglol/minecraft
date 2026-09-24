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
 * Writes the "aot_titans" datapack: all hostile life in the overworld is
 * titans, spawned around players by zone.
 *
 * <ul>
 *   <li>Vanilla natural spawning is switched off (no zombies, creepers, endermen...);
 *       horses and farm animals are spawned by the pack instead.</li>
 *   <li>Titans appear by day in Wall Maria territory and outside the walls, as lone
 *       drifters, packs of 2-5, and periodic waves that come from one direction.</li>
 *   <li>Inside Wall Rose, the district towns, Paradis Port, the Hidden Grove, the sea
 *       and Marley are safe - unless an admin starts a Wall breach event.</li>
 * </ul>
 * Only uses /summon, so it works with any mod's titans.
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

    private static final String[] BANNED = {
        "zombie", "husk", "drowned", "zombie_villager", "skeleton", "stray", "bogged", "creeper", "spider",
        "cave_spider", "enderman", "witch", "slime", "phantom", "silverfish", "pillager", "vindicator", "evoker",
        "ravager", "vex", "wandering_trader", "trader_llama"
    };
    private static final String[][] DEFAULT_ANIMALS = {
        {"minecraft:horse", "30"}, {"minecraft:cow", "14"}, {"minecraft:sheep", "18"}, {"minecraft:pig", "10"},
        {"minecraft:chicken", "14"}, {"minecraft:rabbit", "6"}, {"minecraft:fox", "4"}, {"minecraft:donkey", "4"}
    };

    static void write(Path world, AotWorld w, Path config) throws IOException {
        int interval = 8, chance = 60, cap = 8, radius = 110, packChance = 30, waveMinutes = 5, waveSize = 6;
        boolean vanillaMobs = false, animals = true;
        List<Entry> entries = new ArrayList<>();
        List<Entry> animalList = new ArrayList<>();
        for (String raw : Files.readAllLines(config, StandardCharsets.UTF_8)) {
            String line = raw.replaceAll("#.*", "").trim();
            if (line.isEmpty()) continue;
            String[] p = line.split("\\s+");
            switch (p[0]) {
                case "interval": interval = Integer.parseInt(p[1]); break;
                case "chance": chance = Integer.parseInt(p[1]); break;
                case "cap": cap = Integer.parseInt(p[1]); break;
                case "radius": radius = Integer.parseInt(p[1]); break;
                case "pack_chance": packChance = Integer.parseInt(p[1]); break;
                case "wave_minutes": waveMinutes = Integer.parseInt(p[1]); break;
                case "wave_size": waveSize = Integer.parseInt(p[1]); break;
                case "vanilla_mobs": vanillaMobs = p[1].equals("on"); break;
                case "animals": animals = !p[1].equals("off"); break;
                case "animal": animalList.add(new Entry("any", p[1], p.length > 2 ? Integer.parseInt(p[2]) : 10)); break;
                case "maria": case "wild": case "any":
                    if (p.length < 2 || !p[1].contains(":")) throw new IllegalArgumentException("bad line: " + raw);
                    entries.add(new Entry(p[0], p[1], p.length > 2 ? Integer.parseInt(p[2]) : 10));
                    break;
                default: System.out.println("  (ignoring unknown setting: " + raw.trim() + ")");
            }
        }
        if (entries.isEmpty()) throw new IllegalArgumentException(config + " lists no titans - add lines like: any dannys_aot:titan 10");
        if (animalList.isEmpty()) for (String[] a : DEFAULT_ANIMALS) animalList.add(new Entry("any", a[0], Integer.parseInt(a[1])));
        radius = Math.max(radius, 40);
        int waveLoops = Math.max(1, waveMinutes * 60 / interval);

        Atlas a = w.atlas;
        Path root = world.resolve("datapacks").resolve("aot_titans");
        deleteTree(root);
        Path fn = root.resolve("data/aot_titans/function");
        Files.createDirectories(fn.resolve("event"));
        Files.createDirectories(root.resolve("data/aot_titans/predicate"));
        Files.createDirectories(root.resolve("data/aot_titans/tags/entity_type"));
        Files.createDirectories(root.resolve("data/minecraft/tags/function"));
        write(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":48,\"description\":\"Attack on Titan map: titans, waves and wildlife\"}}");
        write(root.resolve("data/minecraft/tags/function/load.json"), "{\"values\":[\"aot_titans:load\"]}");
        write(root.resolve("data/aot_titans/predicate/daytime.json"),
            "{\"condition\":\"minecraft:time_check\",\"value\":{\"min\":0,\"max\":12000},\"period\":24000}");
        write(root.resolve("data/aot_titans/tags/entity_type/titans.json"), tag(entries));
        write(root.resolve("data/aot_titans/tags/entity_type/animals.json"), tag(animalList));
        StringBuilder banned = new StringBuilder("{\"values\":[");
        for (int i = 0; i < BANNED.length; i++) banned.append(i > 0 ? "," : "").append("\"minecraft:").append(BANNED[i]).append('"');
        write(root.resolve("data/aot_titans/tags/entity_type/banned.json"), banned.append("]}").toString());

        // ---- Lifecycle --------------------------------------------------------------------
        StringBuilder load = new StringBuilder();
        load.append("scoreboard objectives add aot_titans dummy\n");
        load.append("scoreboard objectives add aot_wave dummy\n");
        load.append("execute unless score #enabled aot_titans matches 0..1 run scoreboard players set #enabled aot_titans 1\n");
        load.append("execute unless score #breach aot_titans matches 0..1 run scoreboard players set #breach aot_titans 0\n");
        if (!vanillaMobs) {
            load.append("# Titans are the only monsters: vanilla natural spawning is replaced by this pack.\n");
            load.append("gamerule doMobSpawning false\ngamerule doInsomnia false\ngamerule doPatrolSpawning false\ngamerule doTraderSpawning false\n");
        }
        load.append("schedule function aot_titans:loop ").append(interval).append("s replace\n");
        load.append("schedule function aot_titans:cleanup 30s replace\n");
        write(fn.resolve("load.mcfunction"), load.toString());

        StringBuilder loop = new StringBuilder();
        loop.append("schedule function aot_titans:loop ").append(interval).append("s replace\n");
        loop.append("execute if score #enabled aot_titans matches 1 as @a[gamemode=!spectator] at @s if dimension minecraft:overworld if predicate aot_titans:daytime run function aot_titans:player\n");
        if (animals) loop.append("execute as @a[gamemode=!spectator] at @s if dimension minecraft:overworld run function aot_titans:animals\n");
        if (!vanillaMobs) {
            loop.append("execute in minecraft:overworld positioned 0 0 0 as @e[type=#aot_titans:banned,distance=0..] run tp @s ~ -200 ~\n");
        }
        write(fn.resolve("loop.mcfunction"), loop.toString());

        write(fn.resolve("cleanup.mcfunction"), String.join("\n",
            "schedule function aot_titans:cleanup 30s replace",
            "# Titans nobody is near drift away (removed without drops).",
            "execute in minecraft:overworld positioned 0 0 0 as @e[type=#aot_titans:titans,distance=0..] at @s unless entity @a[distance=..240] run tp @s ~ -200 ~"));

        // ---- Zones -----------------------------------------------------------------------
        double seaMid = (a.marleyCoastX(a.marleyCentreZ()) + a.site(Atlas.Kind.PARADIS_PORT).x) / 2;
        StringBuilder z = new StringBuilder("# Returns 0 = safe, 1 = Wall Maria territory, 2 = outside the walls.\n");
        z.append(String.format(Locale.ROOT, "execute if entity @s[x=%d,y=-64,z=%d,dx=%d,dy=500,dz=%d] run return 0%n",
            a.minX - 100, a.minZ - 100, (int) (seaMid - a.minX + 100), a.maxZ - a.minZ + 200));
        z.append(String.format(Locale.ROOT, "execute positioned 0 ~ 0 if entity @s[distance=..%d] run return 0%n", (int) a.sina.radius + 20));
        z.append(String.format(Locale.ROOT, "execute if score #breach aot_titans matches 1 positioned 0 ~ 0 if entity @s[distance=..%d] run return 1%n", (int) a.rose.radius + 20));
        z.append(String.format(Locale.ROOT, "execute positioned 0 ~ 0 if entity @s[distance=..%d] run return 0%n", (int) a.rose.radius + 20));
        z.append("# Towns are safe unless the walls are breached.\n");
        for (Atlas.District d : a.districts) {
            if (d.wall == a.sina) continue;
            z.append(String.format(Locale.ROOT, "execute unless score #breach aot_titans matches 1 positioned %d ~ %d if entity @s[distance=..%d] run return 0%n",
                (int) d.cx, (int) d.cz, (int) d.radius + 25));
        }
        Atlas.Site port = a.site(Atlas.Kind.PARADIS_PORT);
        z.append(String.format(Locale.ROOT, "execute positioned %d ~ %d if entity @s[distance=..%d] run return 0%n", port.x, port.z, port.radius + 60));
        z.append(String.format(Locale.ROOT, "execute positioned %d ~ %d if entity @s[distance=..60] run return 0%n", w.giantForest.groveX, w.giantForest.groveZ));
        z.append(String.format(Locale.ROOT, "execute positioned 0 ~ 0 if entity @s[distance=..%d] run return 1%n", (int) a.maria.radius + 20));
        z.append("return 2\n");
        write(fn.resolve("zone.mcfunction"), z.toString());

        StringBuilder p = new StringBuilder();
        p.append("execute store result score #zone aot_titans run function aot_titans:zone\n");
        p.append("execute if score #zone aot_titans matches 0 run return 0\n");
        p.append("# Waves roll in every few minutes.\n");
        p.append("scoreboard players add @s aot_wave 1\n");
        p.append("execute if score @s aot_wave matches ").append(waveLoops).append(".. run return run function aot_titans:wave\n");
        p.append("execute store result score #near aot_titans if entity @e[type=#aot_titans:titans,distance=..128]\n");
        p.append("execute if score #near aot_titans matches ").append(cap).append(".. run return 0\n");
        p.append("execute store result score #roll aot_titans run random value 1..100\n");
        p.append("execute if score #roll aot_titans matches ").append(chance + 1).append(".. run return 0\n");
        p.append("execute store result score #roll aot_titans run random value 1..100\n");
        p.append("execute if score #roll aot_titans matches ..").append(packChance).append(" run return run function aot_titans:pack\n");
        p.append("function aot_titans:lone\n");
        write(fn.resolve("player.mcfunction"), p.toString());

        // ---- Spawn patterns --------------------------------------------------------------
        write(fn.resolve("lone.mcfunction"), String.join("\n",
            "# A single titan drifting somewhere around the player.",
            "summon marker ~ ~ ~ {Tags:[\"aot_sp\"]}",
            "spreadplayers ~ ~ 0 " + radius + " under 250 false @e[type=marker,tag=aot_sp,limit=1,sort=nearest]",
            "execute as @e[type=marker,tag=aot_sp] at @s if entity @a[distance=..28] run kill @s",
            "execute at @e[type=marker,tag=aot_sp,limit=1] run function aot_titans:pick",
            "kill @e[type=marker,tag=aot_sp]"));
        write(fn.resolve("pack.mcfunction"), String.join("\n",
            "# A pack of 2-5 titans bunched together.",
            "summon marker ~ ~ ~ {Tags:[\"aot_sp\"]}",
            "spreadplayers ~ ~ 0 " + radius + " under 250 false @e[type=marker,tag=aot_sp,limit=1,sort=nearest]",
            "execute as @e[type=marker,tag=aot_sp] at @s if entity @a[distance=..36] run kill @s",
            "execute store result score #n aot_titans run random value 2..5",
            "execute at @e[type=marker,tag=aot_sp,limit=1] run function aot_titans:group",
            "kill @e[type=marker,tag=aot_sp]"));
        StringBuilder wave = new StringBuilder();
        wave.append("# A wave: a group that appears together in one direction, with a warning.\n");
        wave.append("execute store result score @s aot_wave run random value -").append(Math.max(1, waveLoops / 2)).append("..0\n");
        wave.append("summon marker ~ ~ ~ {Tags:[\"aot_sp\"]}\n");
        wave.append("spreadplayers ~ ~ 0 ").append(Math.max(60, radius - 10)).append(" under 250 false @e[type=marker,tag=aot_sp,limit=1,sort=nearest]\n");
        wave.append("execute as @e[type=marker,tag=aot_sp] at @s if entity @a[distance=..45] run kill @s\n");
        wave.append("execute unless entity @e[type=marker,tag=aot_sp] run return 0\n");
        wave.append("execute store result score #n aot_titans run random value ").append(Math.max(2, waveSize - 2)).append("..").append(waveSize + 2).append('\n');
        wave.append("execute at @e[type=marker,tag=aot_sp,limit=1] run function aot_titans:group\n");
        wave.append("title @s times 10 60 20\n");
        wave.append("title @s subtitle {\"text\":\"A wave of titans is closing in\",\"color\":\"gray\"}\n");
        wave.append("title @s title {\"text\":\"Titans approaching!\",\"color\":\"dark_red\",\"bold\":true}\n");
        wave.append("playsound minecraft:block.bell.use master @s ~ ~ ~ 1 0.6\n");
        wave.append("execute at @e[type=marker,tag=aot_sp,limit=1] run playsound minecraft:entity.ravager.roar hostile @a[distance=..160] ~ ~ ~ 4 0.5\n");
        wave.append("kill @e[type=marker,tag=aot_sp]\n");
        write(fn.resolve("wave.mcfunction"), wave.toString());

        StringBuilder group = new StringBuilder("# Spawns #n titans spread within 12 blocks of here.\n");
        for (int i = 1; i <= Math.max(waveSize + 2, 12); i++) {
            group.append("execute if score #n aot_titans matches ").append(i).append(".. run function aot_titans:group_one\n");
        }
        write(fn.resolve("group.mcfunction"), group.toString());
        write(fn.resolve("group_one.mcfunction"), String.join("\n",
            "summon marker ~ ~ ~ {Tags:[\"aot_pk\"]}",
            "spreadplayers ~ ~ 0 12 under 250 false @e[type=marker,tag=aot_pk,limit=1,sort=nearest]",
            "execute at @e[type=marker,tag=aot_pk,limit=1] run function aot_titans:pick",
            "kill @e[type=marker,tag=aot_pk]"));

        // Weighted pick of a titan for the zone (#zone: 1 = Maria, 2 = wild).
        write(fn.resolve("pick.mcfunction"), String.join("\n",
            "execute if score #zone aot_titans matches ..1 run return run function aot_titans:pick_maria",
            "function aot_titans:pick_wild"));
        pickFile(fn.resolve("pick_maria.mcfunction"), filter(entries, "maria"));
        pickFile(fn.resolve("pick_wild.mcfunction"), filter(entries, "wild"));

        // ---- Wildlife --------------------------------------------------------------------
        if (animals) {
            StringBuilder an = new StringBuilder("# Horses and farm animals roam the countryside.\n");
            an.append("execute store result score #near aot_titans if entity @e[type=#aot_titans:animals,distance=..96]\n");
            an.append("execute if score #near aot_titans matches 10.. run return 0\n");
            an.append("execute store result score #roll aot_titans run random value 1..100\n");
            an.append("execute if score #roll aot_titans matches 21.. run return 0\n");
            an.append("summon marker ~ ~ ~ {Tags:[\"aot_an\"]}\n");
            an.append("spreadplayers ~ ~ 0 64 under 250 false @e[type=marker,tag=aot_an,limit=1,sort=nearest]\n");
            an.append("execute as @e[type=marker,tag=aot_an] at @s if entity @a[distance=..20] run kill @s\n");
            an.append("execute as @e[type=marker,tag=aot_an] at @s unless block ~ ~-1 ~ #minecraft:dirt run kill @s\n");
            an.append("execute store result score #n aot_titans run random value 1..3\n");
            an.append("execute at @e[type=marker,tag=aot_an,limit=1] run function aot_titans:herd\n");
            an.append("kill @e[type=marker,tag=aot_an]\n");
            write(fn.resolve("animals.mcfunction"), an.toString());
            write(fn.resolve("herd.mcfunction"), "execute store result score #pick aot_titans run random value 1.." + total(animalList) + "\n"
                + "function aot_titans:herd_one\nexecute if score #n aot_titans matches 2.. run function aot_titans:herd_one\n"
                + "execute if score #n aot_titans matches 3.. run function aot_titans:herd_one\n");
            StringBuilder one = new StringBuilder();
            int from = 1;
            for (Entry e : animalList) {
                int to = from + e.weight - 1;
                one.append(String.format(Locale.ROOT, "execute if score #pick aot_titans matches %d..%d run summon %s ~ ~ ~%n", from, to, e.id));
                from = to + 1;
            }
            write(fn.resolve("herd_one.mcfunction"), one.toString());
        }

        // ---- Admin tools and world events -----------------------------------------------
        write(fn.resolve("on.mcfunction"), "scoreboard players set #enabled aot_titans 1\ntellraw @s {\"text\":\"Titan spawning on\",\"color\":\"red\"}");
        write(fn.resolve("off.mcfunction"), "scoreboard players set #enabled aot_titans 0\ntellraw @s {\"text\":\"Titan spawning off\",\"color\":\"green\"}");
        write(fn.resolve("status.mcfunction"), String.join("\n",
            "execute store result score #zone aot_titans run function aot_titans:zone",
            "execute store result score #near aot_titans if entity @e[type=#aot_titans:titans,distance=..128]",
            "tellraw @s [{\"text\":\"Titans \",\"color\":\"gold\"},{\"text\":\"enabled=\",\"color\":\"gray\"},{\"score\":{\"name\":\"#enabled\",\"objective\":\"aot_titans\"}},"
                + "{\"text\":\"  breach=\",\"color\":\"gray\"},{\"score\":{\"name\":\"#breach\",\"objective\":\"aot_titans\"}},"
                + "{\"text\":\"  your zone=\",\"color\":\"gray\"},{\"score\":{\"name\":\"#zone\",\"objective\":\"aot_titans\"}},"
                + "{\"text\":\" (0 safe, 1 Wall Maria, 2 wilds)  titans near you=\",\"color\":\"gray\"},{\"score\":{\"name\":\"#near\",\"objective\":\"aot_titans\"}}]",
            "execute unless predicate aot_titans:daytime run tellraw @s {\"text\":\"It is night: titans do not spawn until morning.\",\"color\":\"gray\"}"));
        write(fn.resolve("test.mcfunction"), String.join("\n",
            "# Spawns a pack right now near you, anywhere, to check the titan ids work.",
            "scoreboard players set #zone aot_titans 2",
            "scoreboard players set #n aot_titans 3",
            "summon marker ~ ~ ~ {Tags:[\"aot_sp\"]}",
            "spreadplayers ~ ~ 0 40 under 250 false @e[type=marker,tag=aot_sp,limit=1,sort=nearest]",
            "execute at @e[type=marker,tag=aot_sp,limit=1] run function aot_titans:group",
            "kill @e[type=marker,tag=aot_sp]",
            "tellraw @s {\"text\":\"Spawned a test pack of titans within 40 blocks.\",\"color\":\"gold\"}"));
        write(fn.resolve("event/breach_on.mcfunction"), String.join("\n",
            "scoreboard players set #breach aot_titans 1",
            "title @a times 10 80 20",
            "title @a subtitle {\"text\":\"Titans are inside Wall Rose - the towns are not safe\",\"color\":\"gray\"}",
            "title @a title {\"text\":\"THE WALL HAS BEEN BREACHED\",\"color\":\"dark_red\",\"bold\":true}",
            "execute as @a at @s run playsound minecraft:block.bell.use master @s ~ ~ ~ 1 0.5"));
        write(fn.resolve("event/breach_off.mcfunction"), String.join("\n",
            "scoreboard players set #breach aot_titans 0",
            "title @a title {\"text\":\"The breach has been sealed\",\"color\":\"green\"}"));
        write(fn.resolve("event/wave.mcfunction"), String.join("\n",
            "# Sends a wave at every player who is not in a safe zone right now.",
            "execute as @a[gamemode=!spectator] at @s if dimension minecraft:overworld run function aot_titans:event/wave_one"));
        write(fn.resolve("event/wave_one.mcfunction"), String.join("\n",
            "execute store result score #zone aot_titans run function aot_titans:zone",
            "execute if score #zone aot_titans matches 0 run return 0",
            "function aot_titans:wave"));
        write(fn.resolve("event/horde.mcfunction"), String.join("\n",
            "# Run as a player: /execute as <name> at @s run function aot_titans:event/horde",
            "scoreboard players set #zone aot_titans 2",
            "scoreboard players set #n aot_titans 12",
            "summon marker ~ ~ ~ {Tags:[\"aot_sp\"]}",
            "spreadplayers ~ ~ 0 90 under 250 false @e[type=marker,tag=aot_sp,limit=1,sort=nearest]",
            "execute at @e[type=marker,tag=aot_sp,limit=1] run function aot_titans:group",
            "title @a[distance=..200] title {\"text\":\"A HORDE IS COMING\",\"color\":\"dark_red\",\"bold\":true}",
            "execute as @a[distance=..200] at @s run playsound minecraft:entity.ravager.roar hostile @s ~ ~ ~ 1 0.5",
            "kill @e[type=marker,tag=aot_sp]"));
    }

    private static List<Entry> filter(List<Entry> all, String zone) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) if (e.zone.equals(zone) || e.zone.equals("any")) out.add(e);
        return out.isEmpty() ? all : out;
    }

    private static int total(List<Entry> list) {
        int t = 0;
        for (Entry e : list) t += e.weight;
        return t;
    }

    private static void pickFile(Path file, List<Entry> list) throws IOException {
        StringBuilder s = new StringBuilder("execute store result score #pick aot_titans run random value 1.." + total(list) + "\n");
        int from = 1;
        for (Entry e : list) {
            int to = from + e.weight - 1;
            s.append(String.format(Locale.ROOT, "execute if score #pick aot_titans matches %d..%d run summon %s ~ ~ ~%n", from, to, e.id));
            from = to + 1;
        }
        write(file, s.toString());
    }

    private static String tag(List<Entry> list) {
        Set<String> ids = new LinkedHashSet<>();
        for (Entry e : list) ids.add(e.id);
        StringBuilder t = new StringBuilder("{\"values\":[");
        int i = 0;
        for (String id : ids) t.append(i++ > 0 ? "," : "").append("{\"id\":\"").append(id).append("\",\"required\":false}");
        return t.append("]}").toString();
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (java.util.stream.Stream<Path> s = Files.walk(p)) {
            for (Path q : s.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(q);
        }
    }

    private static void write(Path p, String s) throws IOException {
        Files.writeString(p, s.endsWith("\n") ? s : s + "\n", StandardCharsets.UTF_8);
    }
}
