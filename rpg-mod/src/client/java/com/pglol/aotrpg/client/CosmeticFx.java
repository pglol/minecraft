package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cosmetic effects for every player who wears them: body particles, blade slashes, ODM speed
 * trails (fast flight only, never on horseback), horse gallop trails, and things on or around the
 * head (halo, crown, orbiting planets, wisps, an ember ring). Also the menu catalog.
 */
public final class CosmeticFx {
    private CosmeticFx() {}

    public record Entry(String id, String title, String desc, int color, int color2) { }

    public record Category(String slot, String title, String blurb, List<Entry> entries) { }

    public static final List<Category> CATEGORIES = List.of(
        new Category("trail", "Bullet Trails", "The streak your APG shots leave.", List.of(
            new Entry("trail_tracer", "Tracer", "A clean white tracer. Standard issue.", 0xEDE3C8, 0xFFFFFF),
            new Entry("trail_ember", "Ember", "Sparks and smoke, like a flintlock.", 0xE0782A, 0x6A6A6A),
            new Entry("trail_frost", "Frost", "An icy streak with snowflakes.", 0x8FD8FF, 0xFFFFFF),
            new Entry("trail_lightning", "Thunder", "A crackling electric bolt.", 0xB9A8FF, 0xFFFFFF),
            new Entry("trail_rainbow", "Rainbow", "Every colour at once.", 0xFF6FD8, 0x6FFFD8),
            new Entry("trail_confetti", "Confetti", "Party popper shots.", 0xF2C14E, 0xFF5A7A),
            new Entry("trail_hearts", "Hearts", "Shoot with love.", 0xFF5A7A, 0xFFB0C0),
            new Entry("trail_void", "Void", "A dark smoky rift.", 0x7A3AB8, 0x2A1040))),
        new Category("slash", "Blade Slashes", "The arc your ODM blades leave on a hit.", List.of(
            new Entry("slash_steel", "Steel", "A bright steel sweep.", 0xF4F0E6, 0xB0B0B0),
            new Entry("slash_crimson", "Crimson", "A red crescent.", 0xE03A3A, 0x7A1010),
            new Entry("slash_frost", "Frostbite", "Shards of ice.", 0x8FD8FF, 0xFFFFFF),
            new Entry("slash_ember", "Ember", "A burning edge.", 0xFF8A2A, 0xFFD24A),
            new Entry("slash_void", "Void Rend", "Tears a dark rift.", 0x7A3AB8, 0x1A0A2A),
            new Entry("slash_holy", "Radiant", "A golden flash.", 0xFFE08A, 0xFFFFFF))),
        new Category("body", "Body Particles", "Always drifting around you.", List.of(
            new Entry("body_none", "None", "Nothing at all.", 0x55524A, 0x55524A),
            new Entry("body_embers", "Embers", "Glowing embers rise from you.", 0xFF8A2A, 0xFFD24A),
            new Entry("body_frost", "Frost Aura", "Snowflakes swirl around you.", 0xBFEFFF, 0xFFFFFF),
            new Entry("body_sparkle", "Starlight", "Little stars twinkle around you.", 0xFFF6C0, 0xFFFFFF),
            new Entry("body_petals", "Petals", "Cherry blossoms follow you.", 0xFFB0D0, 0xFF7AA8),
            new Entry("body_soul", "Soul Fire", "Blue soul flames flicker at your feet.", 0x5AD8FF, 0x2A6AB8),
            new Entry("body_void", "Void Mist", "A dark purple mist clings to you.", 0x7A3AB8, 0x2A1040))),
        new Category("odm", "ODM Speed Trails", "When you fly fast on ODM gear (not on horseback).", List.of(
            new Entry("odm_wind", "Wind", "Streaks of air.", 0xEDEDED, 0xBFBFBF),
            new Entry("odm_ember", "Afterburn", "A trail of fire and smoke.", 0xFF8A2A, 0x6A6A6A),
            new Entry("odm_frost", "Frost Wake", "Frost in your wake.", 0x8FD8FF, 0xFFFFFF),
            new Entry("odm_rainbow", "Prism", "A rainbow ribbon.", 0xFF6FD8, 0x6FFFD8),
            new Entry("odm_lightning", "Thunder Dash", "Sparks crackle behind you.", 0xB9A8FF, 0xFFFFFF),
            new Entry("odm_void", "Shadow Step", "You leave shadows behind.", 0x7A3AB8, 0x1A0A2A))),
        new Category("horse", "Horse Trails", "When you ride at a gallop.", List.of(
            new Entry("horse_dust", "Dust", "Kicked-up dust.", 0xB89A6A, 0x8A7A5A),
            new Entry("horse_ember", "Hellfire Hooves", "Flames at every hoofbeat.", 0xFF8A2A, 0xFFD24A),
            new Entry("horse_frost", "Frost Hooves", "Frost where you ride.", 0x8FD8FF, 0xFFFFFF),
            new Entry("horse_petal", "Blossom Gallop", "Petals on the wind.", 0xFFB0D0, 0xFF7AA8),
            new Entry("horse_soul", "Phantom Rider", "Ghostly soul fire.", 0x5AD8FF, 0x2A6AB8))),
        new Category("head", "Head & Floating", "Worn on your head, or orbiting around you.", List.of(
            new Entry("head_none", "None", "Nothing at all.", 0x55524A, 0x55524A),
            new Entry("head_halo", "Halo", "A glowing golden ring.", 0xFFE08A, 0xFFFFFF),
            new Entry("head_crown", "Crown", "A golden crown of spikes.", 0xF2C14E, 0xFFE08A),
            new Entry("head_planets", "Planets", "Little worlds orbit around you.", 0x5A8FD8, 0xE0782A),
            new Entry("head_orbs", "Wisp Orbs", "Glowing wisps circle your head.", 0x9FE3FF, 0xFFFFFF),
            new Entry("head_embers", "Ember Ring", "A ring of fire above you.", 0xFF8A2A, 0xFFD24A))),
        new Category("block", "Block Effects", "When you block a hit with your guard.", List.of(
            new Entry("block_steel", "Steel Sparks", "Sparks off steel.", 0xF4F0E6, 0xFFD24A),
            new Entry("block_frost", "Ice Ward", "Ice bursts from the block.", 0x8FD8FF, 0xFFFFFF),
            new Entry("block_ember", "Fire Ward", "Flames flare out.", 0xFF8A2A, 0xFFD24A),
            new Entry("block_holy", "Radiant Ward", "A burst of light.", 0xFFE08A, 0xFFFFFF),
            new Entry("block_void", "Void Ward", "The hit sinks into a rift.", 0x7A3AB8, 0x2A1040),
            new Entry("block_thunder", "Storm Ward", "Lightning crackles.", 0xB9A8FF, 0xFFFFFF))),
        new Category("clash", "Clash Effects", "When two blades meet at the same moment.", List.of(
            new Entry("clash_steel", "Steel", "A shower of sparks.", 0xF4F0E6, 0xFFD24A),
            new Entry("clash_frost", "Shatter", "Ice shatters between you.", 0x8FD8FF, 0xFFFFFF),
            new Entry("clash_ember", "Inferno", "An explosion of embers.", 0xFF8A2A, 0xFFD24A),
            new Entry("clash_holy", "Radiance", "A blinding flash.", 0xFFE08A, 0xFFFFFF),
            new Entry("clash_void", "Rift", "Space tears open.", 0x7A3AB8, 0x2A1040),
            new Entry("clash_thunder", "Thunderclap", "A crack of thunder.", 0xB9A8FF, 0xFFFFFF),
            new Entry("clash_petal", "Blossom", "Petals burst out.", 0xFFB0D0, 0xFF7AA8))));

    public static Entry entry(String id) {
        for (Category c : CATEGORIES) for (Entry e : c.entries()) if (e.id().equals(id)) return e;
        return null;
    }

    public static Category category(String slot) {
        for (Category c : CATEGORIES) if (c.slot().equals(slot)) return c;
        return CATEGORIES.get(0);
    }

    // ------------------------------------------------------------------ who wears what

    private static final Map<UUID, Map<String, String>> worn = new HashMap<>();

    public static void onWorn(Net.CosmeticsOf msg) {
        Map<String, String> m = new HashMap<>();
        for (String s : msg.worn()) {
            int i = s.indexOf('=');
            if (i > 0) m.put(s.substring(0, i), s.substring(i + 1));
        }
        worn.put(msg.player(), m);
    }

    public static String worn(UUID id, String slot) {
        Map<String, String> m = worn.get(id);
        return m == null ? "" : m.getOrDefault(slot, "");
    }

    public static void clear() {
        worn.clear();
    }

    // ------------------------------------------------------------------ particles

    static Vector3f rgb(int c) {
        return new Vector3f(((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f);
    }

    /** The particles that make up a style (a few kinds, picked at random). */
    static ParticleEffect[] particles(String id) {
        String k = id.contains("_") ? id.substring(id.indexOf('_') + 1) : id;
        return switch (k) {
            case "embers", "ember" -> new ParticleEffect[] {ParticleTypes.FLAME, ParticleTypes.SMALL_FLAME, ParticleTypes.LAVA};
            case "frost" -> new ParticleEffect[] {ParticleTypes.SNOWFLAKE, new DustParticleEffect(rgb(0xBFEFFF), 1f)};
            case "sparkle", "holy" -> new ParticleEffect[] {ParticleTypes.END_ROD, new DustParticleEffect(rgb(0xFFF6C0), 0.9f)};
            case "petals", "petal" -> new ParticleEffect[] {ParticleTypes.CHERRY_LEAVES, new DustParticleEffect(rgb(0xFFB0D0), 1f)};
            case "soul" -> new ParticleEffect[] {ParticleTypes.SOUL_FIRE_FLAME, ParticleTypes.SOUL};
            case "void" -> new ParticleEffect[] {ParticleTypes.REVERSE_PORTAL, new DustParticleEffect(rgb(0x5A2A8A), 1.2f)};
            case "lightning", "thunder" -> new ParticleEffect[] {ParticleTypes.ELECTRIC_SPARK, ParticleTypes.END_ROD};
            case "rainbow" -> new ParticleEffect[] {new DustParticleEffect(rgb(MathHelper.hsvToRgb((Util.getMeasuringTimeMs() % 2000) / 2000f, 0.8f, 1f)), 1.2f)};
            case "crimson" -> new ParticleEffect[] {new DustParticleEffect(rgb(0xE03A3A), 1.2f), ParticleTypes.CRIT};
            case "dust" -> new ParticleEffect[] {ParticleTypes.POOF, new DustParticleEffect(rgb(0xB89A6A), 1.3f)};
            case "wind" -> new ParticleEffect[] {ParticleTypes.CLOUD, new DustParticleEffect(rgb(0xEDEDED), 0.8f)};
            default -> new ParticleEffect[] {ParticleTypes.CRIT, ParticleTypes.ELECTRIC_SPARK};
        };
    }

    private static ParticleEffect pick(ParticleEffect[] set) {
        return set[(int) (Math.random() * set.length)];
    }

    /** A blade slash in a style: an arc of particles across the target. */
    public static void slash(Net.SlashFx fx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        ParticleEffect[] set = fx.style().equals("slash_steel") || fx.style().isEmpty()
            ? new ParticleEffect[] {new DustParticleEffect(rgb(0xF4F0E6), 0.9f), ParticleTypes.CRIT}
            : fx.style().equals("slash_crimson") ? new ParticleEffect[] {new DustParticleEffect(rgb(0xE03A3A), 1.1f), new DustParticleEffect(rgb(0x7A1010), 1f)}
            : particles(fx.style());
        double yaw = Math.toRadians(fx.yaw());
        double rx = Math.cos(yaw), rz = Math.sin(yaw); // the attacker's right
        double tilt = Math.random() < 0.5 ? 1 : -1;
        for (int i = 0; i <= 22; i++) {
            double u = i / 22.0 * 2 - 1;
            double x = fx.x() + rx * u * 1.1, z = fx.z() + rz * u * 1.1, y = fx.y() + u * 0.5 * tilt - (1 - u * u) * 0.2;
            mc.world.addParticle(pick(set), x, y, z, rx * 0.02, 0, rz * 0.02);
        }
        mc.world.addParticle(ParticleTypes.SWEEP_ATTACK, fx.x(), fx.y(), fx.z(), 0, 0, 0);
    }

    /** Each client tick: body particles, speed trails, horse trails, ember rings. */
    public static void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null || mc.isPaused()) return;
        long t = mc.world.getTime();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p.isInvisible() || p.isSpectator() || p.squaredDistanceTo(mc.player) > 64 * 64) continue;
            boolean me = p == mc.player && mc.options.getPerspective().isFirstPerson();
            String body = worn(p.getUuid(), "body");
            if (!body.isEmpty() && !body.equals("body_none") && t % 3 == 0 && !me) {
                ParticleEffect[] set = particles(body);
                double a = Math.random() * Math.PI * 2, r = 0.45 + Math.random() * 0.2;
                mc.world.addParticle(pick(set), p.getX() + Math.cos(a) * r, p.getY() + 0.2 + Math.random() * 1.5, p.getZ() + Math.sin(a) * r,
                    0, 0.02, 0);
            }
            Vec3d v = p.getPos().subtract(p.prevX, p.prevY, p.prevZ);
            double speed = v.length();
            if (p.getVehicle() instanceof AbstractHorseEntity horse) {
                String style = worn(p.getUuid(), "horse");
                double hs = horse.getPos().subtract(horse.prevX, horse.prevY, horse.prevZ).horizontalLength();
                if (!style.isEmpty() && hs > 0.22 && horse.isOnGround()) {
                    ParticleEffect[] set = particles(style);
                    for (int i = 0; i < 2; i++) {
                        mc.world.addParticle(pick(set), horse.getX() + (Math.random() - 0.5) * 0.9, horse.getY() + 0.1,
                            horse.getZ() + (Math.random() - 0.5) * 0.9, -v.x * 0.1, 0.03, -v.z * 0.1);
                    }
                }
            } else if (!p.hasVehicle() && !p.isOnGround() && speed > 0.55) {
                // ODM flight: a trail behind the flier once they are really moving.
                String style = worn(p.getUuid(), "odm");
                if (!style.isEmpty() && !(me && speed < 0.8)) {
                    ParticleEffect[] set = particles(style);
                    int n = speed > 1.2 ? 3 : 2;
                    for (int i = 0; i < n; i++) {
                        double k = i / (double) n;
                        mc.world.addParticle(pick(set), p.getX() - v.x * k, p.getY() + 0.9 - v.y * k, p.getZ() - v.z * k, 0, 0, 0);
                    }
                }
            }
            String head = worn(p.getUuid(), "head");
            if (head.equals("head_embers") && t % 2 == 0 && !me) {
                double a = (t % 40) / 40.0 * Math.PI * 2;
                for (int i = 0; i < 2; i++) {
                    double b = a + i * Math.PI;
                    mc.world.addParticle(ParticleTypes.SMALL_FLAME, p.getX() + Math.cos(b) * 0.45, p.getY() + p.getHeight() + 0.35,
                        p.getZ() + Math.sin(b) * 0.45, 0, 0.005, 0);
                }
            }
        }
    }

    // ------------------------------------------------------------------ head pieces (drawn)

    private static final ItemStack[] PLANETS = {new ItemStack(Items.HEART_OF_THE_SEA), new ItemStack(Items.MAGMA_CREAM), new ItemStack(Items.ENDER_PEARL)};

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        MatrixStack ms = ctx.matrixStack();
        VertexConsumerProvider vc = ctx.consumers();
        if (ms == null || vc == null) return;
        Vec3d cam = ctx.camera().getPos();
        float td = ctx.tickCounter().getTickDelta(true);
        float time = (mc.world.getTime() + td) / 20f;
        for (PlayerEntity p : mc.world.getPlayers()) {
            String head = worn(p.getUuid(), "head");
            if (head.isEmpty() || head.equals("head_none") || head.equals("head_embers") || p.isInvisible()) continue;
            if (p == mc.player && mc.options.getPerspective().isFirstPerson()) continue;
            if (p.squaredDistanceTo(cam) > 48 * 48) continue;
            Vec3d pos = p.getLerpedPos(td);
            double top = pos.y + p.getHeight() + (p.isInSneakingPose() ? -0.1 : 0.05);
            int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
            ms.push();
            ms.translate(pos.x - cam.x, top - cam.y, pos.z - cam.z);
            switch (head) {
                case "head_halo" -> ring(vc.getBuffer(RenderLayer.getLightning()), ms.peek().getPositionMatrix(), 0.1f + (float) Math.sin(time * 2) * 0.03f,
                    0.28f, 0.06f, 0xE0FFE08A);
                case "head_crown" -> crown(vc.getBuffer(RenderLayer.getDebugQuads()), ms, bodyYaw(p, td));
                case "head_planets", "head_orbs" -> {
                    boolean planets = head.equals("head_planets");
                    for (int i = 0; i < 3; i++) {
                        float a = time * (planets ? 0.9f : 1.6f) + i * (float) (Math.PI * 2 / 3);
                        float r = planets ? 0.75f : 0.5f;
                        ms.push();
                        ms.translate(Math.cos(a) * r, (planets ? -0.6 : -0.05) + Math.sin(time * 1.3 + i) * 0.12, Math.sin(a) * r);
                        if (planets) {
                            ms.multiply(RotationAxis.POSITIVE_Y.rotation(time * 2 + i));
                            ms.scale(0.3f, 0.3f, 0.3f);
                            mc.getItemRenderer().renderItem(PLANETS[i], ModelTransformationMode.GROUND, light, OverlayTexture.DEFAULT_UV, ms, vc,
                                mc.world, p.getId() * 5 + i);
                        } else {
                            ms.multiply(mc.getEntityRenderDispatcher().getRotation());
                            orb(vc.getBuffer(RenderLayer.getLightning()), ms.peek().getPositionMatrix(), 0.07f, 0xC09FE3FF);
                        }
                        ms.pop();
                    }
                }
                default -> { }
            }
            ms.pop();
        }
    }

    private static float bodyYaw(PlayerEntity p, float td) {
        return MathHelper.lerpAngleDegrees(td, p.prevBodyYaw, p.bodyYaw);
    }

    /** A flat glowing ring (both faces). */
    private static void ring(VertexConsumer vc, Matrix4f m, float y, float r, float w, int argb) {
        int seg = 28;
        for (int i = 0; i < seg; i++) {
            double a0 = i * Math.PI * 2 / seg, a1 = (i + 1) * Math.PI * 2 / seg;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float ri = r - w, ro = r;
            vc.vertex(m, c0 * ri, y, s0 * ri).color(argb);
            vc.vertex(m, c0 * ro, y, s0 * ro).color(argb);
            vc.vertex(m, c1 * ro, y, s1 * ro).color(argb);
            vc.vertex(m, c1 * ri, y, s1 * ri).color(argb);
            vc.vertex(m, c1 * ri, y, s1 * ri).color(argb);
            vc.vertex(m, c1 * ro, y, s1 * ro).color(argb);
            vc.vertex(m, c0 * ro, y, s0 * ro).color(argb);
            vc.vertex(m, c0 * ri, y, s0 * ri).color(argb);
        }
    }

    /** A camera-facing glowing square. */
    private static void orb(VertexConsumer vc, Matrix4f m, float r, int argb) {
        vc.vertex(m, -r, -r, 0).color(argb);
        vc.vertex(m, -r, r, 0).color(argb);
        vc.vertex(m, r, r, 0).color(argb);
        vc.vertex(m, r, -r, 0).color(argb);
        vc.vertex(m, r, -r, 0).color(argb);
        vc.vertex(m, r, r, 0).color(argb);
        vc.vertex(m, -r, r, 0).color(argb);
        vc.vertex(m, -r, -r, 0).color(argb);
    }

    /** A crown: a gold band with five spikes. */
    private static void crown(VertexConsumer vc, MatrixStack ms, float yaw) {
        ms.push();
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-yaw));
        Matrix4f m = ms.peek().getPositionMatrix();
        int gold = 0xFFF2C14E, dark = 0xFFB8862A;
        float r = 0.27f, y0 = -0.08f, y1 = 0.02f;
        int seg = 10;
        for (int i = 0; i < seg; i++) {
            double a0 = i * Math.PI * 2 / seg, a1 = (i + 1) * Math.PI * 2 / seg;
            float x0 = (float) Math.cos(a0) * r, z0 = (float) Math.sin(a0) * r, x1 = (float) Math.cos(a1) * r, z1 = (float) Math.sin(a1) * r;
            int col = i % 2 == 0 ? gold : dark;
            quad2(vc, m, x0, y0, z0, x1, y0, z1, x1, y1, z1, x0, y1, z0, col);
            if (i % 2 == 0) {
                float xm = (x0 + x1) / 2, zm = (z0 + z1) / 2;
                quad2(vc, m, x0, y1, z0, x1, y1, z1, xm, y1 + 0.1f, zm, xm, y1 + 0.1f, zm, gold);
            }
        }
        ms.pop();
    }

    private static void quad2(VertexConsumer vc, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz,
                              float cx, float cy, float cz, float dx, float dy, float dz, int col) {
        vc.vertex(m, ax, ay, az).color(col);
        vc.vertex(m, bx, by, bz).color(col);
        vc.vertex(m, cx, cy, cz).color(col);
        vc.vertex(m, dx, dy, dz).color(col);
        vc.vertex(m, dx, dy, dz).color(col);
        vc.vertex(m, cx, cy, cz).color(col);
        vc.vertex(m, bx, by, bz).color(col);
        vc.vertex(m, ax, ay, az).color(col);
    }
}
