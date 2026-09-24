package com.pglol.aotworld.anvil;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/** Writes one Anvil region file (32 x 32 chunks). */
public final class RegionWriter implements AutoCloseable {
    private final RandomAccessFile file;
    private final int[] locations = new int[1024];
    private int nextSector = 2;

    public RegionWriter(Path path) throws IOException {
        file = new RandomAccessFile(path.toFile(), "rw");
        file.setLength(0);
        file.write(new byte[8192]);
    }

    /** @param local chunk index within the region: (z & 31) * 32 + (x & 31) */
    public void write(int local, byte[] zlibData) throws IOException {
        int length = zlibData.length + 1;
        int sectors = (length + 4 + 4095) / 4096;
        if (sectors > 255) throw new IOException("chunk too large: " + length + " bytes");
        long pos = (long) nextSector * 4096;
        file.seek(pos);
        file.writeInt(length);
        file.writeByte(2); // zlib
        file.write(zlibData);
        int pad = sectors * 4096 - (length + 4);
        if (pad > 0) file.write(new byte[pad]);
        locations[local] = (nextSector << 8) | sectors;
        nextSector += sectors;
    }

    @Override
    public void close() throws IOException {
        file.seek(0);
        for (int loc : locations) file.writeInt(loc);
        int now = (int) (System.currentTimeMillis() / 1000);
        for (int loc : locations) file.writeInt(loc == 0 ? 0 : now);
        file.close();
    }
}
