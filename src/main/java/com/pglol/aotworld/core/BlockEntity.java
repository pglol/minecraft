package com.pglol.aotworld.core;

/** Extra block-entity data written with a chunk: sign text or a loot table for a chest/barrel. */
public final class BlockEntity {
    public final int x, y, z;
    public final String id;
    public final String[] lines;
    public final String lootTable;

    public BlockEntity(int x, int y, int z, String id, String[] lines, String lootTable) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.id = id;
        this.lines = lines;
        this.lootTable = lootTable;
    }
}
