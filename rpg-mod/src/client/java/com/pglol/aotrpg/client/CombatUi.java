package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Combat feedback on the client: floating damage numbers over what you hit, the XP bar under the
 * hotbar (with "+XP" popups), the guard key, and block / clash / broken-guard effects.
 */
public final class CombatUi {
    private CombatUi() {}

    public static KeyBinding guardKey;
    private static boolean guardSent;

    // ------------------------------------------------------------------ guard key

    public static void tick(MinecraftClient mc) {
        boolean want = guardKey != null && guardKey.isPressed() && mc.currentScreen == null && mc.player != null;
        if (want != guardSent) {
            guardSent = want;
            ClientPlayNetworking.send(new Net.GuardKey(want));
        }
    }

    public static boolean guarding() {
        return guardSent;
    }

    // ------------------------------------------------------------------ damage numbers

    private record Num(Vec3d at, String text, int color, float scale, long born) { }

    private static final List<Num> nums = new ArrayList<>();

    public static void onHit(Net.HitMarker h) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Entity e = mc.world == null ? null : mc.world.getEntityById(h.entity());
        if (e == null) return;
        boolean kill = (h.kind() & 2) != 0, titan = (h.kind() & 8) != 0;
        float d = h.damage();
        String text = d >= 10 ? String.valueOf(Math.round(d)) : String.format(java.util.Locale.ROOT, "%.1f", d);
        int color = kill ? 0xFF4A3A : titan ? 0xFFD76A : 0xFFFFFF;
        double jitter = (Math.random() - 0.5) * Math.min(1.5, e.getWidth());
        Vec3d at = new Vec3d(e.getX() + jitter, e.getY() + Math.min(e.getHeight() * 0.75, e.getHeight() - 0.2) + 0.3, e.getZ() + jitter);
        // Hits near the camera float up in front of the target rather than inside it.
        nums.add(new Num(at, kill ? text + " ✖" : text, color, kill ? 1.4f : d >= 10 ? 1.2f : 1f, Util.getMeasuringTimeMs()));
        if (nums.size() > 40) nums.remove(0);
    }

    public static void renderWorld(WorldRenderContext ctx) {
        if (nums.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        MatrixStack ms = ctx.matrixStack();
        if (ms == null) return;
        VertexConsumerProvider.Immediate vc = mc.getBufferBuilders().getEntityVertexConsumers();
        Vec3d cam = ctx.camera().getPos();
        long now = Util.getMeasuringTimeMs();
        TextRenderer tr = mc.textRenderer;
        for (Iterator<Num> it = nums.iterator(); it.hasNext(); ) {
            Num n = it.next();
            float t = (now - n.born()) / 900f;
            if (t >= 1) {
                it.remove();
                continue;
            }
            Vec3d at = n.at().add(0, t * 0.9, 0);
            double dist = at.distanceTo(cam);
            float s = (float) (0.02 + dist * 0.0012) * n.scale() * (t < 0.12f ? 0.6f + t * 3.3f : 1f);
            int a = (int) (255 * (t < 0.7f ? 1 : (1 - t) / 0.3f));
            ms.push();
            ms.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
            ms.multiply(mc.getEntityRenderDispatcher().getRotation());
            ms.scale(s, -s, s);
            Text text = Ui.heading(n.text());
            tr.draw(text, -tr.getWidth(text) / 2f, 0, (Math.max(8, a) << 24) | n.color(), true, ms.peek().getPositionMatrix(), vc,
                TextRenderer.TextLayerType.SEE_THROUGH, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            ms.pop();
        }
        vc.draw();
    }

    // ------------------------------------------------------------------ XP bar

    private static long lastXp = -1, gainAt;
    private static int lastLevel;
    private static long gained;

    public static void renderHud(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Net.Sync p = ClientState.profile;
        if (p == null || mc.player == null || mc.options.hudHidden || !CombatHotbar.active()) return;
        long now = Util.getMeasuringTimeMs();
        if (lastXp >= 0 && (p.xp() != lastXp || p.level() != lastLevel)) {
            long diff = p.level() > lastLevel ? p.xp() + 1 : p.xp() - lastXp;
            if (diff > 0) {
                gained = now - gainAt < 2500 ? gained + diff : diff;
                gainAt = now;
            }
        }
        lastXp = p.xp();
        lastLevel = p.level();
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        int x0 = CombatHotbar.slotX(w, 0) - 3, x1 = CombatHotbar.slotX(w, 8) + 20 + 3;
        float frac = p.need() > 0 ? Math.min(1, (float) p.xp() / p.need()) : 1;
        int y = h - 2;
        c.fill(x0, y, x1, y + 2, 0xC0101410);
        c.fill(x0, y, x0 + Math.round((x1 - x0) * frac), y + 2, Ui.XP);
        String lv = "Lv " + p.level();
        Ui.text(c, Text.literal(lv), x1 + 4, h - 10, 0.7f, Ui.GOLD, false);
        if (now - gainAt < 2200 && gained > 0) {
            float f = (now - gainAt) / 2200f;
            int a = (int) (255 * (f < 0.7f ? 1 : (1 - f) / 0.3f));
            Ui.text(c, Text.literal("+" + gained + " XP"), x1 + 4, h - 22 - f * 8, 0.75f, (Math.max(8, a) << 24) | 0xE8C24A, false);
        }
        if (guarding()) {
            int cx = w / 2, cy = h / 2;
            Ui.text(c, Text.literal("GUARD"), cx, cy + 10, 0.6f, 0xC0E0B96A, true);
        }
    }

    // ------------------------------------------------------------------ block, clash, broken guard

    public static void onGuardFx(Net.GuardFx fx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        Style s = Style.of(fx.style(), fx.kind());
        int n = fx.kind() == 1 ? 36 : fx.kind() == 2 ? 22 : 16;
        double spread = fx.kind() == 1 ? 0.55 : 0.3;
        for (int i = 0; i < n; i++) {
            double vx = (Math.random() - 0.5) * spread, vy = (Math.random() - 0.2) * spread, vz = (Math.random() - 0.5) * spread;
            mc.world.addParticle(i % 3 == 0 ? s.second : s.main, fx.x(), fx.y(), fx.z(), vx, vy, vz);
        }
        if (fx.kind() == 1) mc.world.addParticle(ParticleTypes.FLASH, fx.x(), fx.y(), fx.z(), 0, 0, 0);
        if (fx.kind() == 2) for (int i = 0; i < 8; i++) mc.world.addParticle(ParticleTypes.LARGE_SMOKE, fx.x(), fx.y(), fx.z(), 0, 0.05, 0);
    }

    /** Particle pairs for block and clash styles (cosmetics); the defaults are steel sparks. */
    record Style(ParticleEffect main, ParticleEffect second) {
        static Style of(String id, int kind) {
            return switch (id) {
                case "block_frost", "clash_frost" -> new Style(ParticleTypes.SNOWFLAKE, new DustParticleEffect(new Vector3f(0.6f, 0.9f, 1f), 1.2f));
                case "block_ember", "clash_ember" -> new Style(ParticleTypes.FLAME, ParticleTypes.LAVA);
                case "block_holy", "clash_holy" -> new Style(ParticleTypes.END_ROD, new DustParticleEffect(new Vector3f(1f, 0.9f, 0.5f), 1.3f));
                case "block_void", "clash_void" -> new Style(ParticleTypes.REVERSE_PORTAL, ParticleTypes.PORTAL);
                case "block_thunder", "clash_thunder" -> new Style(ParticleTypes.ELECTRIC_SPARK, ParticleTypes.END_ROD);
                case "block_petal", "clash_petal" -> new Style(ParticleTypes.CHERRY_LEAVES, new DustParticleEffect(new Vector3f(1f, 0.6f, 0.75f), 1.1f));
                default -> kind == 1 ? new Style(ParticleTypes.ELECTRIC_SPARK, ParticleTypes.CRIT) : new Style(ParticleTypes.CRIT, ParticleTypes.ELECTRIC_SPARK);
            };
        }
    }
}
