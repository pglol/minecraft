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
        long now = net.minecraft.util.Util.getMeasuringTimeMs();
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p.isInvisible() || p.isSpectator() || p.squaredDistanceTo(mc.player) > 64 * 64) continue;
            boolean own = p == mc.player && mc.options.getPerspective().isFirstPerson();
            // Your own hands in first person come from their drawn position (see emit).
            if (!own) {
                blade(mc, p, p.getMainHandStack(), p.getMainArm() == Arm.RIGHT, t, now);
                blade(mc, p, p.getOffHandStack(), p.getMainArm() != Arm.RIGHT, t, now);
            }
            com.pglol.aotrpg.Net.SheathState st = ClientState.sheaths.get(p.getUuid());
            if (st != null && !own && !p.hasVehicle()) {
                boolean two = !st.a().isEmpty() && !st.b().isEmpty();
                if (!st.a().isEmpty()) sheathed(mc, p, st.a(), two ? 135 : 200, 0, t);
                if (!st.b().isEmpty()) sheathed(mc, p, st.b(), two ? 225 : 200, 1, t);
            }
        }
        if (t % 200 == 0) {
            arms.entrySet().removeIf(e -> now - (long) e.getValue()[12] > 5000);
            lastEmit.clear();
        }
    }

    private static int every(ItemStack s) {
        String rar = Gear.data(s).getString("rarity");
        return "MYTHIC".equals(rar) ? 1 : "LEGENDARY".equals(rar) ? 2 : 3;
    }

    /** A held blade seen from outside: along the arm as it's actually posed (else a rough guess). */
    private static void blade(MinecraftClient mc, AbstractClientPlayerEntity p, ItemStack s, boolean right, long t, long now) {
        Infusion inf = Infusions.of(s);
        if (inf == null || t % every(s) != 0) return;
        boolean mythic = "MYTHIC".equals(Gear.data(s).getString("rarity"));
        float[] a = arms.get(p.getId());
        Vec3d[] line = a != null && now - (long) a[12] < 500 ? armLine(p, a, right) : held(p, right);
        for (int i = 0; i < (mythic ? 2 : 1); i++) {
            double f = 0.15 + 0.85 * Math.sqrt(R.nextDouble());
            Vec3d at = line[0].lerp(line[1], f);
            spawn(mc, inf, mythic, at.add(R.nextGaussian() * 0.02, R.nextGaussian() * 0.02, R.nextGaussian() * 0.02));
        }
    }

    /** Each player's arms as last drawn: right pitch, yaw, roll, pivot x y z, then the left, then when. */
    private static final java.util.Map<Integer, float[]> arms = new java.util.HashMap<>();

    public static void arms(int id, net.minecraft.client.model.ModelPart r, net.minecraft.client.model.ModelPart l) {
        arms.put(id, new float[] {r.pitch, r.yaw, r.roll, r.pivotX, r.pivotY, r.pivotZ, l.pitch, l.yaw, l.roll, l.pivotX, l.pivotY, l.pivotZ,
            (float) 0});
        // The time goes in as a long's worth of float would lose precision: keep ms since start instead.
        arms.get(id)[12] = net.minecraft.util.Util.getMeasuringTimeMs();
    }

    /**
     * From the fist out along the blade, in the world: the arm's own rotation about its shoulder,
     * then the player model's frame (flipped, scaled, turned to the body's facing).
     */
    private static Vec3d[] armLine(AbstractClientPlayerEntity p, float[] a, boolean right) {
        int o = right ? 0 : 6;
        org.joml.Quaternionf q = new org.joml.Quaternionf().rotationZYX(a[o + 2], a[o + 1], a[o]);
        org.joml.Vector3f pivot = new org.joml.Vector3f(a[o + 3], a[o + 4], a[o + 5]);
        // The fist (where the item is held), and the way a held blade points: out of the fist, a little up.
        org.joml.Vector3f fist = q.transform(new org.joml.Vector3f(right ? -1 : 1, 10, -2)).add(pivot);
        org.joml.Vector3f dir = q.transform(new org.joml.Vector3f(0, -0.35f, -1).normalize());
        org.joml.Vector3f tip = new org.joml.Vector3f(dir).mul(22).add(fist);
        return new Vec3d[] {model(p, fist), model(p, tip)};
    }

    /** A point in the player model's pixels to the world. */
    private static Vec3d model(AbstractClientPlayerEntity p, org.joml.Vector3f m) {
        double k = 0.9375;
        double x = -(m.x / 16.0) * k, y = -((m.y / 16.0) - 1.501) * k, z = (m.z / 16.0) * k;
        double th = Math.toRadians(180 - p.bodyYaw);
        double wx = x * Math.cos(th) + z * Math.sin(th), wz = -x * Math.sin(th) + z * Math.cos(th);
        return p.getPos().add(wx, y, wz);
    }

    /** Last game tick each first-person blade gave off particles. */
    private static final java.util.Map<Long, Long> lastEmit = new java.util.HashMap<>();

    /**
     * Your own blade in first person, as it finishes drawing: its drawn position anchors the line
     * that runs up the blade on screen, so the element follows the sword through swings and blocks.
     */
    public static void emit(RarityTint.Sampler smp) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.isPaused() || mc.currentScreen != null) return;
        Infusion inf = Infusions.of(smp.stack);
        if (inf == null) return;
        long now = mc.world.getTime();
        long key = smp.right ? 1 : 0;
        Long last = lastEmit.get(key);
        if (last != null && last == now) return;
        lastEmit.put(key, now);
        if (now % every(smp.stack) != 0) return;
        boolean mythic = "MYTHIC".equals(Gear.data(smp.stack).getString("rarity"));
        var cam = mc.gameRenderer.getCamera();
        Vec3d eye = cam.getPos();
        double wf = worldFov > 1 ? worldFov : mc.options.getFov().getValue();
        float spread = (float) (Math.tan(Math.toRadians(wf) / 2) / Math.tan(Math.toRadians(70) / 2));
        // Where the grip is drawn right now (the middle of what was drawn of it).
        float cx = 0, cy = 0, cz = 0;
        int n = Math.min(smp.seen, smp.picks.length);
        for (int i = 0; i < n; i++) {
            cx += smp.picks[i][0];
            cy += smp.picks[i][1];
            cz += smp.picks[i][2];
        }
        org.joml.Vector3f v = new org.joml.Vector3f(cx / n * spread, cy / n * spread, cz / n);
        cam.getRotation().transform(v);
        Vec3d grip = eye.add(v.x, v.y, v.z);
        Vec3d[] line = firstPerson(mc, smp.right);
        Vec3d along = line[1].subtract(line[0]);
        for (int i = 0; i < (mythic ? 2 : 1); i++) {
            double f = 0.12 + 0.88 * Math.sqrt(R.nextDouble());
            Vec3d at = grip.add(along.multiply(f));
            spawn(mc, inf, mythic, at.add(R.nextGaussian() * 0.01, R.nextGaussian() * 0.01, R.nextGaussian() * 0.01));
        }
    }

    /** Along a grip sheathed on the back, using the same frame SheathRender draws it in. */
    private static void sheathed(MinecraftClient mc, AbstractClientPlayerEntity p, ItemStack s, float angle, int i, long t) {
        Infusion inf = Infusions.of(s);
        if (inf == null || t % 3 != 0) return;
        boolean mythic = "MYTHIC".equals(Gear.data(s).getString("rarity"));
        boolean crouch = p.isInSneakingPose();
        double armored = p.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).isEmpty() ? 0.19 : 0.24;
        // Down the blade half, below the crossing (the handles are up over the shoulders).
        double f = 0.05 + R.nextDouble() * 0.75;
        double a = Math.toRadians(angle);
        double lx = -Math.sin(a) * f, ly = 1.15 + Math.cos(a) * f - (crouch ? 0.18 : 0), lz = armored + 0.012 * i + 0.03;
        double th = Math.toRadians(180 - p.bodyYaw);
        double wx = lx * Math.cos(th) + lz * Math.sin(th), wz = -lx * Math.sin(th) + lz * Math.cos(th);
        Vec3d at = p.getPos().add(wx, ly, wz);
        spawn(mc, inf, mythic && R.nextInt(2) == 0, at);
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
        Vec3d dir = fwd.multiply(0.8).add(0, 0.45, 0).add(rgt.multiply(side * -0.05)).normalize();
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
