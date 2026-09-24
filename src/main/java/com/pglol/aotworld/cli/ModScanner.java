package com.pglol.aotworld.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Looks inside a mod jar for its entities and for the spawn lists of the
 * biomes it adds (e.g. an AoT mod's Paradis dimension), and writes a starter
 * titans.txt for {@link TitanPack}.
 */
final class ModScanner {
    private ModScanner() {}

    private static final Pattern LANG = Pattern.compile("\"entity\\.([a-z0-9_.-]+)\\.([a-z0-9_./-]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern SPAWNER = Pattern.compile("\\{[^{}]*\"type\"\\s*:\\s*\"([a-z0-9_.-]+:[a-z0-9_./-]+)\"[^{}]*}");
    private static final Pattern WEIGHT = Pattern.compile("\"weight\"\\s*:\\s*(\\d+)");

    static void run(Path jar, Path out) throws IOException {
        Map<String, String> names = new TreeMap<>();
        Map<String, Integer> spawnWeights = new LinkedHashMap<>();
        List<String> biomes = new ArrayList<>(), dims = new ArrayList<>(), tags = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory()) continue;
                if (n.matches("assets/[^/]+/lang/en_us\\.json")) {
                    Matcher m = LANG.matcher(read(zip, e));
                    while (m.find()) {
                        if (!m.group(1).equals("minecraft")) names.put(m.group(1) + ":" + m.group(2), m.group(3));
                    }
                } else if (n.matches("data/[^/]+/worldgen/biome/.+\\.json")) {
                    biomes.add(n);
                    Matcher m = SPAWNER.matcher(read(zip, e));
                    while (m.find()) {
                        String id = m.group(1);
                        if (id.startsWith("minecraft:")) continue;
                        Matcher w = WEIGHT.matcher(m.group());
                        int weight = w.find() ? Integer.parseInt(w.group(1)) : 10;
                        spawnWeights.merge(id, weight, Math::max);
                    }
                } else if (n.matches("data/[^/]+/dimension/.+\\.json")) {
                    dims.add(n);
                } else if (n.matches("data/[^/]+/tags/worldgen/biome/.+\\.json")) {
                    tags.add(n);
                }
            }
        }

        System.out.println("Scanned " + jar.getFileName());
        System.out.println("  dimensions:  " + (dims.isEmpty() ? "none" : String.join(", ", dims)));
        System.out.println("  biomes:      " + biomes.size());
        System.out.println("  biome tags:  " + (tags.isEmpty() ? "none" : String.join(", ", tags)));
        System.out.println("  entities (" + names.size() + "):");
        for (Map.Entry<String, String> e : names.entrySet()) {
            String w = spawnWeights.containsKey(e.getKey()) ? "   <- spawns naturally, weight " + spawnWeights.get(e.getKey()) : "";
            System.out.printf(Locale.ROOT, "    %-45s %s%s%n", e.getKey(), e.getValue(), w);
        }
        for (String id : spawnWeights.keySet()) {
            if (!names.containsKey(id)) System.out.printf(Locale.ROOT, "    %-45s (no name)   <- spawns naturally, weight %d%n", id, spawnWeights.get(id));
        }

        // Starter config: natural spawns first, otherwise anything named like a titan.
        StringBuilder sb = new StringBuilder();
        sb.append("# Titan spawning for the Attack on Titan map. Generated from ").append(jar.getFileName()).append('\n');
        sb.append("# Lines: <zone> <entity id> <weight>   zone = maria (inside Wall Maria), wild (outside the walls), any\n");
        sb.append("# Delete or comment (#) anything that should not spawn, e.g. shifter or friendly NPC entities.\n");
        sb.append("interval 10     # seconds between spawn attempts per player\n");
        sb.append("chance 35       # percent chance per attempt\n");
        sb.append("cap 4           # max titans within 128 blocks of a player\n");
        sb.append("radius 70       # how far from the player titans appear\n\n");
        int added = 0;
        for (Map.Entry<String, Integer> e : spawnWeights.entrySet()) {
            sb.append("any ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
            added++;
        }
        for (Map.Entry<String, String> e : names.entrySet()) {
            if (spawnWeights.containsKey(e.getKey())) continue;
            String id = e.getKey().toLowerCase(Locale.ROOT), name = e.getValue().toLowerCase(Locale.ROOT);
            boolean titan = id.contains("titan") || name.contains("titan");
            boolean shifter = name.contains("attack titan") || name.contains("colossal") || name.contains("armored")
                || name.contains("female") || name.contains("beast") || name.contains("cart") || name.contains("jaw")
                || name.contains("war hammer") || name.contains("founding") || id.contains("shifter");
            if (titan && !shifter && added == 0) {
                sb.append("any ").append(e.getKey()).append(" 10\n");
            } else {
                sb.append("# any ").append(e.getKey()).append(" 10   # ").append(e.getValue()).append('\n');
            }
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println();
        System.out.println("Wrote " + out + " - check it, then run:  java -jar aot-world.jar titans <worldFolder> --config " + out.getFileName());
    }

    private static String read(ZipFile zip, ZipEntry e) throws IOException {
        try (InputStream in = zip.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
