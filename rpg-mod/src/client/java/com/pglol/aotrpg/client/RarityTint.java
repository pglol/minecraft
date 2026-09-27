package com.pglol.aotrpg.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Util;

/**
 * Danny's blades and APG guns reskinned by rarity: the whole item is washed in its rarity's
 * colour as it's drawn (in hand, in any inventory, on your back, on the ground), a touch for
 * Rare, stronger through Epic and Legendary, and for Mythic a deep blood red that pulses.
 */
public final class RarityTint {
    private RarityTint() {}

    private static final int[] TINT = {0, 0, 0x3A7AFF, 0xB04AFF, 0xFFB020, 0xFF1A1A};
    private static final float[] STRENGTH = {0, 0, 0.22f, 0.32f, 0.42f, 0.62f};

    /** The provider to draw this stack with: tinted for rare-and-up Danny weapons, else as it was. */
    public static VertexConsumerProvider wrap(ItemStack s, VertexConsumerProvider base) {
        if (base == null || s == null || s.isEmpty() || !com.pglol.aotrpg.Gear.aotWeapon(s)) return base;
        int r = GearUi.rarity(s);
        if (r < 2 || r >= TINT.length) return base;
        float k = STRENGTH[r];
        if (r == 5) k += 0.12f * (float) Math.sin(Util.getMeasuringTimeMs() / 260.0);
        final float kk = k;
        final int tint = TINT[r];
        return layer -> new Tinted(base.getBuffer(layer), tint, kk);
    }

    /** Passes everything through, pulling each vertex colour toward the tint (keeping its shading). */
    private record Tinted(VertexConsumer inner, int tint, float k) implements VertexConsumer {
        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            inner.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            float luma = (red * 0.3f + green * 0.59f + blue * 0.11f) / 255f;
            int tr = (tint >> 16) & 255, tg = (tint >> 8) & 255, tb = tint & 255;
            int r = Math.round(red * (1 - k) + tr * luma * 1.35f * k);
            int g = Math.round(green * (1 - k) + tg * luma * 1.35f * k);
            int b = Math.round(blue * (1 - k) + tb * luma * 1.35f * k);
            inner.color(Math.min(255, r), Math.min(255, g), Math.min(255, b), alpha);
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            inner.texture(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            inner.overlay(u, v);
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            inner.light(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            inner.normal(x, y, z);
            return this;
        }
    }
}
