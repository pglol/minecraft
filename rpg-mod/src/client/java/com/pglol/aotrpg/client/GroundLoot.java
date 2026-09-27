package com.pglol.aotrpg.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Cards over items on the ground, instead of a glow: the name (in its rarity colour) and, up close,
 * the details: rarity and kind, item level, the main stats, and whether it's too high for you. Gear
 * with a rarity gets a glowing frame, pulsing on Legendary and Mythic. Far away only the name shows.
 */
public final class GroundLoot {
    private GroundLoot() {}

    private static final int[] RARITY = {0xDDDDDD, 0x55FF55, 0x5599FF, 0xC055FF, 0xFFB020, 0xFF2A2A};

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null || mc.options.hudHidden) return;
        MatrixStack ms = ctx.matrixStack();
        VertexConsumerProvider vc = ctx.consumers();
        if (ms == null || vc == null) return;
        Vec3d cam = ctx.camera().getPos();
        float td = ctx.tickCounter().getTickDelta(true);
        TextRenderer tr = mc.textRenderer;
        float time = (Util.getMeasuringTimeMs() % 100000) / 1000f;
        List<ItemEntity> items = mc.world.getEntitiesByClass(ItemEntity.class, new Box(cam, cam).expand(18), e -> !e.getStack().isEmpty());
        for (ItemEntity e : items) {
            ItemStack s = e.getStack();
            Vec3d pos = e.getLerpedPos(td);
            double dist = pos.distanceTo(cam);
            if (dist > 18) continue;
            int rar = GearUi.rarity(s);
            long marks = marks(s);
            boolean near = dist < 7;
            // What the card says.
            List<Text> lines = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            int titleCol = rar >= 0 ? RARITY[rar] : marks > 0 ? 0xF2C14E : 0xEDE3C8;
            String title = marks > 0 ? marks + " Marks" : s.getName().getString() + (s.getCount() > 1 ? "  ×" + s.getCount() : "");
            if (rar >= 0 && near) {
                LoreComponent lore = s.get(DataComponentTypes.LORE);
                if (lore != null) {
                    int stats = 0;
                    for (int i = 0; i < lore.lines().size(); i++) {
                        String l = lore.lines().get(i).getString();
                        if (i == 0) {
                            lines.add(Text.literal(l));
                            colors.add(RARITY[rar]);
                        } else if (l.startsWith("Item level")) {
                            lines.add(Text.literal(l));
                            colors.add(0xB8B0A0);
                        } else if ((l.startsWith("+") || l.startsWith("✦")) && stats < 4) {
                            lines.add(Text.literal(l));
                            colors.add(l.startsWith("✦") ? 0x7FE0E0 : 0x8FB8E8);
                            stats++;
                        }
                    }
                }
                if (GearUi.locked(s)) {
                    lines.add(Text.literal("Too high for you yet"));
                    colors.add(0xE05A4A);
                }
            }
            float fade = dist < 14 ? 1 : (float) (1 - (dist - 14) / 4);
            int alpha = (int) (255 * Math.max(0, Math.min(1, fade)));
            if (alpha < 8) continue;
            ms.push();
            ms.translate(pos.x - cam.x, pos.y - cam.y + 0.75 + Math.sin(time * 2 + e.getId()) * 0.03, pos.z - cam.z);
            ms.multiply(mc.getEntityRenderDispatcher().getRotation());
            float sc = 0.02f;
            ms.scale(sc, -sc, sc);
            Matrix4f m = ms.peek().getPositionMatrix();
            int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
            int w = tr.getWidth(title);
            for (Text t : lines) w = Math.max(w, (int) (tr.getWidth(t) * 0.8f));
            int h = 10 + lines.size() * 8 + (lines.isEmpty() ? 0 : 3);
            float x0 = -w / 2f - 5, x1 = w / 2f + 5, y0 = -h - 4, y1 = 0;
            VertexConsumer bg = vc.getBuffer(RenderLayer.getTextBackground());
            quad(bg, m, x0, y0, x1, y1, -1.2f, (alpha * 170 / 255) << 24 | 0x0D0F0D, light);
            // The frame: glowing in the rarity colour (pulsing from Legendary up).
            if (rar >= 1) {
                float pulse = rar >= 4 ? 0.6f + 0.4f * (float) Math.sin(time * (rar == 5 ? 6 : 3)) : 0.85f;
                int fa = (int) (alpha * pulse);
                int col = RARITY[rar];
                VertexConsumer glow = vc.getBuffer(RenderLayer.getTextBackground());
                float t1 = 1.2f, g = rar >= 3 ? 3.5f : 2f;
                quad(glow, m, x0, y0, x1, y0 + t1, -1.3f, fa << 24 | col, light);
                quad(glow, m, x0, y1 - t1, x1, y1, -1.3f, fa << 24 | col, light);
                quad(glow, m, x0, y0, x0 + t1, y1, -1.3f, fa << 24 | col, light);
                quad(glow, m, x1 - t1, y0, x1, y1, -1.3f, fa << 24 | col, light);
                // A soft halo outside the frame.
                int ha = fa / 3;
                quad(glow, m, x0 - g, y0 - g, x1 + g, y0, -1.1f, ha << 24 | col, light);
                quad(glow, m, x0 - g, y1, x1 + g, y1 + g, -1.1f, ha << 24 | col, light);
                quad(glow, m, x0 - g, y0, x0, y1, -1.1f, ha << 24 | col, light);
                quad(glow, m, x1, y0, x1 + g, y1, -1.1f, ha << 24 | col, light);
                // A tag of colour on the left edge.
                quad(glow, m, x0, y0, x0 + 2.5f, y1, -1.35f, alpha << 24 | col, light);
            }
            float y = y0 + 2;
            tr.draw(title, -tr.getWidth(title) / 2f, y, alpha << 24 | titleCol, false, m, vc, TextRenderer.TextLayerType.POLYGON_OFFSET, 0, light);
            y += 11;
            for (int i = 0; i < lines.size(); i++) {
                Text t = lines.get(i);
                ms.push();
                ms.translate(0, y, 0);
                ms.scale(0.8f, 0.8f, 1);
                tr.draw(t, -tr.getWidth(t) / 2f, 0, alpha << 24 | colors.get(i), false, ms.peek().getPositionMatrix(), vc,
                    TextRenderer.TextLayerType.POLYGON_OFFSET, 0, light);
                ms.pop();
                y += 8;
            }
            ms.pop();
        }
    }

    private static long marks(ItemStack s) {
        NbtComponent c = s.get(DataComponentTypes.CUSTOM_DATA);
        return c == null ? 0 : c.copyNbt().getLong("aot_marks");
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float x1, float y1, float x2, float y2, float z, int argb, int light) {
        vc.vertex(m, x1, y1, z).color(argb).light(light);
        vc.vertex(m, x1, y2, z).color(argb).light(light);
        vc.vertex(m, x2, y2, z).color(argb).light(light);
        vc.vertex(m, x2, y1, z).color(argb).light(light);
        vc.vertex(m, x2, y1, z).color(argb).light(light);
        vc.vertex(m, x2, y2, z).color(argb).light(light);
        vc.vertex(m, x1, y2, z).color(argb).light(light);
        vc.vertex(m, x1, y1, z).color(argb).light(light);
    }
}
