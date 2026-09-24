package com.pglol.aotworld.anvil;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/**
 * Writes level.dat for a 1.21.1 world. The overworld generator is a flat
 * ocean, so any chunk the tool did not write (open sea far from land) is
 * filled with matching ocean by the game.
 */
public final class LevelDat {
    private LevelDat() {}

    public static void write(Path dir, String name, long seed, int spawnX, int spawnY, int spawnZ,
                             double borderX, double borderZ, double borderSize) throws IOException {
        Nbt.ListTag layers = new Nbt.ListTag(Nbt.COMPOUND)
            .add(layer("minecraft:bedrock", 1))
            .add(layer("minecraft:stone", 92))
            .add(layer("minecraft:sand", 5))
            .add(layer("minecraft:water", 30));
        Nbt.Compound flat = new Nbt.Compound()
            .put("layers", layers)
            .putString("biome", "minecraft:ocean")
            .putByte("lakes", 0)
            .putByte("features", 0)
            .put("structure_overrides", new Nbt.ListTag(Nbt.STRING));

        Nbt.Compound dims = new Nbt.Compound()
            .put("minecraft:overworld", new Nbt.Compound()
                .putString("type", "minecraft:overworld")
                .put("generator", new Nbt.Compound().putString("type", "minecraft:flat").put("settings", flat)))
            .put("minecraft:the_nether", new Nbt.Compound()
                .putString("type", "minecraft:the_nether")
                .put("generator", new Nbt.Compound().putString("type", "minecraft:noise")
                    .putString("settings", "minecraft:nether")
                    .put("biome_source", new Nbt.Compound().putString("type", "minecraft:multi_noise")
                        .putString("preset", "minecraft:nether"))))
            .put("minecraft:the_end", new Nbt.Compound()
                .putString("type", "minecraft:the_end")
                .put("generator", new Nbt.Compound().putString("type", "minecraft:noise")
                    .putString("settings", "minecraft:end")
                    .put("biome_source", new Nbt.Compound().putString("type", "minecraft:the_end"))));

        Nbt.Compound data = new Nbt.Compound()
            .putInt("DataVersion", ChunkSerializer.DATA_VERSION)
            .put("Version", new Nbt.Compound().putInt("Id", ChunkSerializer.DATA_VERSION).putString("Name", "1.21.1")
                .putString("Series", "main").putByte("Snapshot", 0))
            .putInt("version", 19133)
            .putString("LevelName", name)
            .putInt("GameType", 0)
            .putByte("Difficulty", 2)
            .putByte("DifficultyLocked", 0)
            .putByte("hardcore", 0)
            .putByte("allowCommands", 1)
            .putByte("initialized", 1)
            .putByte("raining", 0)
            .putByte("thundering", 0)
            .putInt("rainTime", 120000)
            .putInt("thunderTime", 180000)
            .putInt("clearWeatherTime", 0)
            .putLong("Time", 0)
            .putLong("DayTime", 1000)
            .putLong("LastPlayed", System.currentTimeMillis())
            .putInt("SpawnX", spawnX)
            .putInt("SpawnY", spawnY)
            .putInt("SpawnZ", spawnZ)
            .putFloat("SpawnAngle", 0)
            .putDouble("BorderCenterX", borderX)
            .putDouble("BorderCenterZ", borderZ)
            .putDouble("BorderSize", borderSize)
            .putDouble("BorderSafeZone", 5)
            .putDouble("BorderDamagePerBlock", 0.2)
            .putDouble("BorderWarningBlocks", 5)
            .putDouble("BorderWarningTime", 15)
            .putDouble("BorderSizeLerpTarget", borderSize)
            .putLong("BorderSizeLerpTime", 0)
            .put("GameRules", new Nbt.Compound()
                .putString("doTraderSpawning", "false")
                .putString("doPatrolSpawning", "false")
                .putString("spawnRadius", "0"))
            .put("DataPacks", new Nbt.Compound()
                .put("Enabled", new Nbt.ListTag(Nbt.STRING).add("vanilla"))
                .put("Disabled", new Nbt.ListTag(Nbt.STRING)))
            .put("WorldGenSettings", new Nbt.Compound()
                .putLong("seed", seed)
                .putByte("generate_features", 0)
                .putByte("bonus_chest", 0)
                .put("dimensions", dims));

        Nbt.Compound root = new Nbt.Compound().put("Data", data);
        Files.createDirectories(dir);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(Files.newOutputStream(dir.resolve("level.dat")))))) {
            Nbt.writeRoot(out, root);
        }
    }

    private static Nbt.Compound layer(String block, int height) {
        return new Nbt.Compound().putString("block", block).putInt("height", height);
    }
}
