package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;

/**
 * Infused blades: their element is drawn on the steel itself (see BladeMotes), so it stays on the
 * sword however it moves. This only keeps the tint's per-frame state clean.
 */
public final class InfusionFx {
    private InfusionFx() {}

    /** The world's field of view as last drawn (see GameRendererFovMixin); 0 until known. */
    public static volatile double worldFov;

    public static void tick(MinecraftClient mc) {
        RarityTint.reset();
    }

    /** Arm poses were once used to place particles; kept as a no-op for the arms mixin. */
    public static void arms(int id, net.minecraft.client.model.ModelPart r, net.minecraft.client.model.ModelPart l) {}
}
