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
        int interval = 6, chance = 75, cap = 12, radius = 150, packChance = 35, waveMinutes = 4, waveSize = 7;
        boolean vanillaMobs = false, animals = true, travellers = true;
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
                case "travellers": travellers = !p[1].equals("off"); break;
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
        Files.createDirectories(root.resolve("data/aot_titans/tags/block"));
        write(root.resolve("data/aot_titans/tags/block/road.json"), "{\"values\":[\"minecraft:dirt_path\",\"minecraft:gravel\","
            + "\"minecraft:coarse_dirt\",\"minecraft:cobblestone\",\"minecraft:mossy_cobblestone\",\"minecraft:stone_bricks\","
            + "\"minecraft:andesite\",\"minecraft:polished_andesite\",\"minecraft:smooth_stone\",\"minecraft:stone\"]}");
        write(root.resolve("data/aot_titans/tags/block/ground.json"), "{\"values\":[\"#minecraft:dirt\",\"#minecraft:sand\",\"minecraft:gravel\","
            + "\"minecraft:stone\",\"minecraft:andesite\",\"minecraft:granite\",\"minecraft:diorite\",\"minecraft:tuff\",\"minecraft:calcite\","
            + "\"minecraft:dirt_path\",\"minecraft:snow_block\",\"minecraft:farmland\",\"minecraft:cobblestone\",\"minecraft:mossy_cobblestone\","
            + "\"minecraft:sandstone\",\"minecraft:clay\"]}");
        write(root.resolve("data/aot_titans/tags/block/passable.json"), "{\"values\":[\"minecraft:air\",\"minecraft:cave_air\","
            + "\"minecraft:short_grass\",\"minecraft:tall_grass\",\"minecraft:fern\",\"minecraft:large_fern\",\"minecraft:dead_bush\","
            + "\"minecraft:snow\",\"#minecraft:flowers\",\"#minecraft:saplings\",\"minecraft:wheat\",\"minecraft:carrots\","
            + "\"minecraft:potatoes\",\"minecraft:beetroots\"]}");

        // ---- Lifecycle --------------------------------------------------------------------
        StringBuilder load = new StringBuilder();
        load.append("scoreboard objectives add aot_titans dummy\n");
        load.append("scoreboard objectives add aot_wave dummy\n");
        load.append("scoreboard objectives add aot_age dummy\n");
        load.append("scoreboard players set #force aot_titans 0\n");
        load.append("schedule function aot_titans:march 5t replace\n");
        if (travellers) load.append("schedule function aot_titans:walk_tick 2t replace\n");
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
        if (travellers) loop.append("execute as @a[gamemode=!spectator] at @s if dimension minecraft:overworld if predicate aot_titans:daytime run function aot_titans:traveller\n");
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
        p.append("# Titans fill the wilds around every player - also when watching from a wall or a town.\n");
        p.append("execute store result score #pz aot_titans run function aot_titans:zone\n");
        p.append("# Waves roll in every few minutes for players out in titan territory.\n");
        p.append("execute if score #pz aot_titans matches 1.. run scoreboard players add @s aot_wave 1\n");
        p.append("execute if score #pz aot_titans matches 1.. if score @s aot_wave matches ").append(waveLoops).append(".. run function aot_titans:wave\n");
        p.append("execute store result score #near aot_titans if entity @e[type=#aot_titans:titans,distance=..").append(radius + 20).append("]\n");
        p.append("execute if score #near aot_titans matches ").append(cap).append(".. run return 0\n");
        p.append("execute store result score #roll aot_titans run random value 1..100\n");
        p.append("execute if score #roll aot_titans matches ").append(chance + 1).append(".. run return 0\n");
        p.append("execute store result score #roll aot_titans run random value 1..100\n");
        p.append("execute if score #roll aot_titans matches ..").append(packChance).append(" run return run function aot_titans:pack\n");
        p.append("function aot_titans:lone\n");
        write(fn.resolve("player.mcfunction"), p.toString());

        // ---- Spawn patterns --------------------------------------------------------------
        // A spot is good when it is on real ground (never leaves), in titan territory, and not
        // right next to a player. Each pattern tries a few spots.
        write(fn.resolve("reject.mcfunction"), "kill @s\nreturn 0");
        write(fn.resolve("check.mcfunction"), String.join("\n",
            "# Run as a marker. Returns 1 if titans may appear here.",
            "execute if entity @a[distance=..#MIN#] run return run function aot_titans:reject",
            "execute unless block ~ ~-1 ~ #aot_titans:ground run return run function aot_titans:reject",
            "execute if block ~ ~ ~ minecraft:water run return run function aot_titans:reject",
            "execute store result score #zone aot_titans run function aot_titans:zone",
            "execute if score #force aot_titans matches 1 if score #zone aot_titans matches 0 run scoreboard players set #zone aot_titans 2",
            "execute if score #zone aot_titans matches 0 run return run function aot_titans:reject",
            "return 1").replace("#MIN#", "30"));
        write(fn.resolve("try_lone.mcfunction"), String.join("\n",
            "summon marker ~ ~ ~ {Tags:[\"aot_sp\"]}",
            "spreadplayers ~ ~ 0 " + radius + " under 250 false @e[type=marker,tag=aot_sp,limit=1,sort=nearest]",
            "execute as @e[type=marker,tag=aot_sp,limit=1] at @s run return run function aot_titans:lone_here"));
        write(fn.resolve("lone_here.mcfunction"), String.join("\n",
            "execute store result score #ok aot_titans run function aot_titans:check",
            "execute if score #ok aot_titans matches 0 run return 0",
            "function aot_titans:pick",
            "kill @s",
            "return 1"));
        write(fn.resolve("lone.mcfunction"), String.join("\n",
            "# A single titan drifting somewhere around the player (up to 4 tries).",
            "execute store result score #ok aot_titans run function aot_titans:try_lone",
            "execute if score #ok aot_titans matches 0 store result score #ok aot_titans run function aot_titans:try_lone",
            "execute if score #ok aot_titans matches 0 store result score #ok aot_titans run function aot_titans:try_lone",
            "execute if score #ok aot_titans matches 0 run function aot_titans:try_lone",
            "tag @e[tag=aot_new] remove aot_new"));
        write(fn.resolve("try_group.mcfunction"), String.join("\n",
            "summon marker ~ ~ ~ {Tags:[\"aot_sp\"]}",
            "spreadplayers ~ ~ 0 #R# under 250 false @e[type=marker,tag=aot_sp,limit=1,sort=nearest]",
            "execute as @e[type=marker,tag=aot_sp,limit=1] at @s run return run function aot_titans:group_here"));
        write(fn.resolve("group_here.mcfunction"), String.join("\n",
            "execute store result score #ok aot_titans run function aot_titans:check",
            "execute if score #ok aot_titans matches 0 run return 0",
            "function aot_titans:group",
            "kill @s",
            "return 1"));
        write(fn.resolve("pack.mcfunction"), String.join("\n",
            "# A pack of 2-5 titans bunched together.",
            "execute store result score #n aot_titans run random value 2..5",
            "scoreboard players set #r aot_titans " + radius,
            "execute store result score #ok aot_titans run function aot_titans:try_group_far",
            "execute if score #ok aot_titans matches 0 store result score #ok aot_titans run function aot_titans:try_group_far",
            "execute if score #ok aot_titans matches 0 run function aot_titans:try_group_far",
            "tag @e[tag=aot_new] remove aot_new"));
        write(fn.resolve("try_group_far.mcfunction"), read(fn, "try_group").replace("#R#", String.valueOf(radius)));
        write(fn.resolve("try_group_near.mcfunction"), read(fn, "try_group").replace("#R#", "80")
            .replace("group_here", "wave_here"));
        Files.delete(fn.resolve("try_group.mcfunction"));
        write(fn.resolve("wave_here.mcfunction"), String.join("\n",
            "# Waves come from 50-80 blocks out.",
            "execute if entity @a[distance=..50] run return run function aot_titans:reject",
            "execute store result score #ok aot_titans run function aot_titans:check",
            "execute if score #ok aot_titans matches 0 run return 0",
            "function aot_titans:group",
            "playsound minecraft:entity.ravager.roar hostile @a[distance=..160] ~ ~ ~ 4 0.5",
            "kill @s",
            "return 1"));

        StringBuilder wave = new StringBuilder();
        wave.append("# A wave: a group appears in one direction and marches on the player.\n");
        wave.append("execute store result score @s aot_wave run random value -").append(Math.max(1, waveLoops / 2)).append("..0\n");
        wave.append("execute store result score #n aot_titans run random value ").append(Math.max(2, waveSize - 2)).append("..").append(waveSize + 2).append('\n');
        wave.append("execute store result score #ok aot_titans run function aot_titans:try_group_near\n");
        for (int i = 0; i < 4; i++) {
            wave.append("execute if score #ok aot_titans matches 0 store result score #ok aot_titans run function aot_titans:try_group_near\n");
        }
        wave.append("execute if score #ok aot_titans matches 0 run return 0\n");
        wave.append("tag @e[tag=aot_new] add aot_wave\n");
        wave.append("tag @e[tag=aot_new] remove aot_new\n");
        wave.append("title @s times 10 60 20\n");
        wave.append("title @s subtitle {\"text\":\"A wave of titans is closing in\",\"color\":\"gray\"}\n");
        wave.append("title @s title {\"text\":\"Titans approaching!\",\"color\":\"dark_red\",\"bold\":true}\n");
        wave.append("playsound minecraft:block.bell.use master @s ~ ~ ~ 1 0.6\n");
        write(fn.resolve("wave.mcfunction"), wave.toString());

        StringBuilder group = new StringBuilder("# Spawns #n titans spread within 12 blocks of here.\n");
        for (int i = 1; i <= Math.max(waveSize + 2, 12); i++) {
            group.append("execute if score #n aot_titans matches ").append(i).append(".. run function aot_titans:group_one\n");
        }
        write(fn.resolve("group.mcfunction"), group.toString());
        write(fn.resolve("group_one.mcfunction"), String.join("\n",
            "summon marker ~ ~ ~ {Tags:[\"aot_pk\"]}",
            "spreadplayers ~ ~ 0 12 under 250 false @e[type=marker,tag=aot_pk,limit=1,sort=nearest]",
            "execute as @e[type=marker,tag=aot_pk,limit=1] at @s if block ~ ~-1 ~ #aot_titans:ground run function aot_titans:pick",
            "kill @e[type=marker,tag=aot_pk]"));

        // Wave titans march towards the nearest player until they are close.
        write(fn.resolve("march.mcfunction"), String.join("\n",
            "schedule function aot_titans:march 5t replace",
            "execute as @e[type=#aot_titans:titans,tag=aot_wave] at @s run function aot_titans:nudge"));
        write(fn.resolve("nudge.mcfunction"), String.join("\n",
            "scoreboard players add @s aot_age 1",
            "execute if score @s aot_age matches 480.. run return run tag @s remove aot_wave",
            "execute unless entity @p[gamemode=!spectator,distance=..160] run return 0",
            "execute if entity @p[gamemode=!spectator,distance=..12] run return run tag @s remove aot_wave",
            "execute facing entity @p[gamemode=!spectator] feet rotated ~ 0 positioned ^ ^ ^0.6 run function aot_titans:step"));
        write(fn.resolve("step.mcfunction"), String.join("\n",
            "# Walk one small step, climbing up or down a block to follow the ground.",
            "execute unless block ~ ~ ~ #aot_titans:passable positioned ~ ~1 ~ if block ~ ~ ~ #aot_titans:passable run return run tp @s ~ ~ ~ facing entity @p[gamemode=!spectator] feet",
            "execute unless block ~ ~ ~ #aot_titans:passable run return 0",
            "execute if block ~ ~-1 ~ #aot_titans:passable positioned ~ ~-1 ~ run return run tp @s ~ ~ ~ facing entity @p[gamemode=!spectator] feet",
            "tp @s ~ ~ ~ facing entity @p[gamemode=!spectator] feet"));

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

        // ---- Road travellers ------------------------------------------------------------
        // A couple of people walking the roads near each player, moved step by step along the
        // road surface by the pack (no villager AI: no wandering off, no lag).
        if (travellers) {
            write(fn.resolve("traveller.mcfunction"), String.join("\n",
                "execute store result score #near aot_titans if entity @e[type=villager,tag=aot_walker,distance=..100]",
                "execute if score #near aot_titans matches 2.. run return 0",
                "execute store result score #roll aot_titans run random value 1..100",
                "execute if score #roll aot_titans matches 26.. run return 0",
                "summon marker ~ ~ ~ {Tags:[\"aot_wk\"]}",
                "spreadplayers ~ ~ 0 70 under 250 false @e[type=marker,tag=aot_wk,limit=1,sort=nearest]",
                "execute as @e[type=marker,tag=aot_wk] at @s if entity @a[distance=..35] run kill @s",
                "execute as @e[type=marker,tag=aot_wk] at @s unless block ~ ~-1 ~ #aot_titans:road run kill @s",
                "execute at @e[type=marker,tag=aot_wk,limit=1] run summon villager ~ ~ ~ {NoAI:1b,Silent:1b,Invulnerable:1b,"
                    + "Tags:[\"aot_walker\",\"aot_wk_new\"],VillagerData:{type:\"minecraft:plains\",profession:\"minecraft:none\",level:1}}",
                "execute as @e[type=villager,tag=aot_wk_new] store result entity @s Rotation[0] float 1 run random value 0..359",
                "tag @e[tag=aot_wk_new] remove aot_wk_new",
                "kill @e[type=marker,tag=aot_wk]"));
            write(fn.resolve("walk_tick.mcfunction"), String.join("\n",
                "schedule function aot_titans:walk_tick 2t replace",
                "execute as @e[type=villager,tag=aot_walker] at @s run function aot_titans:walk"));
            write(fn.resolve("walk.mcfunction"), String.join("\n",
                "# Leave when nobody is around, after about three minutes, or at night.",
                "scoreboard players add @s aot_age 1",
                "execute if score @s aot_age matches 1800.. run return run tp @s ~ -200 ~",
                "execute unless entity @a[distance=..120] run return run tp @s ~ -200 ~",
                "execute unless predicate aot_titans:daytime run return run tp @s ~ -200 ~",
                "# Step forward along the road, up or down a block with the ground.",
                "execute rotated ~ 0 positioned ^ ^ ^0.25 if block ~ ~-1 ~ #aot_titans:road if block ~ ~ ~ #aot_titans:passable if block ~ ~1 ~ #aot_titans:passable run return run tp @s ~ ~ ~",
                "execute rotated ~ 0 positioned ^ ^ ^0.25 positioned ~ ~1 ~ if block ~ ~-1 ~ #aot_titans:road if block ~ ~ ~ #aot_titans:passable if block ~ ~1 ~ #aot_titans:passable run return run tp @s ~ ~ ~",
                "execute rotated ~ 0 positioned ^ ^ ^0.25 positioned ~ ~-1 ~ if block ~ ~-1 ~ #aot_titans:road if block ~ ~ ~ #aot_titans:passable if block ~ ~1 ~ #aot_titans:passable run return run tp @s ~ ~ ~",
                "# The road bends: turn towards the side that still has road.",
                "execute rotated ~30 0 positioned ^ ^ ^1.5 if block ~ ~-1 ~ #aot_titans:road at @s run return run tp @s ~ ~ ~ ~10 ~",
                "execute rotated ~-30 0 positioned ^ ^ ^1.5 if block ~ ~-1 ~ #aot_titans:road at @s run return run tp @s ~ ~ ~ ~-10 ~",
                "execute rotated ~70 0 positioned ^ ^ ^1.5 if block ~ ~-1 ~ #aot_titans:road at @s run return run tp @s ~ ~ ~ ~20 ~",
                "execute rotated ~-70 0 positioned ^ ^ ^1.5 if block ~ ~-1 ~ #aot_titans:road at @s run return run tp @s ~ ~ ~ ~-20 ~",
                "# Dead end: turn around.",
                "tp @s ~ ~ ~ ~30 ~"));
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
            "# Spawns a pack near you right now, anywhere, to check the titan ids work.",
            "scoreboard players set #force aot_titans 1",
            "scoreboard players set #n aot_titans 3",
            "execute store result score #ok aot_titans run function aot_titans:try_group_near",
            "execute if score #ok aot_titans matches 0 store result score #ok aot_titans run function aot_titans:try_group_near",
            "execute if score #ok aot_titans matches 0 run function aot_titans:try_group_near",
            "tag @e[tag=aot_new] remove aot_new",
            "scoreboard players set #force aot_titans 0",
            "tellraw @s {\"text\":\"Spawned a test pack of titans 50-80 blocks away.\",\"color\":\"gold\"}"));
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
            "# Sends a wave at every player in the overworld, wherever they are.",
            "scoreboard players set #force aot_titans 1",
            "execute as @a[gamemode=!spectator] at @s if dimension minecraft:overworld run function aot_titans:wave",
            "scoreboard players set #force aot_titans 0"));
        write(fn.resolve("event/horde.mcfunction"), String.join("\n",
            "# Run as a player: /execute as <name> at @s run function aot_titans:event/horde",
            "scoreboard players set #force aot_titans 1",
            "scoreboard players set #n aot_titans 12",
            "execute store result score #ok aot_titans run function aot_titans:try_group_near",
            "execute if score #ok aot_titans matches 0 store result score #ok aot_titans run function aot_titans:try_group_near",
            "execute if score #ok aot_titans matches 0 run function aot_titans:try_group_near",
            "tag @e[tag=aot_new] add aot_wave",
            "tag @e[tag=aot_new] remove aot_new",
            "scoreboard players set #force aot_titans 0",
            "title @a[distance=..200] title {\"text\":\"A HORDE IS COMING\",\"color\":\"dark_red\",\"bold\":true}"));
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
            s.append(String.format(Locale.ROOT, "execute if score #pick aot_titans matches %d..%d run summon %s ~ ~ ~ {Tags:[\"aot_new\"]}%n", from, to, e.id));
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

    private static String read(Path fn, String name) throws IOException {
        return Files.readString(fn.resolve(name + ".mcfunction"), StandardCharsets.UTF_8);
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
