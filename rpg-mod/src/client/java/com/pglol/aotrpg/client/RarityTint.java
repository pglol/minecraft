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
        return enter(s, null, base);
    }

    /**
     * Infused blades drawn in a hand (first or third person) or on a back: points picked from the
     * sword's own drawn geometry, so its element comes off the blade wherever it actually is
     * mid-swing, blocking or sheathed. Null when nothing is being sampled.
     */
    private static Sampler sampler;

    public static final class Sampler {
        public final ItemStack stack;
        /** The right-hand item in first person (else the left). */
        public final boolean right;
        public final float[][] picks = new float[6][3];
        public int seen;
        private final java.util.Random r = new java.util.Random();

        Sampler(ItemStack stack, boolean right) {
            this.stack = stack;
            this.right = right;
        }

        /** Reservoir sampling: every drawn vertex has the same chance of being one of the picks. */
        void offer(float x, float y, float z) {
            int i = seen++;
            int slot = i < picks.length ? i : r.nextInt(i + 1);
            if (slot < picks.length) {
                picks[slot][0] = x;
                picks[slot][1] = y;
                picks[slot][2] = z;
            }
        }
    }

    public static VertexConsumerProvider enter(ItemStack s, net.minecraft.client.render.model.json.ModelTransformationMode mode, VertexConsumerProvider base) {
        ItemStack owner = s;
        if (!drawing.isEmpty() && (s == null || s.isEmpty() || !com.pglol.aotrpg.Gear.isGear(s))) owner = drawing.peek();
        // A loaded blade the AoT mod draws right after its grip (not inside it): it's that grip's blade.
        if (drawing.isEmpty() && s != null && !s.isEmpty() && !com.pglol.aotrpg.Gear.isGear(s) && lastGrip != null && mode == lastMode
            && System.nanoTime() - lastGripAt < 3_000_000L) {
            var id = net.minecraft.registry.Registries.ITEM.getId(s.getItem());
            if (id.getNamespace().equals("dannys-aot") && id.getPath().contains("blade")) owner = lastGrip;
        }
        if (drawing.isEmpty()) {
            outerMode = mode;
            if (mode != null && mode.isFirstPerson() && owner != null && com.pglol.aotrpg.Infusions.of(owner) != null) {
                sampler = new Sampler(owner, mode == net.minecraft.client.render.model.json.ModelTransformationMode.FIRST_PERSON_RIGHT_HAND);
            }
        }
        drawing.push(owner == null ? ItemStack.EMPTY : owner);
        return wrap(owner, base);
    }

    /** The last grip drawn on its own, when, and how: its blade may follow straight after. */
    private static ItemStack lastGrip;
    private static long lastGripAt;
    private static net.minecraft.client.render.model.json.ModelTransformationMode lastMode, outerMode;

    /** Between frames nothing is being drawn: a draw that never finished can't colour anything else. */
    public static void reset() {
        drawing.clear();
        sampler = null;
    }

    public static void exit() {
        ItemStack top = drawing.isEmpty() ? null : drawing.pop();
        if (!drawing.isEmpty()) return;
        if (top != null && !top.isEmpty() && com.pglol.aotrpg.Gear.isGear(top) && com.pglol.aotrpg.Gear.aotWeapon(top)) {
            lastGrip = top;
            lastGripAt = System.nanoTime();
            lastMode = outerMode;
        }
        if (sampler != null) {
            Sampler done = sampler;
            sampler = null;
            if (done.seen > 0) InfusionFx.emit(done);
        }
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
            // How each element sits on the steel: tinted, swallowed in violet dark, pearl-bright, or blacked out.
            int mode = switch (inf) {
                case VOID -> 1;
                case RADIANT -> 2;
                case ECLIPSE -> 3;
                default -> 0;
            };
            // Fire, lightning, frost and light glow in the dark.
            boolean glow = inf == com.pglol.aotrpg.Infusions.Infusion.EMBER || inf == com.pglol.aotrpg.Infusions.Infusion.STORM
                || inf == com.pglol.aotrpg.Infusions.Infusion.FROST || inf == com.pglol.aotrpg.Infusions.Infusion.RADIANT;
            return layer -> new Tinted(base.getBuffer(layer), inf.color, k, ripple, t, mode, glow && r >= 4);
        }
        float k = STRENGTH[r];
        if (r == 5) k += 0.12f * (float) Math.sin(Util.getMeasuringTimeMs() / 260.0);
        final float kk = k;
        final int tint = TINT[r];
        return layer -> new Tinted(base.getBuffer(layer), tint, kk, 0, 0, 0, false);
    }

    /** Passes everything through, pulling each vertex colour toward the tint (keeping its shading). */
    private static final class Tinted implements VertexConsumer {
        private final VertexConsumer inner;
        private final int tint;
        private final float k, ripple, t;
        /** 0 tint, 1 void (dark violet), 2 pearl light, 3 blacked out. */
        private final int mode;
        private final boolean glow;
        private float at;

        Tinted(VertexConsumer inner, int tint, float k, float ripple, float t, int mode, boolean glow) {
            this.inner = inner;
            this.tint = tint;
            this.k = k;
            this.ripple = ripple;
            this.t = t;
            this.mode = mode;
            this.glow = glow;
        }

        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            at = x * 3.1f + y * 4.7f + z * 2.3f;
            if (sampler != null) sampler.offer(x, y, z);
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
            float up = Math.max(0, wave);
            int r, g, b;
            if (mode == 2) {
                // Pearl light: the steel goes near-white, with warm gold running through it.
                float wht = 225 + 30 * luma, m = up * 0.65f;
                float tr2 = wht * (1 - m) + 255 * m, tg2 = wht * (1 - m) + 200 * m, tb2 = wht * 0.95f * (1 - m) + 95 * m;
                r = Math.round(red * (1 - kk) + tr2 * kk);
                g = Math.round(green * (1 - kk) + tg2 * kk);
                b = Math.round(blue * (1 - kk) + tb2 * kk);
            } else if (mode == 3) {
                // Blacked out: the steel swallowed whole, a thin crimson edge catching as the light runs along it.
                float edge = up * up * up * 0.9f;
                r = Math.round(red * 0.04f + luma * 16 + 200 * edge);
                g = Math.round(green * 0.04f + luma * 14 + 18 * edge);
                b = Math.round(blue * 0.04f + luma * 16 + 28 * edge);
            } else {
                boolean dark = mode == 1;
                float bright = dark ? 0.55f + 0.5f * up * (ripple * 4) : 1.35f + ripple * 2 * up;
                // The void swallows the steel's own colour: near-black with violet running through it.
                float keep = dark ? (1 - kk) * 0.35f : 1 - kk;
                r = Math.round(red * keep + tr * luma * bright * kk);
                g = Math.round(green * keep + tg * luma * bright * kk);
                b = Math.round(blue * keep + tb * luma * bright * kk);
            }
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
            // Glowing elements light themselves, day or night.
            if (glow) inner.light(240, 240);
            else inner.light(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            inner.normal(x, y, z);
            return this;
        }
    }
}
