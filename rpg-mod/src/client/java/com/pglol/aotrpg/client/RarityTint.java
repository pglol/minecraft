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
 * Infused blades wear their element instead, far stronger: ice blue, molten orange, a near-black
 * void, and so on, with light rippling along the steel (the higher the rarity, the livelier).
 */
public final class RarityTint {
    private RarityTint() {}

    private static final int[] TINT = {0, 0, 0x3A7AFF, 0xB04AFF, 0xFFB020, 0xFF1A1A};
    private static final float[] STRENGTH = {0, 0, 0.22f, 0.32f, 0.42f, 0.62f};

    /** The provider to draw this stack with: tinted for rare-and-up Danny weapons, else as it was. */
    /**
     * Danny's grips draw their loaded blade as a second item inside their own render; that inner
     * draw takes the grip's colour too, so the whole sword shows it, not just the hilt.
     */
    private static final java.util.ArrayDeque<ItemStack> drawing = new java.util.ArrayDeque<>();

    public static VertexConsumerProvider enter(ItemStack s, VertexConsumerProvider base) {
        ItemStack owner = s;
        if (!drawing.isEmpty() && (s == null || s.isEmpty() || !com.pglol.aotrpg.Gear.isGear(s))) owner = drawing.peek();
        drawing.push(owner == null ? ItemStack.EMPTY : owner);
        return wrap(owner, base);
    }

    /** Between frames nothing is being drawn: a draw that never finished can't colour anything else. */
    public static void reset() {
        drawing.clear();
    }

    public static void exit() {
        if (!drawing.isEmpty()) drawing.pop();
    }

    public static VertexConsumerProvider wrap(ItemStack s, VertexConsumerProvider base) {
        if (base == null || s == null || s.isEmpty() || !com.pglol.aotrpg.Gear.aotWeapon(s)) return base;
        int r = GearUi.rarity(s);
        if (r < 2 || r >= TINT.length) return base;
        com.pglol.aotrpg.Infusions.Infusion inf = com.pglol.aotrpg.Infusions.of(s);
        if (inf != null) {
            float k = r >= 5 ? 0.82f : r == 4 ? 0.7f : 0.58f;
            float ripple = r >= 5 ? 0.18f : r == 4 ? 0.12f : 0.07f;
            float t = (Util.getMeasuringTimeMs() % 100000L) / (r >= 5 ? 180f : 320f);
            boolean dark = inf == com.pglol.aotrpg.Infusions.Infusion.VOID;
            return layer -> new Tinted(base.getBuffer(layer), inf.color, k, ripple, t, dark);
        }
        float k = STRENGTH[r];
        if (r == 5) k += 0.12f * (float) Math.sin(Util.getMeasuringTimeMs() / 260.0);
        final float kk = k;
        final int tint = TINT[r];
        return layer -> new Tinted(base.getBuffer(layer), tint, kk, 0, 0, false);
    }

    /** Passes everything through, pulling each vertex colour toward the tint (keeping its shading). */
    private static final class Tinted implements VertexConsumer {
        private final VertexConsumer inner;
        private final int tint;
        private final float k, ripple, t;
        private final boolean dark;
        private float at;

        Tinted(VertexConsumer inner, int tint, float k, float ripple, float t, boolean dark) {
            this.inner = inner;
            this.tint = tint;
            this.k = k;
            this.ripple = ripple;
            this.t = t;
            this.dark = dark;
        }

        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            at = x * 3.1f + y * 4.7f + z * 2.3f;
            inner.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            float luma = (red * 0.3f + green * 0.59f + blue * 0.11f) / 255f;
            int tr = (tint >> 16) & 255, tg = (tint >> 8) & 255, tb = tint & 255;
            // Light running along the steel.
            float wave = ripple == 0 ? 0 : (float) Math.sin(t + at);
            float kk = Math.max(0, Math.min(1, k + ripple * wave));
            float bright = dark ? 0.55f + 0.5f * Math.max(0, wave) * (ripple * 4) : 1.35f + ripple * 2 * Math.max(0, wave);
            // The void swallows the steel's own colour: near-black with violet running through it.
            float keep = dark ? (1 - kk) * 0.35f : 1 - kk;
            int r = Math.round(red * keep + tr * luma * bright * kk);
            int g = Math.round(green * keep + tg * luma * bright * kk);
            int b = Math.round(blue * keep + tb * luma * bright * kk);
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
