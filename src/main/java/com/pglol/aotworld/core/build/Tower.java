package com.pglol.aotworld.core.build;

import com.pglol.aotworld.core.Blocks;
import com.pglol.aotworld.core.ChunkBuffer;

/** Round tower with a conical roof, drawn per column. */
final class Tower {
    private Tower() {}

    static boolean covers(int x, int z, int cx, int cz, int radius) {
        double dx = x - cx, dz = z - cz;
        return dx * dx + dz * dz <= (radius + 1.5) * (radius + 1.5);
    }

    static void column(ChunkBuffer b, int x, int z, int cx, int cz, int radius, int baseY, int height,
                       int wall, int roof, int groundY) {
        double d = Math.hypot(x - cx, z - cz);
        if (d > radius + 1.5) return;
        int top = baseY + height;
        if (d <= radius + 0.5) {
            if (groundY < baseY) b.fill(x, groundY - 2, baseY - 1, z, wall);
            if (d > radius - 0.7) {
                b.fill(x, baseY, top, z, wall);
                int angleStep = (int) Math.floor((Math.atan2(z - cz, x - cx) + Math.PI) / (Math.PI / 4));
                for (int y = baseY + 6; y < top - 2; y += 6) {
                    if (angleStep % 2 == 0) b.set(x, y, z, Blocks.AIR);
                }
            } else {
                b.fill(x, baseY + 1, top, z, Blocks.AIR);
                for (int y = baseY; y < top; y += 6) b.set(x, y, z, Blocks.SPRUCE_PLANKS);
                if (x == cx && z == cz + radius - 1) {
                    b.fill(x, baseY + 1, top - 1, z, Blocks.id("ladder[facing=north]"));
                }
            }
            b.set(x, top, z, wall);
        }
        int cone = (int) Math.round((radius + 1.5 - d) * 1.6);
        b.fill(x, top + 1, top + cone, z, roof);
    }
}
