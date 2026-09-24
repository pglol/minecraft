package com.pglol.aotworld.cli;

import com.pglol.aotworld.anvil.ChunkSerializer;
import com.pglol.aotworld.anvil.LevelDat;
import com.pglol.aotworld.anvil.RegionWriter;
import com.pglol.aotworld.core.AotWorld;
import com.pglol.aotworld.core.Atlas;
import com.pglol.aotworld.core.Blocks;
import com.pglol.aotworld.core.ChunkBuffer;
import com.pglol.aotworld.core.Region;
import com.pglol.aotworld.preview.MapPreview;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/** Command line entry point: generates a Minecraft 1.21.1 world folder or preview images. */
public final class Main {
    private static final String USAGE = String.join("\n",
        "Attack on Titan world generator (Minecraft Java 1.21.1)",
        "",
        "  generate <worldDir> [options]   write a playable world folder",
        "      --seed <n>                  terrain seed (default 1)",
        "      --scale <blocksPerKm>       size of the Walls: 7 (default, ~1:140), 20 = 1:50 canon",
        "      --island-length <blocks>    Paradis north-to-south length (default 19000, ~40 min by horse)",
        "      --island-scale <f>          set the outer-island squash directly instead of --island-length",
        "      --place <name>              only generate around a named place (see 'places')",
        "      --radius <blocks>           radius for --place (default 1000)",
        "      --area <x0,z0,x1,z1>        only generate this block rectangle",
        "      --threads <n>               worker threads (default: all cores)",
        "      --name <levelName>          world name shown in the menu",
        "      --titans <titans.txt>       also add titan spawning (see scan-mod / titans)",
        "  places [--scale n]              list named places, level ranges and coordinates",
        "  scan-mod <mod.jar> [--out titans.txt]   list a mod's entities and write a starter titan list",
        "  titans <worldDir> --config titans.txt  add/refresh titan spawning in an existing world",
        "  preview overview <out.png> [seed] [scale] [blocksPerPixel]",
        "  preview detail <out.png> <x> <z> <size> [seed] [scale]");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println(USAGE);
            return;
        }
        switch (args[0]) {
            case "generate": generate(args); break;
            case "places": places(args); break;
            case "scan-mod":
                ModScanner.run(Paths.get(args[1]), Paths.get(opt(args, "--out", "titans.txt")));
                break;
            case "titans": {
                Path world = Paths.get(args[1]);
                if (!Files.exists(world.resolve("level.dat"))) throw new IllegalArgumentException(world + " is not a world folder");
                TitanPack.write(world, world(args), Paths.get(opt(args, "--config", "titans.txt")));
                System.out.println("Titan spawning added to " + world + ". In game: /reload (or restart), then");
                System.out.println("  /function aot_titans:off   and   /function aot_titans:on   to toggle.");
                break;
            }
            case "preview": {
                String[] rest = new String[args.length - 1];
                System.arraycopy(args, 1, rest, 0, rest.length);
                MapPreview.main(rest);
                break;
            }
            default: System.out.println(USAGE);
        }
    }

    private static String opt(String[] args, String name, String def) {
        for (int i = 0; i + 1 < args.length; i++) if (args[i].equals(name)) return args[i + 1];
        return def;
    }

    static AotWorld world(String[] args) {
        long seed = Long.parseLong(opt(args, "--seed", "1"));
        double scale = Double.parseDouble(opt(args, "--scale", String.valueOf(AotWorld.DEFAULT_SCALE)));
        String is = opt(args, "--island-scale", null);
        double island = is != null ? Double.parseDouble(is)
            : Atlas.islandScaleForLength(scale, Double.parseDouble(opt(args, "--island-length", String.valueOf(AotWorld.DEFAULT_ISLAND_LENGTH))));
        return new AotWorld(seed, scale, island);
    }

    private static void places(String[] args) {
        AotWorld w = world(args);
        List<Region> regs = new ArrayList<>(w.atlas.regions());
        regs.sort((a, b) -> Integer.compare(a.minLevel, b.minLevel));
        for (Region r : regs) {
            System.out.printf(Locale.ROOT, "%-26s %-9s x=%-7d z=%-7d  %s%s%n", r.id(), r.levelText(), r.warpX, r.warpZ,
                r.name, r.titanLevel > 0 ? "  (titans ~Lv." + r.titanLevel + ")" : "");
        }
    }

    private static void generate(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println(USAGE);
            return;
        }
        Path dir = Paths.get(args[1]);
        long seed = Long.parseLong(opt(args, "--seed", "1"));
        int threads = Integer.parseInt(opt(args, "--threads", String.valueOf(Runtime.getRuntime().availableProcessors())));
        String name = opt(args, "--name", "Attack on Titan - Paradis & Marley");

        System.out.println("Building map layout (roads, villages, plots, caves)...");
        AotWorld w = world(args);
        System.out.printf(Locale.ROOT, "  %d villages, %d property plots, %d points of interest, %d lakes, %d roads%n",
            w.villages.size(), w.plots.size(), w.pois.size(), w.lakes.size(), w.roads.roads().size());
        Atlas a = w.atlas;
        int x0 = a.minX, z0 = a.minZ, x1 = a.maxX, z1 = a.maxZ;
        boolean partial = false;
        String place = opt(args, "--place", null);
        String area = opt(args, "--area", null);
        if (place != null) {
            Region r = w.atlas.region(place);
            if (r == null) throw new IllegalArgumentException("unknown place '" + place + "', run 'places' for the list");
            int rad = Integer.parseInt(opt(args, "--radius", "1000"));
            x0 = r.warpX - rad; x1 = r.warpX + rad; z0 = r.warpZ - rad; z1 = r.warpZ + rad;
            partial = true;
        } else if (area != null) {
            String[] p = area.split(",");
            x0 = Integer.parseInt(p[0].trim()); z0 = Integer.parseInt(p[1].trim());
            x1 = Integer.parseInt(p[2].trim()); z1 = Integer.parseInt(p[3].trim());
            partial = true;
        }
        int cx0 = Math.floorDiv(x0, 16), cz0 = Math.floorDiv(z0, 16), cx1 = Math.floorDiv(x1, 16), cz1 = Math.floorDiv(z1, 16);

        Path regionDir = dir.resolve("region");
        Path entityDir = dir.resolve("entities");
        Files.createDirectories(regionDir);
        Files.createDirectories(entityDir);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> jobs = new ArrayList<>();
        long total = (long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1);
        AtomicLong done = new AtomicLong(), written = new AtomicLong(), creatures = new AtomicLong();
        long start = System.currentTimeMillis();
        final int fcx0 = cx0, fcz0 = cz0, fcx1 = cx1, fcz1 = cz1;
        System.out.printf(Locale.ROOT, "Generating %d x %d chunks with %d threads into %s%n",
            cx1 - cx0 + 1, cz1 - cz0 + 1, threads, dir.toAbsolutePath());

        for (int rx = Math.floorDiv(cx0, 32); rx <= Math.floorDiv(cx1, 32); rx++) {
            for (int rz = Math.floorDiv(cz0, 32); rz <= Math.floorDiv(cz1, 32); rz++) {
                final int frx = rx, frz = rz;
                jobs.add(pool.submit(() -> {
                    ChunkBuffer buf = new ChunkBuffer();
                    ChunkSerializer ser = new ChunkSerializer();
                    RegionWriter out = null, ents = null;
                    try {
                        for (int lz = 0; lz < 32; lz++) {
                            for (int lx = 0; lx < 32; lx++) {
                                int cx = frx * 32 + lx, cz = frz * 32 + lz;
                                if (cx < fcx0 || cx > fcx1 || cz < fcz0 || cz > fcz1) continue;
                                done.incrementAndGet();
                                if (openSea(w, cx, cz)) continue;
                                w.composer.compose(cx, cz, buf);
                                byte[] data = ser.serialize(buf, cx, cz);
                                if (out == null) out = new RegionWriter(regionDir.resolve("r." + frx + "." + frz + ".mca"));
                                out.write(lz * 32 + lx, data);
                                byte[] mobs = ser.serializeEntities(buf, cx, cz);
                                if (mobs != null) {
                                    if (ents == null) ents = new RegionWriter(entityDir.resolve("r." + frx + "." + frz + ".mca"));
                                    ents.write(lz * 32 + lx, mobs);
                                    creatures.addAndGet(buf.mobs().size());
                                }
                                written.incrementAndGet();
                            }
                        }
                        if (out != null) out.close();
                        if (ents != null) ents.close();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                }));
            }
        }
        Thread progress = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(5000);
                    long d = done.get();
                    double secs = (System.currentTimeMillis() - start) / 1000.0;
                    double eta = d == 0 ? 0 : secs / d * (total - d);
                    System.out.printf(Locale.ROOT, "  %5.1f%%  %d/%d chunks (%d written)  elapsed %s  eta %s%n",
                        100.0 * d / total, d, total, written.get(), time(secs), time(eta));
                }
            } catch (InterruptedException ignored) {
            }
        });
        progress.setDaemon(true);
        progress.start();
        for (Future<?> f : jobs) f.get();
        pool.shutdown();
        progress.interrupt();

        int[] spawn = w.spawn();
        double bx, bz, size;
        if (partial) {
            bx = (x0 + x1) / 2.0; bz = (z0 + z1) / 2.0; size = Math.max(x1 - x0, z1 - z0);
            if (spawn[0] < x0 || spawn[0] > x1 || spawn[2] < z0 || spawn[2] > z1) {
                spawn = new int[] {(int) bx, 200, (int) bz};
            }
        } else {
            bx = (a.minX + a.maxX) / 2.0; bz = (a.minZ + a.maxZ) / 2.0; size = Math.max(a.maxX - a.minX, a.maxZ - a.minZ);
        }
        LevelDat.write(dir, name, seed, spawn[0], spawn[1], spawn[2], bx, bz, size);
        Datapack.write(dir, w);
        Registry.write(dir, w);
        String titans = opt(args, "--titans", null);
        if (titans != null) TitanPack.write(dir, w, Paths.get(titans));
        System.out.printf(Locale.ROOT, "Done in %s: %d chunks written, %d animals and townsfolk placed.%n",
            time((System.currentTimeMillis() - start) / 1000.0), written.get(), creatures.get());
    }

    /** Chunks far out to sea are left to the flat-ocean generator. */
    private static boolean openSea(AotWorld w, int cx, int cz) {
        int x = cx * 16 + 8, z = cz * 16 + 8;
        Atlas a = w.atlas;
        for (int dx = -24; dx <= 24; dx += 24) {
            for (int dz = -24; dz <= 24; dz += 24) {
                if (a.landSD(x + dx, z + dz) > -420) return false;
            }
        }
        return w.featuresIn(cx * 16, cz * 16, cx * 16 + 15, cz * 16 + 15).stream()
            .noneMatch(f -> f.occupies(x, z) || f.occupies(x - 8, z - 8) || f.occupies(x + 7, z + 7));
    }

    private static String time(double secs) {
        long s = (long) secs;
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}
