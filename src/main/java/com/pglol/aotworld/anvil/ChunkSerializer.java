package com.pglol.aotworld.anvil;

import com.pglol.aotworld.core.Blocks;
import com.pglol.aotworld.core.ChunkBuffer;
import com.pglol.aotworld.core.Terrain;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Turns a {@link ChunkBuffer} into a Minecraft 1.21.1 chunk NBT (zlib compressed). */
public final class ChunkSerializer {
    public static final int DATA_VERSION = 3955; // Minecraft 1.21.1

    private static volatile Nbt.Compound[] paletteEntries = new Nbt.Compound[0];
    private static volatile String[] blockEntityIds = new String[0];

    private final int[] localIndex = new int[Short.MAX_VALUE];
    private final int[] paletteIds = new int[4096];
    private final int[] indices = new int[4096];

    public byte[] serialize(ChunkBuffer buf, int chunkX, int chunkZ) throws IOException {
        Nbt.Compound root = new Nbt.Compound();
        root.putInt("DataVersion", DATA_VERSION);
        root.putInt("xPos", chunkX);
        root.putInt("zPos", chunkZ);
        root.putInt("yPos", ChunkBuffer.MIN_Y >> 4);
        root.putString("Status", "minecraft:full");
        root.putLong("LastUpdate", 0);
        root.putLong("InhabitedTime", 0);
        root.putByte("isLightOn", 0);

        Nbt.ListTag sections = new Nbt.ListTag(Nbt.COMPOUND);
        Nbt.ListTag blockEntities = new Nbt.ListTag(Nbt.COMPOUND);
        short[] raw = buf.raw();
        Nbt.ListTag biomePalette = biomePalette(buf);
        long[] biomeData = biomeData(buf, biomePalette);

        for (int sy = ChunkBuffer.MIN_Y >> 4; sy <= ChunkBuffer.MAX_Y >> 4; sy++) {
            int count = 0;
            int y0 = sy << 4;
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int id = raw[buf.columnOffset(lx, lz) + (y0 + ly - ChunkBuffer.MIN_Y)];
                        int li = localIndex[id] - 1;
                        if (li < 0) {
                            li = count++;
                            localIndex[id] = li + 1;
                            paletteIds[li] = id;
                        }
                        indices[(ly * 16 + lz) * 16 + lx] = li;
                        String be = blockEntity(id);
                        if (be != null) {
                            blockEntities.add(new Nbt.Compound().putString("id", be)
                                .putInt("x", (chunkX << 4) + lx).putInt("y", y0 + ly).putInt("z", (chunkZ << 4) + lz)
                                .putByte("keepPacked", 0));
                        }
                    }
                }
            }
            Nbt.Compound states = new Nbt.Compound();
            Nbt.ListTag palette = new Nbt.ListTag(Nbt.COMPOUND);
            for (int i = 0; i < count; i++) palette.add(paletteEntry(paletteIds[i]));
            states.put("palette", palette);
            if (count > 1) {
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(count - 1));
                states.putLongArray("data", pack(indices, 4096, bits));
            }
            for (int i = 0; i < count; i++) localIndex[paletteIds[i]] = 0;

            Nbt.Compound biomes = new Nbt.Compound();
            biomes.put("palette", biomePalette);
            if (biomeData != null) biomes.putLongArray("data", biomeData);

            sections.add(new Nbt.Compound().putByte("Y", sy).put("block_states", states).put("biomes", biomes));
        }
        root.put("sections", sections);
        root.put("block_entities", blockEntities);
        root.put("block_ticks", new Nbt.ListTag(Nbt.COMPOUND));
        root.put("fluid_ticks", new Nbt.ListTag(Nbt.COMPOUND));
        root.put("structures", new Nbt.Compound().put("References", new Nbt.Compound()).put("starts", new Nbt.Compound()));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16384);
        Deflater deflater = new Deflater(6);
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes, deflater, 8192))) {
            Nbt.writeRoot(out, root);
        } finally {
            deflater.end();
        }
        return bytes.toByteArray();
    }

    private static Nbt.ListTag biomePalette(ChunkBuffer buf) {
        Nbt.ListTag list = new Nbt.ListTag(Nbt.STRING);
        byte[] b = buf.biomes();
        boolean[] seen = new boolean[Terrain.BIOMES.length];
        for (byte v : b) {
            if (!seen[v]) {
                seen[v] = true;
                list.add("minecraft:" + Terrain.BIOMES[v]);
            }
        }
        return list;
    }

    private static long[] biomeData(ChunkBuffer buf, Nbt.ListTag palette) {
        if (palette.size() <= 1) return null;
        int[] map = new int[Terrain.BIOMES.length];
        for (int i = 0; i < palette.size(); i++) {
            String name = ((String) palette.items.get(i)).substring(10);
            map[Arrays.asList(Terrain.BIOMES).indexOf(name)] = i;
        }
        byte[] b = buf.biomes();
        int[] idx = new int[64];
        for (int qy = 0; qy < 4; qy++) {
            for (int qz = 0; qz < 4; qz++) {
                for (int qx = 0; qx < 4; qx++) idx[(qy * 4 + qz) * 4 + qx] = map[b[qz * 4 + qx]];
            }
        }
        int bits = 32 - Integer.numberOfLeadingZeros(palette.size() - 1);
        return pack(idx, 64, bits);
    }

    static long[] pack(int[] values, int n, int bits) {
        int perLong = 64 / bits;
        long[] out = new long[(n + perLong - 1) / perLong];
        for (int i = 0; i < n; i++) {
            out[i / perLong] |= ((long) values[i]) << ((i % perLong) * bits);
        }
        return out;
    }

    private static Nbt.Compound paletteEntry(int id) {
        Nbt.Compound[] cache = paletteEntries;
        if (id < cache.length && cache[id] != null) return cache[id];
        synchronized (ChunkSerializer.class) {
            cache = paletteEntries;
            if (id >= cache.length) cache = Arrays.copyOf(cache, Blocks.size() + 64);
            String state = Blocks.state(id);
            Nbt.Compound c = new Nbt.Compound();
            int b = state.indexOf('[');
            c.putString("Name", b < 0 ? state : state.substring(0, b));
            if (b >= 0) {
                Nbt.Compound props = new Nbt.Compound();
                for (String kv : state.substring(b + 1, state.length() - 1).split(",")) {
                    int eq = kv.indexOf('=');
                    props.putString(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
                c.put("Properties", props);
            }
            cache[id] = c;
            paletteEntries = cache;
            return c;
        }
    }

    private static String blockEntity(int id) {
        if (id == 0) return null;
        String[] cache = blockEntityIds;
        if (id < cache.length && cache[id] != null) return cache[id].isEmpty() ? null : cache[id];
        synchronized (ChunkSerializer.class) {
            cache = blockEntityIds;
            if (id >= cache.length) cache = Arrays.copyOf(cache, Blocks.size() + 64);
            String name = Blocks.baseName(id);
            String be = "";
            if (name.equals("barrel") || name.equals("bell") || name.equals("campfire") || name.equals("chest")) {
                be = "minecraft:" + name;
            }
            cache[id] = be;
            blockEntityIds = cache;
            return be.isEmpty() ? null : be;
        }
    }
}
