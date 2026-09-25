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

/** The world map image: sent once by the server, cached in config/aot_rpg/. */
public final class MapData {
    private MapData() {}

    public static final Identifier TEXTURE = Identifier.of("aot_rpg", "worldmap");
    public static Net.MapInfo info;
    public static boolean ready;
    public static int width, height;
    private static byte[][] parts;

    private static Path file(Net.MapInfo i) {
        return FabricLoader.getInstance().getConfigDir().resolve("aot_rpg").resolve("map-" + i.hash() + ".png");
    }

    public static void onInfo(Net.MapInfo i) {
        info = i;
        ready = false;
        parts = null;
        try {
            Path f = file(i);
            if (Files.exists(f) && Files.size(f) == i.size()) {
                load(Files.readAllBytes(f));
                return;
            }
        } catch (Exception e) {
            AotRpg.LOG.warn("Could not read cached map", e);
        }
        ClientPlayNetworking.send(new Net.MapRequest());
    }

    public static void onChunk(Net.MapChunk c) {
        if (info == null) return;
        if (parts == null || parts.length != c.total()) parts = new byte[c.total()][];
        parts[c.index()] = c.data();
        for (byte[] p : parts) if (p == null) return;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        parts = null;
        byte[] all = out.toByteArray();
        try {
            Path f = file(info);
            Files.createDirectories(f.getParent());
            Files.write(f, all);
        } catch (Exception e) {
            AotRpg.LOG.warn("Could not cache map", e);
        }
        load(all);
    }

    private static void load(byte[] bytes) {
        try {
            NativeImage img = NativeImage.read(new ByteArrayInputStream(bytes));
            width = img.getWidth();
            height = img.getHeight();
            NativeImageBackedTexture tex = new NativeImageBackedTexture(img);
            tex.setFilter(true, false);
            MinecraftClient.getInstance().getTextureManager().registerTexture(TEXTURE, tex);
            ready = true;
        } catch (Exception e) {
            AotRpg.LOG.error("Could not load the world map image", e);
        }
    }

    public static void reset() {
        info = null;
        ready = false;
        parts = null;
    }
}
