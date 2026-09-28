package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Effects felt rather than seen floating around you (the potion swirls are gone): a surge at the
 * edges of your view when one takes hold, in its colour, with a shape of its own (wind for speed,
 * a thump for strength, drifting motes for slow falling and jump). And a long drop feels like one:
 * wind streaking past and the view shaking a little the faster you fall.
 */
public final class EffectFx {
    private EffectFx() {}

    private record Surge(RegistryEntry<StatusEffect> effect, int color, long at) { }

    private static final Map<RegistryEntry<StatusEffect>, Integer> seen = new HashMap<>();
    private static final List<Surge> surges = new ArrayList<>();
    /** How hard you're falling, 0..1 (smoothed). */
    private static float fall;
    private static final long SURGE_MS = 1100;

    public static void tick(MinecraftClient mc) {
        if (mc.player == null) {
            seen.clear();
            fall = 0;
            return;
        }
        Map<RegistryEntry<StatusEffect>, Integer> now = new HashMap<>();
        for (StatusEffectInstance e : mc.player.getStatusEffects()) {
            now.put(e.getEffectType(), e.getAmplifier());
            Integer before = seen.get(e.getEffectType());
            if ((before == null || e.getAmplifier() > before) && e.getEffectType().value().isBeneficial()) {
                surges.add(new Surge(e.getEffectType(), e.getEffectType().value().getColor(), Util.getMeasuringTimeMs()));
            }
        }
        seen.clear();
        seen.putAll(now);
        long t = Util.getMeasuringTimeMs();
        surges.removeIf(s -> t - s.at() > SURGE_MS);
        // A straight, fast drop (not an ODM swing, which is mostly sideways).
        var v = mc.player.getVelocity();
        double side = Math.sqrt(v.x * v.x + v.z * v.z);
        boolean dropping = !mc.player.isOnGround() && !mc.player.isTouchingWater() && !mc.player.getAbilities().flying
            && !mc.player.isFallFlying() && !mc.player.hasStatusEffect(StatusEffects.SLOW_FALLING)
            && v.y < -0.42 && -v.y > side * 1.1 && mc.player.fallDistance > 2.2f;
        float target = dropping ? MathHelper.clamp((float) (-v.y - 0.42) / 1.5f + 0.35f, 0, 1) : 0;
        // Kicks in quickly as the drop starts, eases off when you land.
        fall += (target - fall) * (target > fall ? 0.45f : 0.3f);
        if (fall < 0.01f) fall = 0;
    }

    /** The view shaking on a long drop (after the hurt tilt). */
    public static void shake(MatrixStack ms, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (fall <= 0 || mc.player == null || !mc.options.getPerspective().isFirstPerson()) return;
        float t = (mc.player.age + tickDelta);
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float) Math.sin(t * 1.7) * 1.2f * fall));
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees((float) Math.sin(t * 2.3 + 1) * 0.7f * fall));
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        long t = Util.getMeasuringTimeMs();
        for (Surge s : surges) {
            float k = (t - s.at()) / (float) SURGE_MS;
            // Rises fast, fades slow.
            float a = k < 0.15f ? k / 0.15f : 1 - (k - 0.15f) / 0.85f;
            int col = s.color() & 0xFFFFFF;
            edges(c, w, h, (int) (110 * a), col, 26 + (int) (20 * k));
            if (is(s, StatusEffects.SPEED) || is(s, StatusEffects.DOLPHINS_GRACE)) {
                // Wind: streaks rushing past toward the edges.
                Random r = new Random(s.at());
                for (int i = 0; i < 26; i++) {
                    boolean left = i % 2 == 0;
                    int y = r.nextInt(h), len = 30 + r.nextInt(60);
                    int x = left ? (int) (w * 0.3 - k * w * 0.35 - r.nextInt(40)) : (int) (w * 0.7 + k * w * 0.35 + r.nextInt(40));
                    c.fill(x, y, x + (left ? -len : len), y + 1, ((int) (150 * a) << 24) | 0xFFFFFF);
                }
            } else if (is(s, StatusEffects.STRENGTH) || is(s, StatusEffects.RESISTANCE) || is(s, StatusEffects.ABSORPTION)) {
                // A thump: a second, tighter pulse right behind the first.
                float b = Math.max(0, 1 - Math.abs(k - 0.3f) / 0.12f);
                edges(c, w, h, (int) (140 * b), col, 14);
            } else if (is(s, StatusEffects.SLOW_FALLING) || is(s, StatusEffects.JUMP_BOOST) || is(s, StatusEffects.LEVITATION)) {
                // Feather-light: motes drifting up the sides.
                Random r = new Random(s.at());
                for (int i = 0; i < 30; i++) {
                    int x = r.nextBoolean() ? r.nextInt(w / 6) : w - r.nextInt(w / 6);
                    int y = (int) (h - (r.nextInt(h) + k * h * 0.5f) % h);
                    c.fill(x, y, x + 2, y + 2, ((int) (190 * a) << 24) | col);
                }
            }
        }
        if (fall > 0 && mc.options.getPerspective().isFirstPerson()) {
            // Falling: wind streaking up past you, and the edges going pale.
            edges(c, w, h, (int) (70 * fall), 0xDCE8F0, 34);
            Random r = new Random(mc.player.age / 2);
            for (int i = 0; i < (int) (40 * fall); i++) {
                int x = r.nextBoolean() ? r.nextInt(w / 4) : w - r.nextInt(w / 4);
                int y = r.nextInt(h), len = 20 + r.nextInt(50);
                c.fill(x, y - len, x + 1, y, ((int) (120 * fall) << 24) | 0xFFFFFF);
            }
        }
    }

    private static boolean is(Surge s, RegistryEntry<StatusEffect> e) {
        return s.effect().value() == e.value();
    }

    /** A soft band of colour around the screen's edge, alpha at the rim, fading inward over depth pixels. */
    private static void edges(DrawContext c, int w, int h, int alpha, int rgb, int depth) {
        if (alpha <= 2) return;
        int a = Math.min(255, alpha) << 24;
        c.fillGradient(0, 0, w, depth, a | rgb, rgb);
        c.fillGradient(0, h - depth, w, h, rgb, a | rgb);
        for (int i = 0; i < depth; i += 2) {
            int al = (int) (Math.min(255, alpha) * (1 - i / (float) depth));
            c.fill(i, 0, i + 2, h, (al << 24) | rgb);
            c.fill(w - i - 2, 0, w - i, h, (al << 24) | rgb);
        }
    }
}
