package com.pglol.aotworld.anvil;

import com.pglol.aotworld.core.BlockEntity;
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
        java.util.Map<Long, BlockEntity> explicit = new java.util.HashMap<>();
        for (BlockEntity e : buf.entities()) {
            if (buf.get(e.x, e.y, e.z) == Blocks.AIR) continue;
            explicit.put(posKey(e.x, e.y, e.z), e);
            blockEntities.add(explicitTag(e));
        }
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
                        if (be != null && !explicit.containsKey(posKey((chunkX << 4) + lx, y0 + ly, (chunkZ << 4) + lz))) {
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

    private static final String[] CAT_VARIANTS = {"tabby", "black", "red", "siamese", "british_shorthair", "calico", "ragdoll", "white"};
    private static final String[] VILLAGER_TYPES = {"plains", "plains", "plains", "taiga"};

    /** Entity chunk NBT (entities/r.x.z.mca), or null when the chunk has no creatures. */
    public byte[] serializeEntities(ChunkBuffer buf, int chunkX, int chunkZ) throws IOException {
        if (buf.mobs().isEmpty()) return null;
        Nbt.ListTag list = new Nbt.ListTag(Nbt.COMPOUND);
        for (ChunkBuffer.Mob m : buf.mobs()) {
            long h = m.seed;
            boolean vendor = m.id.equals("aot:stable_master");
            Nbt.Compound c = new Nbt.Compound().putString("id", vendor ? "minecraft:villager" : m.id);
            c.put("Pos", new Nbt.ListTag(Nbt.DOUBLE).add(m.x).add(m.y).add(m.z));
            c.put("Motion", new Nbt.ListTag(Nbt.DOUBLE).add(0.0).add(0.0).add(0.0));
            c.put("Rotation", new Nbt.ListTag(Nbt.FLOAT).add((float) (com.pglol.aotworld.core.Hash.unit(h) * 360 - 180)).add(0f));
            long u1 = com.pglol.aotworld.core.Hash.mix(h + 11), u2 = com.pglol.aotworld.core.Hash.mix(h + 12);
            c.putIntArray("UUID", new int[] {(int) (u1 >>> 32), (int) u1, (int) (u2 >>> 32), (int) u2});
            c.putByte("OnGround", 1).putFloat("FallDistance", 0).putShort("Fire", -1).putShort("Air", 300);
            c.putByte("PersistenceRequired", 1);
            int r = (int) Math.floorMod(com.pglol.aotworld.core.Hash.mix(h + 13), 1000L);
            switch (m.id) {
                case "minecraft:horse":
                    c.putInt("Variant", (r % 7) + 256 * ((r / 7) % 5));
                    break;
                case "minecraft:sheep":
                    c.putByte("Color", r < 800 ? 0 : (r < 880 ? 7 : (r < 940 ? 15 : 12)));
                    break;
                case "minecraft:villager":
                    c.put("VillagerData", new Nbt.Compound().putString("type", "minecraft:" + VILLAGER_TYPES[r % VILLAGER_TYPES.length])
                        .putString("profession", "minecraft:none").putInt("level", 1));
                    break;
                case "aot:stable_master":
                    stableMaster(c);
                    break;
                case "minecraft:cat":
                    c.putString("variant", "minecraft:" + CAT_VARIANTS[r % CAT_VARIANTS.length]);
                    break;
                default:
                    break;
            }
            list.add(c);
        }
        Nbt.Compound root = new Nbt.Compound()
            .putInt("DataVersion", DATA_VERSION)
            .putIntArray("Position", new int[] {chunkX, chunkZ})
            .put("Entities", list);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
        Deflater deflater = new Deflater(6);
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes, deflater, 1024))) {
            Nbt.writeRoot(out, root);
        } finally {
            deflater.end();
        }
        return bytes.toByteArray();
    }

    /** A horse vendor: stays put, can't be hurt, and sells horses by quality tier. */
    private static void stableMaster(Nbt.Compound c) {
        c.put("VillagerData", new Nbt.Compound().putString("type", "minecraft:plains")
            .putString("profession", "minecraft:leatherworker").putInt("level", 5));
        c.putInt("Xp", 250);
        c.putByte("NoAI", 1).putByte("Invulnerable", 1).putByte("CustomNameVisible", 1);
        c.putString("CustomName", "{\"text\":\"Stable Master\",\"color\":\"gold\"}");
        Nbt.ListTag offers = new Nbt.ListTag(Nbt.COMPOUND);
        offers.add(offer(10, 0, horseEgg("Common Horse", "horse", 0.20, 0.60, 20)));
        offers.add(offer(24, 0, horseEgg("Swift Courser", "horse", 0.30, 0.80, 26)));
        offers.add(offer(48, 1, horseEgg("Survey Corps Warhorse", "horse", 0.3375, 1.0, 30)));
        offers.add(offer(12, 0, horseEgg("Pack Donkey", "donkey", 0.175, 0.5, 22)));
        offers.add(offer(6, 0, item("minecraft:saddle", 1)));
        offers.add(offer(2, 0, item("minecraft:lead", 2)));
        offers.add(offer(1, 0, item("minecraft:hay_block", 4)));
        offers.add(offer(8, 0, item("minecraft:iron_horse_armor", 1)));
        offers.add(offer(14, 0, item("minecraft:golden_horse_armor", 1)));
        offers.add(offer(32, 0, item("minecraft:diamond_horse_armor", 1)));
        c.put("Offers", new Nbt.Compound().put("Recipes", offers));
    }

    private static Nbt.Compound item(String id, int count) {
        return new Nbt.Compound().putString("id", id).putInt("count", count);
    }

    private static Nbt.Compound offer(int emeralds, int diamonds, Nbt.Compound sell) {
        Nbt.Compound o = new Nbt.Compound().put("buy", new Nbt.Compound().putString("id", "minecraft:emerald").putInt("count", emeralds));
        if (diamonds > 0) o.put("buyB", new Nbt.Compound().putString("id", "minecraft:diamond").putInt("count", diamonds));
        return o.put("sell", sell).putInt("maxUses", 99999).putInt("uses", 0).putInt("xp", 0).putByte("rewardExp", 0)
            .putFloat("priceMultiplier", 0).putInt("specialPrice", 0).putInt("demand", 0);
    }

    /** A spawn egg for a tamed horse or donkey with fixed stats. */
    private static Nbt.Compound horseEgg(String name, String type, double speed, double jump, double health) {
        Nbt.ListTag attrs = new Nbt.ListTag(Nbt.COMPOUND)
            .add(new Nbt.Compound().putString("id", "minecraft:generic.movement_speed").putDouble("base", speed))
            .add(new Nbt.Compound().putString("id", "minecraft:generic.jump_strength").putDouble("base", jump))
            .add(new Nbt.Compound().putString("id", "minecraft:generic.max_health").putDouble("base", health));
        Nbt.Compound data = new Nbt.Compound().putString("id", "minecraft:" + type).putByte("Tame", 1)
            .put("Attributes", attrs).putFloat("Health", (float) health)
            .putString("CustomName", "\"" + name + "\"");
        Nbt.Compound comps = new Nbt.Compound()
            .put("minecraft:entity_data", data)
            .putString("minecraft:item_name", "{\"text\":\"" + name + "\",\"italic\":false}");
        return new Nbt.Compound().putString("id", "minecraft:" + type + "_spawn_egg").putInt("count", 1).put("components", comps);
    }

    private static long posKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static Nbt.Compound explicitTag(BlockEntity e) {
        Nbt.Compound c = new Nbt.Compound().putString("id", e.id).putInt("x", e.x).putInt("y", e.y).putInt("z", e.z)
            .putByte("keepPacked", 0);
        if (e.lines != null) {
            Nbt.ListTag front = new Nbt.ListTag(Nbt.STRING), back = new Nbt.ListTag(Nbt.STRING);
            for (int i = 0; i < 4; i++) {
                String line = i < e.lines.length ? e.lines[i] : "";
                front.add("\"" + line.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
                back.add("\"\"");
            }
            c.put("front_text", new Nbt.Compound().put("messages", front).putString("color", "black").putByte("has_glowing_text", 0));
            c.put("back_text", new Nbt.Compound().put("messages", back).putString("color", "black").putByte("has_glowing_text", 0));
            c.putByte("is_waxed", 1);
        }
        if (e.lootTable != null) {
            c.putString("LootTable", e.lootTable);
            c.putLong("LootTableSeed", com.pglol.aotworld.core.Hash.of(e.x, e.y, e.z));
        }
        return c;
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
            } else if (name.equals("furnace") || name.equals("smoker") || name.equals("lectern")) {
                be = "minecraft:" + name;
            } else if (name.endsWith("_bed")) {
                be = "minecraft:bed";
            }
            cache[id] = be;
            blockEntityIds = cache;
            return be.isEmpty() ? null : be;
        }
    }
}
