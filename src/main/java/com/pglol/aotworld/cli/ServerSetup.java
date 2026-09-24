package com.pglol.aotworld.cli;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Builds a ready-to-run Fabric dedicated server: downloads the Fabric server launcher,
 * copies the modpack's mods (client-only ones are skipped), copies the world, and writes
 * server.properties, eula.txt and start scripts.
 */
final class ServerSetup {
    private ServerSetup() {}

    private static final String MC = "1.21.1";
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();

    static void run(Path server, Path mods, Path world, String loader, String ram, boolean eula, Path toolDir)
            throws IOException, InterruptedException {
        Files.createDirectories(server);
        System.out.println("Creating server in " + server.toAbsolutePath());

        // 1. Fabric server launcher
        if (loader == null) loader = latest("https://meta.fabricmc.net/v2/versions/loader", "0.16.10");
        String installer = latest("https://meta.fabricmc.net/v2/versions/installer", "1.0.1");
        Path launcher = server.resolve("fabric-server-launch.jar");
        String url = "https://meta.fabricmc.net/v2/versions/loader/" + MC + "/" + loader + "/" + installer + "/server/jar";
        System.out.println("Downloading Fabric server (Minecraft " + MC + ", loader " + loader + ")...");
        try {
            HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            try (InputStream in = r.body()) {
                Files.copy(in, launcher, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.out.println("  Could not download the Fabric server (" + e.getMessage() + ").");
            System.out.println("  Download it yourself from https://fabricmc.net/use/server/ (Minecraft " + MC + ")");
            System.out.println("  and save it in the server folder as fabric-server-launch.jar");
        }

        // 2. Mods
        Path modsOut = server.resolve("mods");
        Files.createDirectories(modsOut);
        int copied = 0, skipped = 0;
        boolean hasRpg = false;
        if (mods != null) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "*.jar")) {
                for (Path jar : ds) {
                    String name = jar.getFileName().toString();
                    if (clientOnly(jar)) {
                        System.out.println("  skip (client only)  " + name);
                        skipped++;
                        continue;
                    }
                    Files.copy(jar, modsOut.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    if (name.startsWith("aot-rpg")) hasRpg = true;
                    copied++;
                }
            }
        }
        if (!hasRpg && toolDir != null && Files.isDirectory(toolDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(toolDir, "aot-rpg*.jar")) {
                for (Path jar : ds) {
                    Files.copy(jar, modsOut.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("  added " + jar.getFileName());
                    hasRpg = true;
                    copied++;
                }
            }
        }
        System.out.println("Mods: " + copied + " copied, " + skipped + " client-only skipped.");
        if (!hasRpg) System.out.println("  NOTE: aot-rpg mod jar not found. Put aot-rpg-*.jar in the server's mods folder.");

        // 3. World
        String level = "world";
        if (world != null) {
            if (!Files.exists(world.resolve("level.dat"))) throw new IllegalArgumentException(world + " is not a world folder");
            level = world.getFileName().toString().replaceAll("[^A-Za-z0-9_-]", "_");
            Path dest = server.resolve(level);
            System.out.println("Copying world to " + dest.getFileName() + " (this can take a few minutes)...");
            copyTree(world, dest);
        }

        // 4. Settings
        Path propsFile = server.resolve("server.properties");
        Properties props = new Properties();
        if (Files.exists(propsFile)) try (var in = Files.newBufferedReader(propsFile)) { props.load(in); }
        props.setProperty("level-name", level);
        props.putIfAbsent("motd", "§4§lAttack on Titan §r§7RPG");
        props.putIfAbsent("allow-flight", "true"); // ODM gear would otherwise get players kicked for flying
        props.putIfAbsent("spawn-protection", "0");
        props.putIfAbsent("difficulty", "normal");
        props.putIfAbsent("spawn-monsters", "true");
        props.putIfAbsent("enable-command-block", "true");
        props.putIfAbsent("view-distance", "10");
        props.putIfAbsent("simulation-distance", "8");
        props.putIfAbsent("max-players", "20");
        props.putIfAbsent("pvp", "true");
        props.putIfAbsent("sync-chunk-writes", "false");
        props.putIfAbsent("server-port", "25565");
        try (var out = Files.newBufferedWriter(propsFile)) { props.store(out, "Attack on Titan RPG server"); }
        Files.writeString(server.resolve("eula.txt"),
            "# https://aka.ms/MinecraftEULA\neula=" + eula + "\n", StandardCharsets.UTF_8);

        String java = "java -Xms2G -Xmx" + ram + " -XX:+UseG1GC -XX:+ParallelRefProcEnabled -jar fabric-server-launch.jar nogui";
        Files.writeString(server.resolve("start.bat"),
            "@echo off\r\ncd /d \"%~dp0\"\r\n" + java + "\r\npause\r\n", StandardCharsets.UTF_8);
        Path sh = server.resolve("start.sh");
        Files.writeString(sh, "#!/bin/sh\ncd \"$(dirname \"$0\")\"\n" + java + "\n", StandardCharsets.UTF_8);
        sh.toFile().setExecutable(true);

        System.out.println();
        System.out.println("Server ready: " + server.toAbsolutePath());
        System.out.println("  Start it with start.bat (Windows) or start.sh. It needs Java 21.");
        if (!eula) System.out.println("  Set eula=true in eula.txt first (you must accept the Minecraft EULA).");
        System.out.println("  Friends join with the same modpack at your IP, port " + props.getProperty("server-port") + ".");
        System.out.println("  Make yourself operator in the server console: op <yourname>");
        if (Runtime.version().feature() < 21)
            System.out.println("  WARNING: 'java' here is version " + Runtime.version().feature() + "; Minecraft 1.21.1 needs Java 21.");
    }

    /** True if the mod's fabric.mod.json says it only runs on the client. */
    private static boolean clientOnly(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("fabric.mod.json");
            if (e == null) return false;
            String json = new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"environment\"\\s*:\\s*\"(\\w+)\"").matcher(json);
            return m.find() && m.group(1).equals("client");
        } catch (IOException ex) {
            return false;
        }
    }

    /** First stable version from a Fabric meta listing, or the fallback if offline. */
    private static String latest(String url, String fallback) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
            Matcher m = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"stable\"\\s*:\\s*true").matcher(r.body());
            if (m.find()) return m.group(1);
            m = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"[^}]*\"stable\"\\s*:\\s*true").matcher(r.body());
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {
            // fall through
        }
        return fallback;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                if (!file.getFileName().toString().equals("session.lock"))
                    Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
