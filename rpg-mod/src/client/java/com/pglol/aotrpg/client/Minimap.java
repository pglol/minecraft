package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.Heightmap;

/**
 * Top-left minimap: terrain from loaded chunks (1 block per pixel, north up), you as an arrow,
 * party members, titans and hostile mobs, and the current story objective.
 */
public final class Minimap {
    private Minimap() {}

    private static final int TEX = 128, VIEW = 96, ROWS_PER_TICK = 16;
    private static final Identifier ID = Identifier.of("aot_rpg", "minimap");

    private static NativeImage image, back;
    private static NativeImageBackedTexture texture;
    private static int row, centerX, centerZ, nextCenterX, nextCenterZ;
    /** Screen y just below the minimap and objective, for the party frames. */
    public static int bottom = 4;

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity pl = mc.player;
        ClientWorld w = mc.world;
        if (pl == null || w == null || !ClientState.minimap) return;
        if (image == null) {
            image = new NativeImage(TEX, TEX, true);
            back = new NativeImage(TEX, TEX, true);
            texture = new NativeImageBackedTexture(image);
            mc.getTextureManager().registerTexture(ID, texture);
            centerX = nextCenterX = pl.getBlockX();
            centerZ = nextCenterZ = pl.getBlockZ();
        }
        if (row == 0) {
            nextCenterX = pl.getBlockX();
            nextCenterZ = pl.getBlockZ();
        }
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int i = 0; i < ROWS_PER_TICK; i++) {
            int z = nextCenterZ - TEX / 2 + row;
            for (int px = 0; px < TEX; px++) {
                int x = nextCenterX - TEX / 2 + px;
                back.setColor(px, row, color(w, x, z, pos));
            }
            row++;
            if (row >= TEX) {
                row = 0;
                // Finished frame: show it (drawing never sees half-written rows).
                image.copyFrom(back);
                centerX = nextCenterX;
                centerZ = nextCenterZ;
                texture.upload();
                break;
            }
        }
    }

    private static int height(ClientWorld w, int x, int z) {
        return w.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
    }

    /** A tiny house icon centred on (cx, cy). */
    static void house(DrawContext c, int cx, int cy, int col) {
        c.fill(cx - 4, cy - 1, cx + 5, cy + 5, 0xFF101010);
        c.fill(cx - 3, cy, cx + 4, cy + 4, col);
        c.fill(cx - 1, cy + 2, cx + 2, cy + 4, 0xFF3A2A16);
        for (int i = 0; i < 4; i++) {
            c.fill(cx - 4 + i, cy - 1 - i, cx + 5 - i, cy - i, i == 0 ? 0xFF101010 : col);
        }
        c.fill(cx, cy - 5, cx + 1, cy - 4, 0xFF101010);
    }

    /** Slightly muted colours so markers stand out (ABGR). */
    private static int mute(int abgr) {
        int r = abgr & 255, g = (abgr >> 8) & 255, b = (abgr >> 16) & 255;
        int l = (r * 3 + g * 6 + b) / 10;
        r = (r * 7 + l * 3) / 10 * 85 / 100;
        g = (g * 7 + l * 3) / 10 * 85 / 100;
        b = (b * 7 + l * 3) / 10 * 85 / 100;
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }

    private static int color(ClientWorld w, int x, int z, BlockPos.Mutable pos) {
        return mute(rawColor(w, x, z, pos));
    }

    private static int rawColor(ClientWorld w, int x, int z, BlockPos.Mutable pos) {
        if (!w.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) return 0xFF0E0F0E;
        int y = height(w, x, z);
        if (y <= w.getBottomY()) return 0xFF0E0F0E;
        pos.set(x, y, z);
        BlockState st = w.getBlockState(pos);
        if (st.getFluidState().isOf(Fluids.WATER) || st.getFluidState().isOf(Fluids.FLOWING_WATER)) {
            int depth = 1;
            while (depth < 12) {
                pos.setY(y - depth);
                if (w.getFluidState(pos).isEmpty()) break;
                depth++;
            }
            MapColor.Brightness b = depth > 7 ? MapColor.Brightness.LOWEST : depth > 3 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
            return MapColor.WATER_BLUE.getRenderColor(b);
        }
        MapColor mc = st.getMapColor(w, pos);
        if (mc == MapColor.CLEAR) return 0xFF0E0F0E;
        int north = height(w, x, z - 1);
        MapColor.Brightness b = y > north ? MapColor.Brightness.HIGH : y < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
        return mc.getRenderColor(b);
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity pl = mc.player;
        bottom = 4;
        if (pl == null || texture == null || !ClientState.minimap || mc.currentScreen != null || mc.options.hudHidden || mc.getDebugHud().shouldShowDebugHud()) return;
        float td = tick.getTickDelta(true);
        double px = pl.getLerpedPos(td).x, pz = pl.getLerpedPos(td).z;

        int x = 6, y = 6, s = VIEW;
        Ui.panel(c, x - 2, y - 2, s + 4, s + 4);
        int u = (int) Math.round(px - centerX) + (TEX - VIEW) / 2;
        int v = (int) Math.round(pz - centerZ) + (TEX - VIEW) / 2;
        u = Math.max(0, Math.min(TEX - VIEW, u));
        v = Math.max(0, Math.min(TEX - VIEW, v));
        c.drawTexture(ID, x, y, s, s, u, v, s, s, TEX, TEX);
        // map-space origin (block at the top-left pixel of the view)
        double ox = centerX - TEX / 2.0 + u, oz = centerZ - TEX / 2.0 + v;

        // Cooking fires
        int[] fires = ClientState.campfires;
        for (int i = 0; i + 2 < fires.length; i += 3) {
            double sx = fires[i] + 0.5 - ox, sz = fires[i + 2] + 0.5 - oz;
            if (sx < 2 || sz < 2 || sx > s - 2 || sz > s - 2) continue;
            int fx = x + (int) sx, fy = y + (int) sz;
            c.fill(fx - 2, fy - 2, fx + 2, fy + 2, 0xFF2A1406);
            c.fill(fx - 1, fy - 1, fx + 1, fy + 1, 0xFFFF9A2E);
        }
        WitnessFx.minimap(c, x, y, s, ox, oz);
        // Entities: one marker per creature (titan hitbox parts are not mobs), diamonds so they never look like roofs.
        java.util.List<int[]> placed = new java.util.ArrayList<>();
        for (Entity e : mc.world.getEntities()) {
            if (e == pl || !(e instanceof net.minecraft.entity.mob.MobEntity le) || !le.isAlive()) continue;
            double sx = e.getX() - ox, sz = e.getZ() - oz;
            if (sx < 3 || sz < 3 || sx > s - 3 || sz > s - 3) continue;
            int ex = x + (int) sx, ey = y + (int) sz;
            String type = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
            boolean titan = type.contains("titan");
            if (!titan && !(e instanceof Monster)) continue;
            boolean dup = false;
            for (int[] p : placed) if (Math.abs(p[0] - ex) <= 3 && Math.abs(p[1] - ey) <= 3) dup = true;
            if (dup) continue;
            placed.add(new int[] {ex, ey});
            if (titan) diamond(c, ex, ey, 4, 0xFFFFFFFF, 0xFFE0302A);
            else diamond(c, ex, ey, 2, 0xFF200808, 0xFFD06050);
        }
        // Party members (also far away: clamped to the edge)
        for (Net.PartyMember m : ClientState.party) {
            if (!m.online() || !m.sameWorld()) continue;
            int[] p = clamp(m.x() - ox, m.z() - oz, s);
            int col = m.leader() ? 0xFFF2C14E : 0xFF5BD35B;
            c.fill(x + p[0] - 2, y + p[1] - 2, x + p[0] + 3, y + p[1] + 3, 0xFF0B0F0C);
            c.fill(x + p[0] - 1, y + p[1] - 1, x + p[0] + 2, y + p[1] + 2, col);
        }
        // Quest targets, map marks and party highlights (pinned to the edge when far away).
        for (Net.Marker m : ClientState.markers) {
            if (m.kind().equals("ferry")) {
                double fx = m.x() + 0.5 - ox, fz = m.z() + 0.5 - oz;
                if (fx >= 4 && fx <= s - 4 && fz >= 4 && fz <= s - 4) c.drawText(Ui.font(), Text.literal("⚓"), x + (int) fx - 3, y + (int) fz - 4, 0xFF000000 | m.color(), true);
                continue;
            }
            if (m.kind().equals("home")) {
                // Your homes: a small house, only when on the minimap.
                double hx = m.x() + 0.5 - ox, hz = m.z() + 0.5 - oz;
                if (hx >= 3 && hx <= s - 3 && hz >= 3 && hz <= s - 3) house(c, x + (int) hx, y + (int) hz, 0xFF000000 | m.color());
                continue;
            }
            int[] p = clamp(m.x() + 0.5 - ox, m.z() + 0.5 - oz, s);
            diamond(c, x + p[0], y + p[1], 3, 0xFF101010, 0xFF000000 | m.color());
        }
        Net.Objective obj = ClientState.objective;
        // You
        MatrixStack ms = c.getMatrices();
        ms.push();
        ms.translate(x + (float) (px - ox), y + (float) (pz - oz), 0);
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(pl.getYaw(td) + 180));
        Text arrow = Text.literal("▲");
        c.drawText(Ui.font(), arrow, -Ui.font().getWidth(arrow) / 2, -4, 0xFFFFFFFF, true);
        ms.pop();
        // North marker and coordinates
        c.drawCenteredTextWithShadow(Ui.font(), Text.literal("N"), x + s / 2, y + 1, Ui.GOLD);
        String coords = pl.getBlockX() + ", " + pl.getBlockZ();
        c.drawTextWithShadow(Ui.font(), Text.literal(coords), x + s - Ui.font().getWidth(coords) - 2, y + s - 9, 0xC0EDE3C8);

        int ty = y + s + 5;
        if (obj != null) {
            c.drawTextWithShadow(Ui.font(), Ui.heading(obj.chapter()), x - 1, ty, Ui.GOLD);
            ty += 11;
            String line = "▶ " + obj.text();
            if (!obj.progress().isEmpty()) line += "  " + obj.progress();
            if (obj.hasTarget()) {
                double dx = obj.x() - pl.getX(), dz = obj.z() - pl.getZ();
                line += "  (" + (int) Math.sqrt(dx * dx + dz * dz) + "m)";
            }
            ty = Ui.wrapped(c, Text.literal(line), x - 1, ty, 150, Ui.CREAM);
        }
        bottom = ty + 4;
    }

    private static void diamond(DrawContext c, int x, int y, int r, int outline, int fill) {
        for (int i = -r; i <= r; i++) {
            int w = r - Math.abs(i);
            c.fill(x - w - 1, y + i, x + w + 2, y + i + 1, outline);
        }
        for (int i = -r + 1; i <= r - 1; i++) {
            int w = r - 1 - Math.abs(i);
            c.fill(x - w, y + i, x + w + 1, y + i + 1, fill);
        }
    }

    private static int[] clamp(double sx, double sz, int s) {
        double cx = s / 2.0, cz = s / 2.0;
        double dx = sx - cx, dz = sz - cz;
        double lim = s / 2.0 - 3;
        double k = Math.max(Math.abs(dx), Math.abs(dz));
        if (k > lim) {
            dx = dx * lim / k;
            dz = dz * lim / k;
        }
        return new int[] {(int) Math.round(cx + dx), (int) Math.round(cz + dz)};
    }
}
