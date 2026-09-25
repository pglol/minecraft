package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/** Draws a shot's trail in particles, in the shooter's chosen style. */
public final class Trails {
    private Trails() {}

    private static Vector3f rgb(int c) {
        return new Vector3f(((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f);
    }

    private static boolean wasDown;

    /**
     * The APG gun fires on left click, which the server never sees as an item use, so the
     * client notices the click, draws its own trail at once and tells the server for others.
     */
    public static void tickShooting(MinecraftClient mc) {
        boolean down = mc.options.attackKey.isPressed() && mc.currentScreen == null;
        boolean fired = down && !wasDown;
        wasDown = down;
        if (!fired || mc.player == null || mc.world == null) return;
        var id = net.minecraft.registry.Registries.ITEM.getId(mc.player.getMainHandStack().getItem());
        if (!id.getNamespace().equals("dannys-aot") || !id.getPath().equals("apg_gun")) return;
        Vec3d eye = mc.player.getEyePos(), dir = mc.player.getRotationVec(1f);
        var hit = mc.player.raycast(96, 1f, false);
        Vec3d to = hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS ? eye.add(dir.multiply(96)) : hit.getPos();
        // Start at the barrel: a little ahead, right and below the eye.
        Vec3d right = dir.crossProduct(new Vec3d(0, 1, 0)).normalize();
        if (mc.options.getPerspective().isFirstPerson()) right = right.multiply(0.25);
        else right = Vec3d.ZERO;
        Vec3d start = eye.add(dir.multiply(0.8)).add(right).add(0, -0.25, 0);
        spawn(new Net.Trail(ClientState.trail, start.x, start.y, start.z, to.x, to.y, to.z));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new Net.ShotFired());
    }

    public static void spawn(Net.Trail t) {
        ClientWorld w = MinecraftClient.getInstance().world;
        if (w == null) return;
        Vec3d a = new Vec3d(t.x0(), t.y0(), t.z0()), b = new Vec3d(t.x1(), t.y1(), t.z1());
        Vec3d d = b.subtract(a);
        double len = d.length();
        if (len < 0.1 || len > 200) return;
        int steps = (int) Math.min(240, len * 3);
        var r = w.random;
        String style = t.style();
        for (int i = 0; i <= steps; i++) {
            double f = i / (double) steps;
            Vec3d p = a.add(d.multiply(f));
            double jx = (r.nextDouble() - 0.5) * 0.05, jy = (r.nextDouble() - 0.5) * 0.05, jz = (r.nextDouble() - 0.5) * 0.05;
            ParticleEffect e;
            switch (style) {
                case "trail_ember" -> {
                    e = i % 3 == 0 ? ParticleTypes.SMOKE : new DustParticleEffect(rgb(r.nextBoolean() ? 0xFF8A2A : 0xFFC04A), 0.7f);
                    if (r.nextInt(12) == 0) w.addParticle(ParticleTypes.SMALL_FLAME, p.x, p.y, p.z, 0, 0.01, 0);
                }
                case "trail_frost" -> {
                    e = new DustParticleEffect(rgb(0x9FE2FF), 0.8f);
                    if (r.nextInt(8) == 0) w.addParticle(ParticleTypes.SNOWFLAKE, p.x, p.y, p.z, jx, jy, jz);
                }
                case "trail_lightning" -> {
                    // A zigzag bolt: offset sideways in a jagged pattern.
                    double zig = Math.sin(f * 40) * 0.12;
                    p = p.add(zig, Math.cos(f * 33) * 0.1, -zig);
                    e = new DustParticleEffect(rgb(r.nextInt(3) == 0 ? 0xFFFFFF : 0xB9A8FF), 0.6f);
                    if (r.nextInt(20) == 0) w.addParticle(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, jx * 4, jy * 4, jz * 4);
                }
                case "trail_rainbow" -> e = new DustParticleEffect(rgb(net.minecraft.util.math.MathHelper.hsvToRgb((float) (f * 2 % 1), 0.85f, 1f)), 0.9f);
                case "trail_confetti" -> {
                    int[] cols = {0xF2C14E, 0x5BD35B, 0x55C8FF, 0xFF5A7A, 0xD070FF};
                    e = new DustParticleEffect(rgb(cols[r.nextInt(cols.length)]), 1.0f);
                    if (i == steps) for (int k = 0; k < 12; k++) {
                        w.addParticle(new DustParticleEffect(rgb(cols[r.nextInt(cols.length)]), 1.2f), p.x, p.y, p.z,
                            (r.nextDouble() - 0.5) * 0.3, r.nextDouble() * 0.3, (r.nextDouble() - 0.5) * 0.3);
                    }
                }
                case "trail_hearts" -> {
                    e = new DustParticleEffect(rgb(0xFF7A9A), 0.6f);
                    if (i % 10 == 0) w.addParticle(ParticleTypes.HEART, p.x, p.y, p.z, 0, 0.02, 0);
                }
                case "trail_void" -> {
                    e = i % 2 == 0 ? ParticleTypes.PORTAL : new DustParticleEffect(rgb(0x2A0A40), 1.1f);
                    if (r.nextInt(10) == 0) w.addParticle(ParticleTypes.SQUID_INK, p.x, p.y, p.z, 0, 0, 0);
                }
                default -> e = new DustParticleEffect(rgb(0xFFF2D0), 0.45f);
            }
            w.addParticle(e, true, p.x, p.y, p.z, jx, jy, jz);
        }
        // Muzzle flash and impact puff for every style.
        w.addParticle(ParticleTypes.FLASH, a.x, a.y, a.z, 0, 0, 0);
        w.addParticle(ParticleTypes.CRIT, b.x, b.y, b.z, 0, 0.05, 0);
        for (int k = 0; k < 4; k++) w.addParticle(ParticleTypes.POOF, b.x, b.y, b.z, (r.nextDouble() - 0.5) * 0.05, 0.02, (r.nextDouble() - 0.5) * 0.05);
    }
}
