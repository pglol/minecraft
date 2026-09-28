package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Infusions.Infusion;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.Util;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * An infused blade's element, drawn as part of the blade itself: motes placed on the surface the
 * sword was just drawn with, in the same frame and space. They can't be left behind: running,
 * swinging, blocking, sheathed on a back, first person or third, they ride the steel.
 */
final class BladeMotes {
    private BladeMotes() {}

    static void draw(Infusion inf, int rarity, float[] v, int floats, boolean view, VertexConsumerProvider base) {
        int quads = floats / 12;
        if (quads < 1 || base == null) return;
        // Pick spots by surface area, so the long flat of the blade gets its share, not just a busy hilt.
        float[] cum = new float[quads];
        float total = 0, minX = 1e9f, minY = 1e9f, minZ = 1e9f, maxX = -1e9f, maxY = -1e9f, maxZ = -1e9f;
        for (int q = 0; q < quads; q++) {
            int o = q * 12;
            float ax = v[o + 3] - v[o], ay = v[o + 4] - v[o + 1], az = v[o + 5] - v[o + 2];
            float bx = v[o + 9] - v[o], by = v[o + 10] - v[o + 1], bz = v[o + 11] - v[o + 2];
            float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
            total += (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            cum[q] = total;
            for (int k = 0; k < 4; k++) {
                float x = v[o + k * 3], y = v[o + k * 3 + 1], z = v[o + k * 3 + 2];
                minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            }
        }
        if (total <= 0) return;
        float dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        float diag = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float ctrX = (minX + maxX) / 2, ctrY = (minY + maxY) / 2, ctrZ = (minZ + maxZ) / 2;
        int count = Math.round((rarity >= 5 ? 16 : rarity == 4 ? 11 : 7) * Math.max(0.3f, Math.min(1.3f, diag / 0.9f)));

        // Facing the viewer: hands in first person are drawn in view space, everything else in world axes.
        Quaternionf cam = MinecraftClient.getInstance().gameRenderer.getCamera().getRotation();
        Vector3f right, up, worldUp;
        if (view) {
            right = new Vector3f(1, 0, 0);
            up = new Vector3f(0, 1, 0);
            worldUp = new Quaternionf(cam).conjugate().transform(new Vector3f(0, 1, 0));
        } else {
            right = cam.transform(new Vector3f(1, 0, 0));
            up = cam.transform(new Vector3f(0, 1, 0));
            worldUp = new Vector3f(0, 1, 0);
        }
        long now = Util.getMeasuringTimeMs();
        int seed = quads * 31 + (view ? 7 : 0);
        VertexConsumer glow = null, dark = null;
        float s0 = Math.max(0.6f, Math.min(1.2f, diag / 0.9f));
        for (int i = 0; i < count; i++) {
            float life = life(inf) * (0.75f + 0.5f * hf(seed + i * 13));
            double ph = now / (double) life + hf(seed + i * 29);
            int cycle = (int) Math.floor(ph);
            float p = (float) (ph - cycle);
            int hs = seed * 977 + i * 7919 + cycle * 104729;
            // Storm arcs jump about: a new spot every few frames.
            if (inf == Infusion.STORM) hs += (int) (now / 70) * 31;
            // Where on the surface.
            float pick = hf(hs) * total;
            int lo = 0, hi = quads - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (cum[mid] < pick) lo = mid + 1;
                else hi = mid;
            }
            int o = lo * 12;
            float u = hf(hs + 1), w = hf(hs + 2);
            float x = bil(v, o, 0, u, w), y = bil(v, o, 1, u, w), z = bil(v, o, 2, u, w);
            // How it moves over its short life.
            float fade = (float) Math.sin(Math.PI * p), size = 0.022f * s0, travel = 0.16f * s0 * p;
            float mx = 0, my = 0, mz = 0;
            switch (inf) {
                case EMBER, RADIANT -> { mx = worldUp.x * travel; my = worldUp.y * travel; mz = worldUp.z * travel; }
                case ECLIPSE -> { mx = worldUp.x * travel * 0.8f; my = worldUp.y * travel * 0.8f; mz = worldUp.z * travel * 0.8f; size *= 1.5f + p; }
                case BLOOD, VENOM -> { float d = -travel * 0.9f * p; mx = worldUp.x * d; my = worldUp.y * d; mz = worldUp.z * d; }
                case FROST -> { float d = -travel * 0.3f; mx = worldUp.x * d; my = worldUp.y * d; mz = worldUp.z * d; }
                case VOID -> {
                    // Drawn in toward the steel from just off it.
                    float k = (1 - p) * 0.25f;
                    mx = (x - ctrX) * k; my = (y - ctrY) * k; mz = (z - ctrZ) * k;
                }
                case STORM -> { size *= 0.8f; fade = hf(hs + 5) < 0.3f ? 0 : 1; }
            }
            x += mx + (hf(hs + 3) - 0.5f) * 0.02f;
            y += my + (hf(hs + 4) - 0.5f) * 0.02f;
            z += mz + (hf(hs + 6) - 0.5f) * 0.02f;
            int[] c = color(inf, i, p);
            boolean bright = c[3] == 1;
            if (bright) {
                if (glow == null) glow = base.getBuffer(RenderLayer.getLightning());
                quad(glow, x, y, z, right, up, size, c, (int) (230 * fade));
            } else {
                if (dark == null) dark = base.getBuffer(RenderLayer.getDebugQuads());
                quad(dark, x, y, z, right, up, size, c, (int) (200 * fade));
            }
        }
    }

    private static float life(Infusion inf) {
        return switch (inf) {
            case STORM -> 350;
            case EMBER -> 700;
            case ECLIPSE -> 1300;
            case BLOOD, VENOM -> 1100;
            default -> 950;
        };
    }

    /** r, g, b, and 1 when it glows (added light) rather than covers. */
    private static int[] color(Infusion inf, int i, float p) {
        return switch (inf) {
            case FROST -> new int[] {175, 228, 255, 1};
            case EMBER -> new int[] {255, Math.round(200 - 130 * p), Math.round(80 - 60 * p), 1};
            case VOID -> i % 3 == 0 ? new int[] {150, 70, 255, 1} : new int[] {30, 0, 55, 0};
            case STORM -> new int[] {165, 232, 255, 1};
            case VENOM -> new int[] {95, 225, 70, 0};
            case RADIANT -> new int[] {255, 238, 185, 1};
            case BLOOD -> new int[] {150, 6, 12, 0};
            case ECLIPSE -> i % 5 == 0 ? new int[] {205, 22, 34, 1} : new int[] {8, 8, 10, 0};
        };
    }

    private static float bil(float[] v, int o, int a, float u, float w) {
        float p0 = v[o + a], p1 = v[o + 3 + a], p2 = v[o + 6 + a], p3 = v[o + 9 + a];
        float top = p0 + (p1 - p0) * u, bot = p3 + (p2 - p3) * u;
        return top + (bot - top) * w;
    }

    /** A little square facing the viewer, both sides (so culling never hides it). */
    private static void quad(VertexConsumer b, float x, float y, float z, Vector3f r, Vector3f u, float s, int[] c, int a) {
        if (a <= 2) return;
        float rx = r.x * s, ry = r.y * s, rz = r.z * s, ux = u.x * s, uy = u.y * s, uz = u.z * s;
        float[][] pts = {{x - rx - ux, y - ry - uy, z - rz - uz}, {x + rx - ux, y + ry - uy, z + rz - uz},
            {x + rx + ux, y + ry + uy, z + rz + uz}, {x - rx + ux, y - ry + uy, z - rz + uz}};
        for (int k = 0; k < 4; k++) b.vertex(pts[k][0], pts[k][1], pts[k][2]).color(c[0], c[1], c[2], a);
        for (int k = 3; k >= 0; k--) b.vertex(pts[k][0], pts[k][1], pts[k][2]).color(c[0], c[1], c[2], a);
    }

    private static float hf(int a) {
        a ^= a >>> 16;
        a *= 0x7feb352d;
        a ^= a >>> 15;
        a *= 0x846ca68b;
        a ^= a >>> 16;
        return (a >>> 8) / 16777216f;
    }
}
