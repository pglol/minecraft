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

    private static int color(ClientWorld w, int x, int z, BlockPos.Mutable pos) {
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
        // Entities
        for (Entity e : mc.world.getEntities()) {
            if (e == pl || !(e instanceof LivingEntity le) || !le.isAlive()) continue;
            double sx = e.getX() - ox, sz = e.getZ() - oz;
            if (sx < 1 || sz < 1 || sx > s - 1 || sz > s - 1) continue;
            int ex = x + (int) sx, ey = y + (int) sz;
            String type = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
            if (type.contains("titan")) {
                c.fill(ex - 3, ey - 3, ex + 3, ey + 3, 0xFF2A0806);
                c.fill(ex - 2, ey - 2, ex + 2, ey + 2, 0xFFE0442F);
            } else if (e instanceof Monster) {
                c.fill(ex - 1, ey - 1, ex + 1, ey + 1, 0xFFD04A3A);
            } else if (e instanceof PlayerEntity && ClientState.partyMember(e.getUuid()) == null) {
                c.fill(ex - 1, ey - 1, ex + 1, ey + 1, 0xFFEDE3C8);
            }
        }
        // Party members (also far away: clamped to the edge)
        for (Net.PartyMember m : ClientState.party) {
            if (!m.online() || !m.sameWorld()) continue;
            int[] p = clamp(m.x() - ox, m.z() - oz, s);
            int col = m.leader() ? 0xFFF2C14E : 0xFF5BD35B;
            c.fill(x + p[0] - 2, y + p[1] - 2, x + p[0] + 3, y + p[1] + 3, 0xFF0B0F0C);
            c.fill(x + p[0] - 1, y + p[1] - 1, x + p[0] + 2, y + p[1] + 2, col);
        }
        // Objective marker
        Net.Objective obj = ClientState.objective;
        if (obj != null && obj.hasTarget()) {
            int[] p = clamp(obj.x() + 0.5 - ox, obj.z() + 0.5 - oz, s);
            int mx = x + p[0], my = y + p[1];
            long t = net.minecraft.util.Util.getMeasuringTimeMs();
            int pulse = (int) (1 + Math.sin(t / 200.0));
            c.fill(mx - 1, my - 3 - pulse, mx + 2, my + 4 + pulse, 0xFF3A2A0A);
            c.fill(mx - 3 - pulse, my - 1, mx + 4 + pulse, my + 2, 0xFF3A2A0A);
            c.fill(mx, my - 2 - pulse, mx + 1, my + 3 + pulse, Ui.GOLD);
            c.fill(mx - 2 - pulse, my, mx + 3 + pulse, my + 1, Ui.GOLD);
        }
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
