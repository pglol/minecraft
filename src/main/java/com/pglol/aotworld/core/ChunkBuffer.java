package com.pglol.aotworld.core;

import java.util.Arrays;

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
    private int x0, z0;

    public void reset(int chunkX, int chunkZ) {
        this.x0 = chunkX << 4;
        this.z0 = chunkZ << 4;
        Arrays.fill(data, (short) 0);
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
