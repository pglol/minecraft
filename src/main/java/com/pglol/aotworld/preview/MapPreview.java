package com.pglol.aotworld.preview;

import com.pglol.aotworld.core.AotWorld;
import com.pglol.aotworld.core.Atlas;
import com.pglol.aotworld.core.Blocks;
import com.pglol.aotworld.core.ChunkBuffer;
import com.pglol.aotworld.core.Column;
import com.pglol.aotworld.core.Hash;
import com.pglol.aotworld.core.Region;
import com.pglol.aotworld.core.Terrain;
import com.pglol.aotworld.core.WorldSpec;
import com.pglol.aotworld.core.build.Village;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.stream.IntStream;

/**
 * Renders top-down PNG previews without Minecraft.
 *
 * <pre>
 *   java -cp aot-world.jar com.pglol.aotworld.preview.MapPreview overview out.png [seed] [blocksPerKm] [blocksPerPixel]
 *   java -cp aot-world.jar com.pglol.aotworld.preview.MapPreview detail out.png centerX centerZ size [seed] [blocksPerKm]
 * </pre>
 */
public final class MapPreview {
    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        if (args.length < 2) {
            System.err.println("usage: overview <out.png> [seed] [bpk] [bpp] | detail <out.png> <x> <z> <size> [seed] [bpk]");
            System.exit(1);
        }
        if (args[0].equals("overview")) {
            long seed = args.length > 2 ? Long.parseLong(args[2]) : 1L;
            double bpk = args.length > 3 ? Double.parseDouble(args[3]) : AotWorld.DEFAULT_SCALE;
            int bpp = args.length > 4 ? Integer.parseInt(args[4]) : 8;
            AotWorld w = world(seed, bpk);
            ImageIO.write(overview(w, bpp), "png", new File(args[1]));
        } else {
            int cx = Integer.parseInt(args[2]), cz = Integer.parseInt(args[3]), size = Integer.parseInt(args[4]);
            long seed = args.length > 5 ? Long.parseLong(args[5]) : 1L;
            double bpk = args.length > 6 ? Double.parseDouble(args[6]) : AotWorld.DEFAULT_SCALE;
            AotWorld w = world(seed, bpk);
            ImageIO.write(detail(w, cx, cz, size), "png", new File(args[1]));
        }
    }

    private static AotWorld world(long seed, double bpk) {
        return new AotWorld(seed, bpk, Atlas.islandScaleForLength(bpk, AotWorld.DEFAULT_ISLAND_LENGTH));
    }

    // ---- Overview ------------------------------------------------------------------------

    public static BufferedImage overview(AotWorld w, int bpp) {
        return overview(w, bpp, false);
    }

    /** clean = the in-game map: terrain, water, roads, towns and walls only (the game draws its own labels). */
    public static BufferedImage overview(AotWorld w, int bpp, boolean clean) {
        Atlas a = w.atlas;
        int width = (a.maxX - a.minX) / bpp, height = (a.maxZ - a.minZ) / bpp;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] hts = new int[width * height];
        int[] rgb = new int[width * height];
        IntStream.range(0, height).parallel().forEach(py -> {
            Column c = new Column();
            for (int px = 0; px < width; px++) {
                int x = a.minX + px * bpp, z = a.minZ + py * bpp;
                w.terrain.sample(x, z, c);
                hts[py * width + px] = c.height;
                rgb[py * width + px] = terrainColor(w, c);
            }
        });
        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                int i = py * width + px;
                int c = rgb[i];
                if (px > 0 && py > 0 && hts[i] > WorldSpec.SEA) {
                    int d = hts[i] - hts[i - width - 1];
                    c = shade(c, 1 + Math.max(-0.35, Math.min(0.35, d * 0.06)));
                }
                img.setRGB(px, py, c);
            }
        }

        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        double s = 1.0 / bpp;
        java.util.function.DoubleUnaryOperator X = x -> (x - a.minX) * s, Z = z -> (z - a.minZ) * s;

        // Rivers and roads.
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(0x3f76e4));
        for (com.pglol.aotworld.core.River r : a.rivers) {
            for (int i = 0; i + 1 < r.xs.length; i++) {
                g.drawLine((int) X.applyAsDouble(r.xs[i]), (int) Z.applyAsDouble(r.zs[i]),
                    (int) X.applyAsDouble(r.xs[i + 1]), (int) Z.applyAsDouble(r.zs[i + 1]));
            }
        }
        for (com.pglol.aotworld.core.Road r : w.roads.roads()) {
            switch (r.type) {
                case MAIN: g.setStroke(new BasicStroke(2f)); g.setColor(new Color(0xF2E3B3)); break;
                case PAVED: g.setStroke(new BasicStroke(2f)); g.setColor(new Color(0xD0D0D0)); break;
                case TRAIL: g.setStroke(new BasicStroke(1.2f)); g.setColor(new Color(0xC9A56B)); break;
                default: g.setStroke(new BasicStroke(0.8f)); g.setColor(new Color(0x9C7A4A)); break;
            }
            for (int i = 0; i + 1 < r.xs.length; i++) {
                if (a.landSD(r.xs[i], r.zs[i]) < 0) continue;
                g.drawLine((int) X.applyAsDouble(r.xs[i]), (int) Z.applyAsDouble(r.zs[i]),
                    (int) X.applyAsDouble(r.xs[i + 1]), (int) Z.applyAsDouble(r.zs[i + 1]));
            }
        }

        // Towns.
        g.setColor(new Color(0xB5651D));
        for (Atlas.District d : a.districts) {
            double r = d.radius * s;
            g.fillOval((int) (X.applyAsDouble(d.cx) - r), (int) (Z.applyAsDouble(d.cz) - r), (int) (2 * r), (int) (2 * r));
        }
        double cr = a.capitalRadius * s;
        g.setColor(new Color(0xC9A13B));
        g.fillOval((int) (X.applyAsDouble(0) - cr), (int) (Z.applyAsDouble(0) - cr), (int) (2 * cr), (int) (2 * cr));
        for (Atlas.Site site : a.sites) {
            if (site.kind == Atlas.Kind.GIANT_FOREST) continue;
            double r = Math.max(3, site.radius * s * 0.8);
            g.setColor(site.kind == Atlas.Kind.LIBERIO || site.kind == Atlas.Kind.MARLEY_PORT
                || site.kind == Atlas.Kind.MILITARY_BASE ? new Color(0x8E3B2E) : new Color(0xB5651D));
            g.fillOval((int) (X.applyAsDouble(site.x) - r), (int) (Z.applyAsDouble(site.z) - r), (int) (2 * r), (int) (2 * r));
        }
        for (Village v : w.villages) {
            g.setColor(new Color(0x8B5A2B));
            int r = Math.max(3, (int) (v.radius * s));
            g.fillOval((int) X.applyAsDouble(v.cx) - r, (int) Z.applyAsDouble(v.cz) - r, 2 * r, 2 * r);
        }
        for (com.pglol.aotworld.core.build.Plot p : clean ? java.util.List.<com.pglol.aotworld.core.build.Plot>of() : w.plots) {
            g.setColor(new Color(0xE040C0));
            int px = (int) X.applyAsDouble(p.cx()), pz = (int) Z.applyAsDouble(p.cz());
            g.fillRect(px - 1, pz - 1, 3, 3);
        }
        for (com.pglol.aotworld.core.build.Poi p : clean ? java.util.List.<com.pglol.aotworld.core.build.Poi>of() : w.pois) {
            switch (p.kind) {
                case TITAN_CAVE: g.setColor(new Color(0xB00000)); break;
                case EXPEDITION_CAMP: g.setColor(new Color(0x1E7A2E)); break;
                default: g.setColor(new Color(0x3050A0)); break;
            }
            int px = (int) X.applyAsDouble(p.x), pz = (int) Z.applyAsDouble(p.z);
            int r = p.kind == com.pglol.aotworld.core.build.Poi.Kind.TITAN_CAVE || p.kind == com.pglol.aotworld.core.build.Poi.Kind.EXPEDITION_CAMP ? 4 : 2;
            g.fillPolygon(new int[] {px, px - r, px + r}, new int[] {pz - r, pz + r, pz + r}, 3);
        }

        // Walls.
        g.setStroke(new BasicStroke(3f));
        g.setColor(new Color(0x2B2B2B));
        for (Atlas.Wall wall : a.walls) {
            double r = wall.radius * s;
            g.drawOval((int) (X.applyAsDouble(0) - r), (int) (Z.applyAsDouble(0) - r), (int) (2 * r), (int) (2 * r));
            for (Atlas.District d : wall.districts) {
                double dr = d.radius * s;
                double startDeg = -Math.toDegrees(Math.atan2(d.uz, d.ux)) - 90;
                g.drawArc((int) (X.applyAsDouble(d.cx) - dr), (int) (Z.applyAsDouble(d.cz) - dr), (int) (2 * dr), (int) (2 * dr),
                    (int) startDeg, 180);
            }
        }

        if (clean) {
            g.dispose();
            parchment(img);
            return img;
        }
        // Labels.
        Font big = new Font(Font.SANS_SERIF, Font.BOLD, 22), mid = new Font(Font.SANS_SERIF, Font.BOLD, 14),
            small = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        for (Atlas.District d : a.districts) {
            String name = d.name.replace(" District", "");
            label(g, name, X.applyAsDouble(d.cx + d.ux * (d.radius + 260)), Z.applyAsDouble(d.cz + d.uz * (d.radius + 260)), mid);
        }
        label(g, "Mitras", X.applyAsDouble(0), Z.applyAsDouble(0) - cr - 10, mid);
        for (Atlas.Site site : a.sites) {
            double off = site.kind == Atlas.Kind.GIANT_FOREST ? 0 : Math.max(10, site.radius * s) + 10;
            label(g, site.name, X.applyAsDouble(site.x), Z.applyAsDouble(site.z) - off, small);
        }
        for (Atlas.Wall wall : a.walls) {
            label(g, wall.name, X.applyAsDouble(wall.radius * Math.cos(Math.toRadians(225))) + 30,
                Z.applyAsDouble(wall.radius * Math.sin(Math.toRadians(225))) + 10, mid);
        }
        label(g, "PARADIS ISLAND", X.applyAsDouble(0), Z.applyAsDouble(-a.maria.radius - 2500), big);
        label(g, "MARLEY", X.applyAsDouble(a.site(Atlas.Kind.MILITARY_BASE).x - 900), Z.applyAsDouble(a.site(Atlas.Kind.MILITARY_BASE).z + 2600), big);
        label(g, "THE SEA", X.applyAsDouble((a.marleyCoastX(a.marleyCentreZ()) + a.site(Atlas.Kind.PARADIS_PORT).x) / 2), Z.applyAsDouble(a.marleyCentreZ() - 4000), big);

        // Legend.
        g.setFont(small);
        int ly = 30;
        String[][] legend = {{"B00000", "Titan cave"}, {"1E7A2E", "Survey Corps camp"}, {"3050A0", "Point of interest"},
            {"E040C0", "Property plot"}, {"8B5A2B", "Village"}};
        g.setColor(new Color(255, 255, 255, 220));
        g.fillRect(20, 14, 170, 18 * legend.length + 12);
        for (String[] l : legend) {
            g.setColor(new Color(Integer.parseInt(l[0], 16)));
            g.fillRect(30, ly - 9, 10, 10);
            g.setColor(Color.BLACK);
            g.drawString(l[1], 48, ly);
            ly += 18;
        }

        // Scale bar.
        int barBlocks = 2000;
        int bx = 30, by = height - 40;
        g.setColor(Color.WHITE);
        g.fillRect(bx - 6, by - 26, (int) (barBlocks * s) + 12, 40);
        g.setColor(Color.BLACK);
        g.fillRect(bx, by, (int) (barBlocks * s), 6);
        g.setFont(small);
        g.drawString(barBlocks + " blocks, about 4 min on horseback  (1 px = " + bpp + " blocks)", bx, by - 8);
        g.dispose();
        return img;
    }

    private static void label(Graphics2D g, String text, double x, double y, Font f) {
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int tx = (int) (x - fm.stringWidth(text) / 2.0), ty = (int) y;
        g.setColor(new Color(255, 255, 255, 200));
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) g.drawString(text, tx + dx, ty + dy);
        g.setColor(Color.BLACK);
        g.drawString(text, tx, ty);
    }

    private static int terrainColor(AotWorld w, Column c) {
        if (c.underwater()) {
            if (c.river || c.lake) return 0x3f76e4;
            int depth = c.water - c.height;
            double t = Math.min(1, depth / 30.0);
            return mix(0x4f8fe8, 0x1c3a8a, t);
        }
        if (c.road >= 0) return 0xb29663;
        if (c.surface == Blocks.SAND) return 0xdbd3a0;
        if (c.surface == Blocks.SNOW_BLOCK) return 0xf4f8fb;
        if (c.height > 135) return mix(0x7f7f7f, 0xb0b0b0, Math.min(1, (c.height - 135) / 40.0));
        int base;
        switch (c.biome) {
            case Terrain.B_FOREST: base = 0x3f7d2a; break;
            case Terrain.B_BIRCH: base = 0x5d9a3c; break;
            case Terrain.B_DARK_FOREST: base = 0x2f5a1f; break;
            case Terrain.B_TAIGA: base = 0x3d6040; break;
            case Terrain.B_MEADOW: base = 0x7eb05a; break;
            case Terrain.B_HILLS: base = 0x7a8a6a; break;
            case Terrain.B_DESERT: base = 0xd8c88a; break;
            default: base = 0x7cb35a;
        }
        Atlas.Site gf = w.atlas.site(Atlas.Kind.GIANT_FOREST);
        if (gf != null && Math.hypot(c.x - gf.x, c.z - gf.z) < gf.radius) base = 0x1f4a14;
        return base;
    }

    // ---- Detail --------------------------------------------------------------------------

    // ---- In-game world map: an old parchment chart ----------------------------------------

    private static final int K_SEA = 0, K_LAND = 1, K_FOREST = 2, K_MOUNT = 3, K_SAND = 4, K_WATER = 5, K_SNOW = 6;

    /** Pads the chart west of the generated world so Marley fades into "uncharted" land. */
    public static final int GAME_MAP_PAD = 1800;

    public static int gameMapBpp(AotWorld w) {
        return Math.max(4, (w.atlas.maxX - (w.atlas.minX - GAME_MAP_PAD)) / 2048);
    }

    /** The in-game world map. The top-left pixel is world (minX - GAME_MAP_PAD, minZ). */
    public static BufferedImage gameMap(AotWorld w) {
        Atlas a = w.atlas;
        int bpp = gameMapBpp(w);
        int x0 = a.minX - GAME_MAP_PAD, z0 = a.minZ;
        int width = (a.maxX - x0) / bpp, height = (a.maxZ - z0) / bpp;
        int n = width * height;
        int[] hts = new int[n];
        float[] sd = new float[n];
        byte[] kind = new byte[n];
        boolean[] outside = new boolean[n];
        Atlas.Site gf = a.site(Atlas.Kind.GIANT_FOREST);
        IntStream.range(0, height).parallel().forEach(py -> {
            Column c = new Column();
            for (int px = 0; px < width; px++) {
                int i = py * width + px;
                int x = x0 + px * bpp, z = z0 + py * bpp;
                sd[i] = (float) a.landSD(x, z);
                if (x < a.minX) {
                    outside[i] = true;
                    kind[i] = (byte) (sd[i] > 0 ? K_LAND : K_SEA);
                    hts[i] = WorldSpec.SEA + (sd[i] > 0 ? 4 : 0);
                    continue;
                }
                w.terrain.sample(x, z, c);
                hts[i] = c.height;
                byte k;
                if (c.underwater()) k = (byte) (c.river || c.lake ? K_WATER : K_SEA);
                else if (c.surface == Blocks.SNOW_BLOCK) k = K_SNOW;
                else if (c.height > 135) k = K_MOUNT;
                else if (c.surface == Blocks.SAND || c.biome == Terrain.B_DESERT) k = K_SAND;
                else if (c.biome == Terrain.B_FOREST || c.biome == Terrain.B_DARK_FOREST || c.biome == Terrain.B_TAIGA
                    || c.biome == Terrain.B_BIRCH || (gf != null && Math.hypot(x - gf.x, z - gf.z) < gf.radius)) k = K_FOREST;
                else k = K_LAND;
                kind[i] = k;
            }
        });
        // Distance from land for every sea pixel (chamfer 3-4), for tint and coastal ripple lines.
        int[] dist = new int[n];
        final int INF = 1 << 28;
        for (int i = 0; i < n; i++) dist[i] = kind[i] == K_SEA ? INF : 0;
        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                int i = py * width + px;
                if (dist[i] == 0) continue;
                int d = dist[i];
                if (px > 0) d = Math.min(d, dist[i - 1] + 3);
                if (py > 0) {
                    d = Math.min(d, dist[i - width] + 3);
                    if (px > 0) d = Math.min(d, dist[i - width - 1] + 4);
                    if (px < width - 1) d = Math.min(d, dist[i - width + 1] + 4);
                }
                dist[i] = d;
            }
        }
        for (int py = height - 1; py >= 0; py--) {
            for (int px = width - 1; px >= 0; px--) {
                int i = py * width + px;
                if (dist[i] == 0) continue;
                int d = dist[i];
                if (px < width - 1) d = Math.min(d, dist[i + 1] + 3);
                if (py < height - 1) {
                    d = Math.min(d, dist[i + width] + 3);
                    if (px < width - 1) d = Math.min(d, dist[i + width + 1] + 4);
                    if (px > 0) d = Math.min(d, dist[i + width - 1] + 4);
                }
                dist[i] = d;
            }
        }
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                int i = py * width + px;
                int k = kind[i];
                double r, g, b;
                if (k == K_SEA) {
                    double blocks = dist[i] / 3.0 * bpp;
                    double t = Math.min(1, blocks / 1400.0);
                    r = 150 + 40 * t; g = 166 + 24 * t; b = 158 + 10 * t;
                    double ripple = blocks % 95;
                    if (blocks > 25 && blocks < 420 && ripple < bpp * 0.9) { r *= 0.84; g *= 0.86; b *= 0.86; }
                } else if (k == K_WATER) {
                    r = 122; g = 146; b = 146;
                } else {
                    switch (k) {
                        case K_FOREST: r = 164; g = 156; b = 102; break;
                        case K_MOUNT: r = 182; g = 162; b = 128; break;
                        case K_SAND: r = 228; g = 210; b = 160; break;
                        case K_SNOW: r = 238; g = 230; b = 212; break;
                        default: r = 208; g = 186; b = 138;
                    }
                    if (px > 0 && py > 0) {
                        int d = hts[i] - hts[i - width - 1];
                        double f = 1 + Math.max(-0.28, Math.min(0.28, d * (k == K_MOUNT ? 0.09 : 0.05)));
                        r *= f; g *= f; b *= f;
                    }
                    if (k == K_FOREST && Hash.unit(Hash.of(7, px, py)) < 0.22) { r *= 0.8; g *= 0.8; b *= 0.78; }
                    // Coastline in ink.
                    boolean coast = false;
                    if (px > 0 && kind[i - 1] == K_SEA) coast = true;
                    if (px < width - 1 && kind[i + 1] == K_SEA) coast = true;
                    if (py > 0 && kind[i - width] == K_SEA) coast = true;
                    if (py < height - 1 && kind[i + width] == K_SEA) coast = true;
                    if (coast) { r = 78; g = 56; b = 34; }
                }
                if (outside[i]) {
                    // Beyond the charted world: faded, hatched.
                    double fade = Math.min(1, (a.minX - (x0 + px * bpp)) / 900.0);
                    r = r * (1 - 0.5 * fade) + 206 * 0.5 * fade;
                    g = g * (1 - 0.5 * fade) + 188 * 0.5 * fade;
                    b = b * (1 - 0.5 * fade) + 146 * 0.5 * fade;
                    if ((px + py) % 7 == 0) { r *= 0.88; g *= 0.88; b *= 0.88; }
                }
                img.setRGB(px, py, rgb(r, g, b));
            }
        }

        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double s = 1.0 / bpp;
        java.util.function.DoubleUnaryOperator X = x -> (x - x0) * s, Z = z -> (z - z0) * s;
        // Faint survey grid.
        g.setColor(new Color(60, 40, 20, 26));
        g.setStroke(new BasicStroke(1f));
        for (int gx = (int) Math.ceil(x0 / 2000.0) * 2000; gx < x0 + width * bpp; gx += 2000) g.drawLine((int) X.applyAsDouble(gx), 0, (int) X.applyAsDouble(gx), height);
        for (int gz = (int) Math.ceil(z0 / 2000.0) * 2000; gz < z0 + height * bpp; gz += 2000) g.drawLine(0, (int) Z.applyAsDouble(gz), width, (int) Z.applyAsDouble(gz));
        // Rivers.
        g.setStroke(new BasicStroke(1.6f));
        g.setColor(new Color(0x5E7A80));
        for (com.pglol.aotworld.core.River r : a.rivers) {
            for (int i = 0; i + 1 < r.xs.length; i++) {
                g.drawLine((int) X.applyAsDouble(r.xs[i]), (int) Z.applyAsDouble(r.zs[i]),
                    (int) X.applyAsDouble(r.xs[i + 1]), (int) Z.applyAsDouble(r.zs[i + 1]));
            }
        }
        // Roads: solid ink for highways, dashes for trails.
        for (com.pglol.aotworld.core.Road r : w.roads.roads()) {
            switch (r.type) {
                case MAIN: case PAVED: g.setStroke(new BasicStroke(1.4f)); g.setColor(new Color(0x6A4A2C)); break;
                case TRAIL: g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] {3f, 2f}, 0f));
                    g.setColor(new Color(0x7A5A3A)); break;
                default: continue;
            }
            for (int i = 0; i + 1 < r.xs.length; i++) {
                if (a.landSD(r.xs[i], r.zs[i]) < 0) continue;
                g.drawLine((int) X.applyAsDouble(r.xs[i]), (int) Z.applyAsDouble(r.zs[i]),
                    (int) X.applyAsDouble(r.xs[i + 1]), (int) Z.applyAsDouble(r.zs[i + 1]));
            }
        }
        // Villages and towns in red-brown ink.
        for (Village v : w.villages) {
            int r = Math.max(2, (int) (v.radius * s * 0.8));
            g.setColor(new Color(0x9A5A36));
            g.fillOval((int) X.applyAsDouble(v.cx) - r, (int) Z.applyAsDouble(v.cz) - r, 2 * r, 2 * r);
        }
        g.setStroke(new BasicStroke(1.2f));
        for (Atlas.District d : a.districts) {
            double r = d.radius * s;
            int ex = (int) (X.applyAsDouble(d.cx) - r), ez = (int) (Z.applyAsDouble(d.cz) - r);
            g.setColor(new Color(0x9A5A36));
            g.fillOval(ex, ez, (int) (2 * r), (int) (2 * r));
            g.setColor(new Color(0x3A2818));
            g.drawOval(ex, ez, (int) (2 * r), (int) (2 * r));
        }
        double cr = a.capitalRadius * s;
        g.setColor(new Color(0xA8743A));
        g.fillOval((int) (X.applyAsDouble(0) - cr), (int) (Z.applyAsDouble(0) - cr), (int) (2 * cr), (int) (2 * cr));
        g.setColor(new Color(0x3A2818));
        g.drawOval((int) (X.applyAsDouble(0) - cr), (int) (Z.applyAsDouble(0) - cr), (int) (2 * cr), (int) (2 * cr));
        for (Atlas.Site site : a.sites) {
            if (site.kind == Atlas.Kind.GIANT_FOREST) continue;
            int r = Math.max(3, (int) (site.radius * s * 0.7));
            int sx = (int) X.applyAsDouble(site.x), sz = (int) Z.applyAsDouble(site.z);
            g.setColor(new Color(0x8A4A2E));
            g.fillRect(sx - r, sz - r, 2 * r, 2 * r);
            g.setColor(new Color(0x3A2818));
            g.drawRect(sx - r, sz - r, 2 * r, 2 * r);
        }
        // The Walls: bold ink.
        for (Atlas.Wall wall : a.walls) {
            double r = wall.radius * s;
            g.setStroke(new BasicStroke(3.4f));
            g.setColor(new Color(0x3A2818));
            g.drawOval((int) (X.applyAsDouble(0) - r), (int) (Z.applyAsDouble(0) - r), (int) (2 * r), (int) (2 * r));
            g.setStroke(new BasicStroke(1f));
            g.setColor(new Color(0xC8A870));
            g.drawOval((int) (X.applyAsDouble(0) - r), (int) (Z.applyAsDouble(0) - r), (int) (2 * r), (int) (2 * r));
        }
        compass(g, width - 150, height - 150, 90);
        g.dispose();
        paper(img);
        return img;
    }

    private static int rgb(double r, double g, double b) {
        return ((int) Math.max(0, Math.min(255, r)) << 16) | ((int) Math.max(0, Math.min(255, g)) << 8) | (int) Math.max(0, Math.min(255, b));
    }

    /** A simple ink compass rose. */
    private static void compass(Graphics2D g, int cx, int cy, int r) {
        g.setColor(new Color(58, 40, 24, 200));
        g.setStroke(new BasicStroke(1.5f));
        g.drawOval(cx - r, cy - r, 2 * r, 2 * r);
        g.drawOval(cx - r + 8, cy - r + 8, 2 * r - 16, 2 * r - 16);
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4 - Math.PI / 2;
            double len = i % 2 == 0 ? r - 4 : r * 0.55;
            double w = i % 2 == 0 ? 12 : 7;
            double lx = Math.cos(ang), lz = Math.sin(ang), nx = -lz, nz = lx;
            int[] xs = {cx + (int) (lx * len), cx + (int) (nx * w), cx - (int) (nx * w)};
            int[] zs = {cy + (int) (lz * len), cy + (int) (nz * w), cy - (int) (nz * w)};
            g.setColor(i == 0 ? new Color(0x8A2A1E) : new Color(58, 40, 24, 220));
            g.fillPolygon(xs, zs, 3);
        }
        g.setFont(new Font(Font.SERIF, Font.BOLD, 22));
        g.setColor(new Color(0x3A2818));
        String[] l = {"N", "E", "S", "W"};
        for (int i = 0; i < 4; i++) {
            double ang = i * Math.PI / 2 - Math.PI / 2;
            int tx = cx + (int) (Math.cos(ang) * (r + 16)), tz = cy + (int) (Math.sin(ang) * (r + 16));
            int tw = g.getFontMetrics().stringWidth(l[i]);
            g.drawString(l[i], tx - tw / 2, tz + 8);
        }
    }

    /** Paper grain, a few stains and scorched edges. */
    private static void paper(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        long seed = 91;
        double[][] stains = new double[7][];
        for (int i = 0; i < stains.length; i++) {
            stains[i] = new double[] {Hash.unit(Hash.of(seed, i, 1)) * w, Hash.unit(Hash.of(seed, i, 2)) * h,
                60 + Hash.unit(Hash.of(seed, i, 3)) * 220};
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = img.getRGB(x, y);
                double r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                double grain = 0.94 + 0.06 * valueNoise(x / 3.0, y / 3.0) + 0.05 * (valueNoise(x / 40.0 + 9, y / 40.0 + 3) - 0.5);
                for (double[] st : stains) {
                    double d = Math.hypot(x - st[0], y - st[1]) / st[2];
                    if (d < 1) grain *= 1 - 0.06 * (1 - d) * (1 - d);
                }
                double ex = Math.min(x, w - 1 - x) / (double) w, ey = Math.min(y, h - 1 - y) / (double) h;
                double e = Math.min(ex, ey) + 0.012 * (valueNoise(x / 25.0, y / 25.0) - 0.5);
                double burn = e < 0.005 ? 0.35 : e < 0.03 ? 0.35 + 0.65 * (e - 0.005) / 0.025 : 1;
                double vig = 1 - 0.18 * Math.pow(1 - Math.min(ex, ey) * 2, 3);
                double f = grain * burn * vig;
                img.setRGB(x, y, rgb(r * f, g * f * 0.985, b * f * 0.96));
            }
        }
    }

    private static double valueNoise(double x, double y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        double fx = x - ix, fy = y - iy;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        double a = Hash.unit(Hash.of(5, ix, iy)), b = Hash.unit(Hash.of(5, ix + 1, iy));
        double c = Hash.unit(Hash.of(5, ix, iy + 1)), d = Hash.unit(Hash.of(5, ix + 1, iy + 1));
        return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
    }

    /** Old-map look: colours pulled toward sepia parchment, darker edges. */
    private static void parchment(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = img.getRGB(x, y);
                double r = (c >> 16) & 255, gg = (c >> 8) & 255, b = c & 255;
                double lum = 0.3 * r + 0.59 * gg + 0.11 * b;
                boolean water = b > r + 30 && b > gg;
                double sr, sg, sb;
                if (water) {
                    sr = 60 + lum * 0.25; sg = 88 + lum * 0.30; sb = 96 + lum * 0.30;
                } else {
                    sr = 70 + lum * 0.78; sg = 58 + lum * 0.66; sb = 34 + lum * 0.42;
                }
                double k = 0.62;
                r = r * (1 - k) + sr * k;
                gg = gg * (1 - k) + sg * k;
                b = b * (1 - k) + sb * k;
                double dx = (x - w / 2.0) / (w / 2.0), dy = (y - h / 2.0) / (h / 2.0);
                double v = 1 - 0.35 * Math.pow(Math.min(1, Math.sqrt(dx * dx + dy * dy)), 2.2);
                img.setRGB(x, y, ((int) Math.min(255, r * v) << 16) | ((int) Math.min(255, gg * v) << 8) | (int) Math.min(255, b * v));
            }
        }
    }

    public static BufferedImage detail(AotWorld w, int cx, int cz, int size) {
        int x0 = cx - size / 2, z0 = cz - size / 2;
        int c0x = Math.floorDiv(x0, 16), c0z = Math.floorDiv(z0, 16);
        int c1x = Math.floorDiv(x0 + size - 1, 16), c1z = Math.floorDiv(z0 + size - 1, 16);
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        int[] tops = new int[size * size];
        int[] cols = new int[size * size];
        int ncx = c1x - c0x + 1;
        IntStream.range(0, ncx * (c1z - c0z + 1)).parallel().forEach(i -> {
            int chx = c0x + i % ncx, chz = c0z + i / ncx;
            ChunkBuffer buf = new ChunkBuffer();
            w.composer.compose(chx, chz, buf);
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int x = (chx << 4) + lx, z = (chz << 4) + lz;
                    int px = x - x0, pz = z - z0;
                    if (px < 0 || pz < 0 || px >= size || pz >= size) continue;
                    int y = buf.top(x, z);
                    int id = buf.get(x, y, z);
                    int color = BlockColors.color(id);
                    if (id == Blocks.WATER) {
                        int yy = y;
                        while (yy > ChunkBuffer.MIN_Y && buf.get(x, yy, z) == Blocks.WATER) yy--;
                        color = mix(0x3f76e4, 0x1c3a8a, Math.min(1, (y - yy) / 20.0));
                    }
                    tops[pz * size + px] = y;
                    cols[pz * size + px] = color;
                }
            }
        });
        for (int pz = 0; pz < size; pz++) {
            for (int px = 0; px < size; px++) {
                int i = pz * size + px;
                int c = cols[i];
                if (px > 0 && pz > 0) {
                    int d = tops[i] - tops[i - size - 1];
                    c = shade(c, 1 + Math.max(-0.4, Math.min(0.4, d * 0.08)));
                }
                img.setRGB(px, pz, c);
            }
        }
        return img;
    }

    // ---- Colour helpers ------------------------------------------------------------------

    static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    static int shade(int c, double f) {
        int r = (int) Math.min(255, ((c >> 16) & 255) * f);
        int g = (int) Math.min(255, ((c >> 8) & 255) * f);
        int b = (int) Math.min(255, (c & 255) * f);
        return (r << 16) | (g << 8) | b;
    }

    @SuppressWarnings("unused")
    private static Region unused;
}
