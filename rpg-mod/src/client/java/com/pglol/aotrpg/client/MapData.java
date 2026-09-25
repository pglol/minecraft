package com.pglol.aotrpg.client;

import com.pglol.aotrpg.AotRpg;
import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Map images (the world map and town plans): sent once by the server, cached in config/aot_rpg/. */
public final class MapData {
    private MapData() {}

    public static final class Image {
        public final Net.MapFile info;
        public final Identifier texture;
        public boolean ready;
        public int width, height;
        byte[][] parts;

        Image(Net.MapFile info) {
            this.info = info;
            this.texture = Identifier.of("aot_rpg", "map/" + info.name());
        }
    }

    private static final Map<String, Image> IMAGES = new LinkedHashMap<>();
    public static boolean received, outdated;

    public static Image world() {
        return IMAGES.get("world");
    }

    public static List<Image> tiles() {
        List<Image> l = new ArrayList<>();
        for (Image i : IMAGES.values()) if (!i.info.name().equals("world") && i.ready) l.add(i);
        return l;
    }

    private static Path file(Net.MapFile f) {
        return FabricLoader.getInstance().getConfigDir().resolve("aot_rpg").resolve("map-" + f.hash() + ".png");
    }

    public static void onFiles(Net.MapFiles files) {
        received = true;
        outdated = files.outdated();
        IMAGES.clear();
        for (Net.MapFile f : files.files()) {
            Image img = new Image(f);
            IMAGES.put(f.name(), img);
            try {
                Path p = file(f);
                if (Files.exists(p) && Files.size(p) == f.size()) {
                    load(img, Files.readAllBytes(p));
                    continue;
                }
            } catch (Exception e) {
                AotRpg.LOG.warn("Could not read cached map image", e);
            }
            ClientPlayNetworking.send(new Net.MapRequest(f.name()));
        }
    }

    public static void onChunk(Net.MapChunk c) {
        Image img = IMAGES.get(c.name());
        if (img == null) return;
        if (img.parts == null || img.parts.length != c.total()) img.parts = new byte[c.total()][];
        img.parts[c.index()] = c.data();
        for (byte[] p : img.parts) if (p == null) return;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : img.parts) out.writeBytes(p);
        img.parts = null;
        byte[] all = out.toByteArray();
        try {
            Path f = file(img.info);
            Files.createDirectories(f.getParent());
            Files.write(f, all);
        } catch (Exception e) {
            AotRpg.LOG.warn("Could not cache map image", e);
        }
        load(img, all);
    }

    private static void load(Image img, byte[] bytes) {
        try {
            NativeImage ni = NativeImage.read(new ByteArrayInputStream(bytes));
            img.width = ni.getWidth();
            img.height = ni.getHeight();
            NativeImageBackedTexture tex = new NativeImageBackedTexture(ni);
            tex.setFilter(true, false);
            MinecraftClient.getInstance().getTextureManager().registerTexture(img.texture, tex);
            img.ready = true;
        } catch (Exception e) {
            AotRpg.LOG.error("Could not load map image " + img.info.name(), e);
        }
    }

    public static void reset() {
        IMAGES.clear();
        received = false;
        outdated = false;
    }
}
