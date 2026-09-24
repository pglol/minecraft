package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Loads and saves profiles in <world>/aot_rpg/players/<uuid>.json. */
public final class ProfileStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Map<UUID, Profile> cache = new ConcurrentHashMap<>();
    private Path dir;

    public void open(MinecraftServer server) {
        dir = server.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("players");
        cache.clear();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            AotRpg.LOG.error("Could not create profile folder {}", dir, e);
        }
    }

    public Profile get(UUID id) {
        return cache.computeIfAbsent(id, this::load);
    }

    private Profile load(UUID id) {
        Path f = dir.resolve(id + ".json");
        if (Files.exists(f)) {
            try {
                Profile p = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Profile.class);
                if (p != null) {
                    if (p.stats == null) p.stats = new java.util.EnumMap<>(Stat.class);
                    return p;
                }
            } catch (Exception e) {
                AotRpg.LOG.error("Could not read profile {}", f, e);
            }
        }
        return new Profile();
    }

    public void save(UUID id) {
        Profile p = cache.get(id);
        if (p == null || dir == null) return;
        try {
            Files.writeString(dir.resolve(id + ".json"), GSON.toJson(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AotRpg.LOG.error("Could not save profile {}", id, e);
        }
    }

    public void reset(UUID id) {
        cache.put(id, new Profile());
        save(id);
    }

    public void saveAll() {
        for (UUID id : cache.keySet()) save(id);
    }
}
