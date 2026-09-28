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
        if (mc.world == null || mc.player == null || mc.isPaused()) return;
        long t = mc.world.getTime();
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p.isInvisible() || p.isSpectator() || p.squaredDistanceTo(mc.player) > 64 * 64) continue;
            boolean own = p == mc.player && mc.options.getPerspective().isFirstPerson();
            if (own && mc.currentScreen != null) continue;
            blade(mc, p, p.getMainHandStack(), p.getMainArm() == Arm.RIGHT, own, t);
            blade(mc, p, p.getOffHandStack(), p.getMainArm() != Arm.RIGHT, own, t);
        }
    }

    private static void blade(MinecraftClient mc, AbstractClientPlayerEntity p, ItemStack s, boolean right, boolean own, long t) {
        Infusion inf = Infusions.of(s);
        if (inf == null) return;
        boolean mythic = "MYTHIC".equals(Gear.data(s).getString("rarity"));
        boolean legendary = mythic || "LEGENDARY".equals(Gear.data(s).getString("rarity"));
        int every = mythic ? 1 : legendary ? 2 : 3;
        if (t % every != 0) return;
        Vec3d[] line = own ? firstPerson(mc, right) : held(p, right);
        int n = mythic ? 3 : 2;
        for (int i = 0; i < n; i++) {
            // Mostly out along the steel, not the grip.
            double f = 0.2 + 0.8 * Math.sqrt(R.nextDouble());
            Vec3d at = line[0].lerp(line[1], f);
            double j = own ? 0.01 : 0.02;
            spawn(mc, inf, mythic, at.add(R.nextGaussian() * j, R.nextGaussian() * j, R.nextGaussian() * j));
        }
    }

    /** Where your own blade shows on screen in first person, as a line in the world just in front of you. */
    private static Vec3d[] firstPerson(MinecraftClient mc, boolean right) {
        var cam = mc.gameRenderer.getCamera();
        Vec3d eye = cam.getPos();
        float yaw = cam.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        Vec3d fwd = Vec3d.fromPolar(cam.getPitch(), cam.getYaw());
        Vec3d rgt = new Vec3d(-MathHelper.cos(yaw), 0, -MathHelper.sin(yaw));
        Vec3d up = rgt.crossProduct(fwd).normalize();
        // Hands are drawn with their own fixed 70 degrees; the world (and these particles) with the
        // setting plus running and speed widening it. Screen spots below are where the blades show
        // on screen, so place them with the world's field of view as it is right now.
        double fov = Math.toRadians(worldFov > 1 ? worldFov : mc.options.getFov().getValue());
        double hh = Math.tan(fov / 2), hw = hh * mc.getWindow().getFramebufferWidth() / Math.max(1.0, mc.getWindow().getFramebufferHeight());
        double side = right ? 1 : -1, z = 1.0;
        // Screen spots (x -1..1, y -1..1): the grip low in the corner, the blade running up to the top edge.
        Vec3d a = eye.add(fwd.multiply(z)).add(rgt.multiply(side * 0.84 * hw * z)).add(up.multiply(-0.8 * hh * z));
        Vec3d b = eye.add(fwd.multiply(z)).add(rgt.multiply(side * 0.6 * hw * z)).add(up.multiply(0.95 * hh * z));
        return new Vec3d[] {a, b};
    }

    /** Hand to tip for a player seen from outside. */
    private static Vec3d[] held(AbstractClientPlayerEntity p, boolean right) {
        float by = p.bodyYaw * MathHelper.RADIANS_PER_DEGREE;
        Vec3d fwd = new Vec3d(-MathHelper.sin(by), 0, MathHelper.cos(by));
        Vec3d rgt = new Vec3d(-MathHelper.cos(by), 0, -MathHelper.sin(by));
        double side = right ? 1 : -1;
        // The hand hangs at the side; the blade runs forward and up from it, not out sideways.
        Vec3d hand = p.getPos().add(0, p.isInSneakingPose() ? 0.55 : 0.7, 0).add(rgt.multiply(side * 0.3)).add(fwd.multiply(0.15));
        Vec3d dir = fwd.multiply(0.6).add(0, 0.8, 0).add(rgt.multiply(side * -0.05)).normalize();
        return new Vec3d[] {hand, hand.add(dir.multiply(1.25))};
    }

    private static void spawn(MinecraftClient mc, Infusion inf, boolean mythic, Vec3d at) {
        double vy = 0.004;
        ParticleEffect fx = switch (inf) {
            case FROST -> ParticleTypes.SNOWFLAKE;
            case EMBER -> R.nextInt(3) == 0 ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME;
            case VOID -> R.nextInt(3) == 0 ? new DustParticleEffect(new Vector3f(0.05f, 0f, 0.1f), 0.7f) : ParticleTypes.REVERSE_PORTAL;
            case STORM -> ParticleTypes.ELECTRIC_SPARK;
            case VENOM -> new DustParticleEffect(new Vector3f(0.4f, 0.9f, 0.3f), 0.6f);
            case RADIANT -> ParticleTypes.END_ROD;
            case BLOOD -> new DustParticleEffect(new Vector3f(0.65f, 0.02f, 0.05f), 0.7f);
        };
        if (inf == Infusion.BLOOD || inf == Infusion.VENOM) vy = -0.02;
        mc.world.addParticle(fx, at.x, at.y, at.z, R.nextGaussian() * 0.004, vy, R.nextGaussian() * 0.004);
        if (mythic && inf == Infusion.FROST && R.nextInt(2) == 0) mc.world.addParticle(ParticleTypes.WHITE_ASH, at.x, at.y, at.z, 0, 0, 0);
        if (mythic && inf == Infusion.VOID && R.nextInt(3) == 0) mc.world.addParticle(ParticleTypes.SQUID_INK, at.x, at.y, at.z, 0, 0.005, 0);
        if (mythic && inf == Infusion.STORM && R.nextInt(6) == 0) mc.world.addParticle(ParticleTypes.FLASH, at.x, at.y, at.z, 0, 0, 0);
    }
}
