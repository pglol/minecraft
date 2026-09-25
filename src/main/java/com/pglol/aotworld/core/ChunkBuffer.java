package com.pglol.aotworld.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One chunk worth of block ids (16 x 384 x 16), column-major so each vertical
 * column is contiguous. All setters take world coordinates and silently clip to
 * the chunk, so structures can be written without caring about chunk borders.
 */
public final class ChunkBuffer {
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 319;
    public static final int HEIGHT = MAX_Y - MIN_Y + 1;

    private final short[] data = new short[16 * 16 * HEIGHT];
    /** Biome index (into {@link Terrain#BIOMES}) per 4x4 column, indexed qz * 4 + qx. */
    private final byte[] biomes = new byte[16];
    private final List<BlockEntity> entities = new ArrayList<>();
    private final List<Mob> mobs = new ArrayList<>();

    /** A creature placed at generation time (animals, horses, townsfolk). */
    public static final class Mob {
        public final double x, y, z;
        public final String id;
        public final long seed;

        Mob(double x, double y, double z, String id, long seed) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.id = id;
            this.seed = seed;
        }
    }
    private int x0, z0;

    public void reset(int chunkX, int chunkZ) {
        this.x0 = chunkX << 4;
        this.z0 = chunkZ << 4;
        Arrays.fill(data, (short) 0);
        entities.clear();
        mobs.clear();
    }

    /** Places a creature standing on the block below (x, y, z), if that column is in this chunk. */
    public void mob(int x, int y, int z, String id) {
        if (!contains(x, z)) return;
        mobs.add(new Mob(x + 0.5, y, z + 0.5, id.contains(":") ? id : "minecraft:" + id, Hash.of(x, y, z)));
    }

    public List<Mob> mobs() {
        return mobs;
    }

    /** A standing sign with up to four lines of text. */
    public void sign(int x, int y, int z, int rotation, String... lines) {
        if (!contains(x, z)) return;
        set(x, y, z, Blocks.id("oak_sign[rotation=" + (rotation & 15) + "]"));
        entities.add(new BlockEntity(x, y, z, "minecraft:sign", lines, null));
    }

    /**
     * Vanilla loot tables in chests are off: loot comes from the RPG systems (quests, bosses,
     * drops), so the world's chests start empty. Set to true for the old behaviour.
     */
    public static boolean vanillaLoot = false;

    /** A chest; filled from a vanilla loot table when first opened only if vanillaLoot is on. */
    public void lootChest(int x, int y, int z, String facing, String lootTable) {
        if (!contains(x, z)) return;
        set(x, y, z, Blocks.id("chest[facing=" + facing + "]"));
        entities.add(new BlockEntity(x, y, z, "minecraft:chest", null, vanillaLoot ? lootTable : null));
    }

    public List<BlockEntity> entities() {
        return entities;
    }

    public int x0() { return x0; }
    public int z0() { return z0; }

    public boolean contains(int x, int z) {
        return x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16;
    }

    private static int idx(int lx, int y, int lz) {
        return ((lx << 4) | lz) * HEIGHT + (y - MIN_Y);
    }

    public void set(int x, int y, int z, int id) {
        int lx = x - x0, lz = z - z0;
        if ((lx | lz) < 0 || lx > 15 || lz > 15 || y < MIN_Y || y > MAX_Y) return;
        data[idx(lx, y, lz)] = (short) id;
    }

    /** Only writes where the current block is air. */
    public void setIfAir(int x, int y, int z, int id) {
        int lx = x - x0, lz = z - z0;
        if ((lx | lz) < 0 || lx > 15 || lz > 15 || y < MIN_Y || y > MAX_Y) return;
        int i = idx(lx, y, lz);
        if (data[i] == 0) data[i] = (short) id;
    }

    public int get(int x, int y, int z) {
        int lx = x - x0, lz = z - z0;
        if ((lx | lz) < 0 || lx > 15 || lz > 15 || y < MIN_Y || y > MAX_Y) return Blocks.AIR;
        return data[idx(lx, y, lz)];
    }

    /** Fills y in [y1, y2] inclusive. */
    public void fill(int x, int y1, int y2, int z, int id) {
        int lx = x - x0, lz = z - z0;
        if ((lx | lz) < 0 || lx > 15 || lz > 15) return;
        int a = Math.max(y1, MIN_Y), b = Math.min(y2, MAX_Y);
        if (a > b) return;
        int base = ((lx << 4) | lz) * HEIGHT - MIN_Y;
        Arrays.fill(data, base + a, base + b + 1, (short) id);
    }

    /** Highest non-air y in a column, or MIN_Y - 1. */
    public int top(int x, int z) {
        int lx = x - x0, lz = z - z0;
        int base = ((lx << 4) | lz) * HEIGHT;
        for (int i = HEIGHT - 1; i >= 0; i--) if (data[base + i] != 0) return i + MIN_Y;
        return MIN_Y - 1;
    }

    /** Raw column access for adapters: local coordinates, returns the backing array offset. */
    public int columnOffset(int lx, int lz) {
        return ((lx << 4) | lz) * HEIGHT;
    }

    public byte[] biomes() {
        return biomes;
    }

    public short[] raw() {
        return data;
    }
}
