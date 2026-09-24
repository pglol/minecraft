# Attack on Titan: Paradis & Marley map generator

A standalone generator that writes a finished **Minecraft Java 1.21.1** world with
Paradis Island and the Marleyan coast. It's built as the base for a Wynncraft-style
open-world RPG. The output is a normal world folder that works in singleplayer and on
Fabric, Forge, NeoForge or vanilla servers. It's designed to sit alongside an AoT
modpack such as Danny's AOT, which supplies the titans and ODM gear.

![Overview](docs/previews/overview.png)

## Scale and travel times

The map is sized for travel on horseback. An average horse covers about 8 blocks/s
once hills and bends are counted.

| Trip | Distance | Time |
|---|---|---|
| Paradis, north tip to south tip | ~19,000 blocks | ~40 min on horseback |
| Paradis, east to west | ~12,500 blocks | ~25 min |
| Paradis Port → Marley Port City | ~4,500 blocks of sea | ~10 min by boat |
| Across Marley's coast | ~4,000 × 9,000 blocks | ~8–10 min |
| Wall Maria (radius 3,360) | Shiganshina → Trost ~700 blocks | ~1.5 min |

The Walls are 7 blocks per canon km (about 1:140) and keep their true 50-block height.
The land outside Wall Maria follows the traced reference map, squashed to hit the 40
minute target. Both are adjustable: see [Options](#options).

## What's in the world

- **The three Walls:** walkways, cannons, iron portcullis gates, scaffolding lifts
  (hold jump to ride up) and water gates for rivers.
- **12 districts:** Shiganshina, Trost, Karanes, Stohess, Ehrmich and the rest. Each
  is a walled half-circle town with streets, a plaza, markets and fenced town plots.
- **Mitras:** the palace and noble estates, plus the Underground City beneath it.
- **Landmarks:** the Forest of Giant Trees, Utgard Castle, Reiss Chapel and its
  crystal cavern, Survey Corps HQ, the cadet training camp, Ragako and Paradis Port
  (stone quay, piers, lighthouse).
- **About 100 villages of several types:**
  - farming villages with fields
  - hill villages terraced into slopes, with stepped gardens
  - forest cabin villages
  - fishing villages with docks
  - Marleyan brick hamlets
- **The Forest of Giant Trees:**
  - the Canopy Hideout: decks 30 blocks up the giant trees, joined by rope bridges,
    with cabins built around the trunks and a scaffolding lift up
  - the Hidden Grove: a secluded village in a clearing, reached by a faint path
- **Wilderness:** bigger hills outside the walls, stepped hillsides, snowy northern
  peaks, about 40 lakes, rivers, the Sand Barrens, and coasts that mix beaches,
  stony shores and sea cliffs.
- **Roads:** ring roads and gate roads as main highways with lamp posts, and paved
  roads in Marley. Winding trails link villages to each other and to the highways, and
  footpaths lead to plots and hideouts. Roads are graded into the slopes, bridge
  rivers and lakes, and enter walled places through their gates.
- **Marley:** the walled Liberio Internment Zone, Marley Port City with the military
  HQ and a quay, and the Marleyan Military Base (barracks, HQ, parade ground, depots,
  training field).

![Places](docs/previews/places.png)

### Missions: titan caves and Survey Corps camps

- **About 25 titan caves.** Each is a hillside cave mouth leading down to a great hall
  about 30 blocks tall, sized for titans, with a titan's bone ribcage and two side
  chambers. Loot chests use vanilla dungeon, mineshaft and pyramid loot. Every cave
  is its own named zone (e.g. *The Titan's Throat*) with a level range and a warp.
- **About 20 Survey Corps expedition camps:** a palisade, tents, a supply wagon with a
  loot chest, a horse corral, a watchtower with a lift, and a flag. Three camps ring
  the Forest of Giant Trees.
- **Smaller finds:** ruined watchtowers on hilltops, campsites, stone circles, hermit
  cabins and shipwrecks with treasure.

All of them are listed with coordinates and levels in `missions.csv` in the world
folder.

### Property plots for player housing

There are about **530 empty plots** across the map, each fenced and levelled, with a
gate facing its driveway and a numbered sign (`Property #123`). Sizes:

| Size | Interior | Where |
|---|---|---|
| Small | 14 × 14 | village edges, forests, meadows |
| Medium | 20 × 20 | villages, lakesides, coasts, forest clearings |
| Large | 28 × 28 | meadows, lakesides, mountainsides, hilltops |
| Estate | 36 × 36 | secluded hilltops (mansions) |
| Town | 23 × 23 | whole city blocks inside the districts and Mitras |
| Treehouse | 13 × 13 deck | the Canopy Hideout, 30 blocks up |

`plots.csv` and `plots.json` in the world folder list every plot: id, type, size,
buildable interior corners, floor height, gate side and zone. A housing plugin or mod
can paste a house schematic or instance straight onto a plot from that list.

## Generating the world

You need Java 17 or newer (the Java bundled with Minecraft 1.21 works, or get it from
https://adoptium.net).

1. Download `dist/aot-world.jar` and `dist/generate-world.bat` (or `.sh`) into one folder.
2. Double-click `generate-world.bat`, or run:
   ```
   java -Xmx4G -jar aot-world.jar generate AttackOnTitan
   ```
3. Copy the `AttackOnTitan` folder into your instance's `saves` folder. On a Fabric
   server, set `level-name=AttackOnTitan` instead.
4. You spawn in Shiganshina.

The full map is about 900k chunks, roughly **3.6 GB**. It takes about 15–20 minutes
on a 4-core CPU. Open sea far from land isn't written; the game fills it with
matching flat ocean.

To try a small piece first (a few seconds):

```
java -jar aot-world.jar generate TestWorld --place shiganshina-district --radius 800
```

### Options

| Option | Meaning |
|---|---|
| `--island-length 19000` | Paradis north-to-south length in blocks, about 40 min on horseback. Lower it for a quicker map. |
| `--scale 7` | Size of the Walls in blocks per canon km. 7 ≈ 1:140 (default), 20 = canon 1:50. |
| `--island-scale <f>` | Set the outer-island squash directly instead of `--island-length`. |
| `--place <id>` / `--radius <n>` | Generate only around a place (ids come from `places`). |
| `--area x0,z0,x1,z1` | Generate only a rectangle. |
| `--threads <n>` / `--seed <n>` | Worker threads / terrain seed. |

## Places and warps

`java -jar aot-world.jar places` lists every zone with its levels and coordinates:
districts, landmarks, each titan cave and camp, the Hidden Grove and the Canopy
Hideout. The world also includes a datapack:

- `/function aot:places` shows a clickable list of zones.
- `/function aot:warp/<id>` teleports you, e.g. `/function aot:warp/the-titan-s-throat`.

Level ranges are metadata for server design; nothing enforces them in-game. Titans
come from your AoT mod.

## Titans from your AoT mod

AoT mods such as Danny's AOT usually register their titans to spawn only in their own
dimension. This tool adds a datapack that spawns them in this map by zone:

- **Titans:** Wall Maria territory, the wilds outside the walls, and the Forest of
  Giant Trees.
- **Safe:** inside Wall Rose, every district town, Paradis Port, the Hidden Grove,
  the sea and Marley.
- **Daytime only**, with a per-player cap, a spawn chance and a spawn radius.

1. **Find the mod's titan IDs.** In the Modrinth App, open your instance, choose
   *⋮ → Open folder*, go into `mods`, then run:
   ```
   java -jar aot-world.jar scan-mod "path\to\mods\dannys-aot-2.4.3.jar"
   ```
   This lists every entity in the mod (marking the ones it spawns naturally in its own
   dimension, with their weights) and writes a starter `titans.txt`.
2. **Edit `titans.txt`.** Keep the titans you want and remove shifters and friendly
   NPCs. Each line is `<zone> <entity id> <weight>`. Zones:
   - `maria` for inside Wall Maria (e.g. smaller titans)
   - `wild` for outside the walls (e.g. abnormals)
   - `any` for both

   `interval`, `chance`, `cap` and `radius` set how often titans spawn, how likely a
   spawn is, the most titans near one player, and how far away they appear.
3. **Add it to your world** (no regeneration needed):
   ```
   java -jar aot-world.jar titans AttackOnTitan --config titans.txt
   ```
   You can also pass `--titans titans.txt` to `generate`. In-game, run `/reload` or
   restart. Toggle it with `/function aot_titans:off` and `/function aot_titans:on`.

The mod's own spawning stays as it is; this only adds spawns in the overworld.

## Previews without Minecraft

```
java -jar aot-world.jar preview overview map.png             # whole world
java -jar aot-world.jar preview detail trost.png 0 2660 400  # 400x400 blocks around (0, 2660)
```

## Customising

- **Geography:** `src/main/java/com/pglol/aotworld/core/Atlas.java` (districts,
  landmarks, level ranges, mountains, rivers, the traced Paradis outline).
- **Layout of everything scattered:** `core/AotWorld.java`, `Scatter.java` and
  `Villages.java` (villages, plots, caves, camps, lakes, trails).
- **Buildings:** `core/build/`.

Build with `mvn package`.

## How it works

The core is pure Java with no Minecraft dependency. When the tool starts, it lays the
whole map out in about half a second: geography, then roads, villages, trails, the
forest, lakes, points of interest, plots and driveways. It then records the levelled
ground each of them needs. After that, every block is a deterministic function of its
coordinates, so chunks are generated independently and in parallel. They're written
as Anvil region files (DataVersion 3955) together with `level.dat`, the datapack and
the plot and mission lists. Lighting is computed by the game on first load.
