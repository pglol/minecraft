package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Gear;
import com.pglol.aotrpg.Infusions;
import com.pglol.aotrpg.Infusions.Infusion;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.Arm;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Vector3f;

/**
 * Infused blades give off their element along the whole blade, hilt to tip: drawn here on each
 * client, so your own blades shed it where you see them in first person, and everyone else's do
 * where they hold them.
 */
public final class InfusionFx {
    private InfusionFx() {}

    private static final Random R = Random.create();
    /** The world's field of view as last drawn (see GameRendererFovMixin); 0 until known. */
    public static volatile double worldFov;

    public static void tick(MinecraftClient mc) {
        RarityTint.reset();
        if (mc.world != null && mc.world.getTime() % 200 == 0) lastEmit.clear();
    }

    /** Last game tick each drawn blade gave off particles (by the stack drawn and first person or not). */
    private static final java.util.Map<Long, Long> lastEmit = new java.util.HashMap<>();

    /**
     * Called as an infused blade finishes drawing, with points taken from its own geometry: a few
     * of them become particles in the world, at most once a game tick per blade.
     */
    public static void emit(RarityTint.Sampler smp) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.isPaused()) return;
        Infusion inf = Infusions.of(smp.stack);
        if (inf == null) return;
        long now = mc.world.getTime();
        long key = ((long) System.identityHashCode(smp.stack) << 1) | (smp.firstPerson ? 1 : 0);
        Long last = lastEmit.get(key);
        if (last != null && last == now) return;
        lastEmit.put(key, now);
        String rar = Gear.data(smp.stack).getString("rarity");
        boolean mythic = "MYTHIC".equals(rar), legendary = mythic || "LEGENDARY".equals(rar);
        if (now % (mythic ? 1 : legendary ? 2 : 3) != 0) return;
        var cam = mc.gameRenderer.getCamera();
        Vec3d eye = cam.getPos();
        // Your own hands are drawn in view space with a fixed 70 degree view; the world with yours.
        float spread = 1f;
        if (smp.firstPerson) {
            double wf = worldFov > 1 ? worldFov : mc.options.getFov().getValue();
            spread = (float) (Math.tan(Math.toRadians(wf) / 2) / Math.tan(Math.toRadians(70) / 2));
        }
        int n = Math.min(smp.seen, mythic ? 2 : 1);
        for (int i = 0; i < n; i++) {
            float[] p = smp.picks[R.nextInt(Math.min(smp.seen, smp.picks.length))];
            Vec3d at;
            if (smp.firstPerson) {
                org.joml.Vector3f v = new org.joml.Vector3f(p[0] * spread, p[1] * spread, p[2]);
                cam.getRotation().transform(v);
                at = eye.add(v.x, v.y, v.z);
            } else {
                at = eye.add(p[0], p[1], p[2]);
            }
            spawn(mc, inf, mythic, at);
        }
    }

    private static void spawn(MinecraftClient mc, Infusion inf, boolean mythic, Vec3d at) {
        double vy = 0.004;
        ParticleEffect fx = switch (inf) {
            case FROST -> ParticleTypes.SNOWFLAKE;
            case EMBER -> R.nextInt(3) == 0 ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME;
            case VOID -> R.nextInt(3) == 0 ? new DustParticleEffect(new Vector3f(0.05f, 0f, 0.1f), 0.7f) : ParticleTypes.REVERSE_PORTAL;
            case STORM -> ParticleTypes.ELECTRIC_SPARK;
            case VENOM -> new DustParticleEffect(new Vector3f(0.4f, 0.9f, 0.3f), 0.6f);
            // Soft gold motes that fade fast; End Rods linger and glow, so only the odd one as a glint.
            case RADIANT -> R.nextInt(8) == 0 ? ParticleTypes.END_ROD : new DustParticleEffect(new Vector3f(1f, 0.93f, 0.62f), 0.45f);
            case BLOOD -> new DustParticleEffect(new Vector3f(0.65f, 0.02f, 0.05f), 0.7f);
        };
        if (inf == Infusion.BLOOD || inf == Infusion.VENOM) vy = -0.02;
        if (fx == ParticleTypes.END_ROD) vy = 0;
        mc.world.addParticle(fx, at.x, at.y, at.z, R.nextGaussian() * 0.004, vy, R.nextGaussian() * 0.004);
        if (mythic && inf == Infusion.FROST && R.nextInt(2) == 0) mc.world.addParticle(ParticleTypes.WHITE_ASH, at.x, at.y, at.z, 0, 0, 0);
        if (mythic && inf == Infusion.VOID && R.nextInt(3) == 0) mc.world.addParticle(ParticleTypes.SQUID_INK, at.x, at.y, at.z, 0, 0.005, 0);
        if (mythic && inf == Infusion.STORM && R.nextInt(6) == 0) mc.world.addParticle(ParticleTypes.FLASH, at.x, at.y, at.z, 0, 0, 0);
    }
}
