package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * Titans: a small "Lv 12 · Titan" plate over their heads (with nape pips once you start cutting),
 * and a fighting-game hit counter: consecutive hits build a combo, nape strikes fill pips, and a
 * severed nape flashes across the screen.
 */
public final class TitanPlates {
    private TitanPlates() {}

    private static final Map<Integer, Net.TitanTag> tags = new HashMap<>();

    public static void onTags(Net.TitanTags t) {
        tags.clear();
        for (Net.TitanTag x : t.tags()) tags.put(x.entity(), x);
    }

    private static String name(Entity e) {
        if (e.hasCustomName()) return e.getCustomName().getString();
        String path = Registries.ENTITY_TYPE.getId(e.getType()).getPath().replace("_titan", "").replace("titan_", "").replace('_', ' ').trim();
        if (path.isEmpty() || path.equals("titan")) return "Titan";
        String[] w = path.split(" ");
        StringBuilder b = new StringBuilder();
        for (String s : w) if (!s.isEmpty()) b.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1)).append(' ');
        return b.append("Titan").toString();
    }

    /** Colour of a level relative to yours: grey easy, white even, orange hard, red deadly. */
    public static int levelColor(int lv) {
        int me = ClientState.profile == null ? 1 : ClientState.profile.level();
        int d = lv - me;
        return d <= -6 ? 0xFF9A9A9A : d <= 2 ? 0xFFEDE3C8 : d <= 6 ? 0xFFFFA040 : 0xFFFF4A3A;
    }

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || tags.isEmpty()) return;
        MatrixStack ms = ctx.matrixStack();
        if (ms == null) return;
        VertexConsumerProvider.Immediate vc = mc.getBufferBuilders().getEntityVertexConsumers();
        Vec3d cam = ctx.camera().getPos();
        TextRenderer tr = mc.textRenderer;
        float delta = ctx.tickCounter().getTickDelta(true);
        for (Net.TitanTag t : tags.values()) {
            Entity e = mc.world.getEntityById(t.entity());
            if (!(e instanceof LivingEntity le) || !e.isAlive() || t.level() <= 0) continue;
            if (e.hasPassengers() && e.getPassengerList().contains(mc.player)) continue;
            Vec3d pos = e.getLerpedPos(delta).add(0, e.getHeight() + 0.6, 0);
            double dist = pos.distanceTo(cam);
            if (dist > 96) continue;
            float s = (float) (0.025 + dist * 0.0016);
            ms.push();
            ms.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
            ms.multiply(mc.getEntityRenderDispatcher().getRotation());
            ms.scale(s, -s, s);
            MutableText line = Text.literal("Lv " + t.level()).withColor(levelColor(t.level()))
                .append(Text.literal("  " + name(e)).withColor(0xFFD8CFC0));
            Text text = Ui.heading(line.getString()).withColor(levelColor(t.level()));
            int w = tr.getWidth(text);
            int bg = (int) (MathHelper.clamp(1 - (dist - 64) / 32, 0, 1) * 0x90) << 24;
            tr.draw(text, -w / 2f, 0, levelColor(t.level()), false, ms.peek().getPositionMatrix(), vc,
                TextRenderer.TextLayerType.SEE_THROUGH, bg, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            // Health bar under the name.
            float hp = MathHelper.clamp(le.getHealth() / Math.max(1, le.getMaxHealth()), 0, 1);
            String bar = "▬".repeat(Math.max(0, Math.round(hp * 10))) + "▭".repeat(Math.max(0, 10 - Math.round(hp * 10)));
            tr.draw(bar, -tr.getWidth(bar) / 2f, 10, 0xFFD04A3A, false, ms.peek().getPositionMatrix(), vc,
                TextRenderer.TextLayerType.SEE_THROUGH, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            // Nape pips once it has been cut (or how many cuts it will take you).
            if (t.needed() > 1) {
                StringBuilder pips = new StringBuilder("NAPE ");
                for (int i = 0; i < t.needed(); i++) pips.append(i < t.strikes() ? '◆' : '◇');
                String p = pips.toString();
                tr.draw(p, -tr.getWidth(p) / 2f, 20, t.strikes() > 0 ? 0xFFFFD76A : 0xFFB0A890, false, ms.peek().getPositionMatrix(), vc,
                    TextRenderer.TextLayerType.SEE_THROUGH, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            }
            ms.pop();
        }
        vc.draw();
    }

    // ------------------------------------------------------------------ hit counter

    private static int combo, napeStrikes, napeNeeded;
    private static long lastHitAt, napeAt, severedAt, bumpAt;
    private static final long COMBO_MS = 2600;

    /** Any hit you land on a titan or fighter keeps the combo going. */
    public static void onHit(Net.HitMarker h) {
        long now = Util.getMeasuringTimeMs();
        if (now - lastHitAt > COMBO_MS) combo = 0;
        combo++;
        lastHitAt = now;
        bumpAt = now;
    }

    public static void onNape(Net.NapeHit n) {
        long now = Util.getMeasuringTimeMs();
        napeStrikes = n.strikes();
        napeNeeded = n.needed();
        napeAt = now;
        if (n.kill()) severedAt = now;
        Net.TitanTag t = tags.get(n.entity());
        if (t != null) tags.put(n.entity(), new Net.TitanTag(t.entity(), t.level(), n.kill() ? 0 : n.strikes(), n.needed()));
        if (now - lastHitAt > COMBO_MS) combo = 0;
        lastHitAt = now;
        bumpAt = now;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.playSound(n.kill() ? net.minecraft.sound.SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP : net.minecraft.sound.SoundEvents.ENTITY_PLAYER_ATTACK_CRIT,
                0.8f, n.kill() ? 0.7f : 1.2f + 0.1f * n.strikes());
        }
    }

    public static void renderHud(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || mc.player == null) return;
        long now = Util.getMeasuringTimeMs();
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        int x = w - 24, y = h / 2 - 20;
        long since = now - lastHitAt;
        if (combo >= 2 && since < COMBO_MS) {
            float pop = 1 + 0.35f * Math.max(0, 1 - (now - bumpAt) / 140f);
            float fade = since > COMBO_MS - 500 ? (COMBO_MS - since) / 500f : 1;
            int a = Math.max(8, (int) (255 * fade)) << 24;
            String n = String.valueOf(combo);
            // Big slanted number, "HITS" under it, and a draining timer bar.
            var m = c.getMatrices();
            m.push();
            m.translate(x, y, 0);
            m.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(-8));
            float sc = 2.6f * pop;
            m.scale(sc, sc, 1);
            Text big = Ui.title(n);
            int col = combo >= 20 ? 0xFF4A3A : combo >= 10 ? 0xFFB020 : 0xFFE0B96A & 0xFFFFFF;
            c.drawText(mc.textRenderer, big, -mc.textRenderer.getWidth(big), -8, a | col, true);
            m.pop();
            Ui.text(c, Ui.heading("HITS"), x - 2 - Ui.font().getWidth("HITS"), y + 12, 1f, a | 0xEDE3C8, false);
            float left = 1 - since / (float) COMBO_MS;
            c.fill(x - 70, y + 24, x, y + 26, a | 0x201A10);
            c.fill(x - Math.round(70 * left), y + 24, x, y + 26, a | 0xE0B96A);
        }
        // Nape progress under the counter.
        if (now - napeAt < 3500 && napeNeeded > 1 && now - severedAt > 1200) {
            int py = y + 34;
            String label = "NAPE";
            Ui.text(c, Ui.heading(label), x - 4 - 14 * napeNeeded - Ui.font().getWidth(label), py, 1f, 0xFFEDE3C8, false);
            for (int i = 0; i < napeNeeded; i++) {
                int px = x - 14 * (napeNeeded - i);
                boolean on = i < napeStrikes;
                c.fill(px, py, px + 10, py + 8, on ? 0xFFFFD76A : 0x60201A10);
                c.drawBorder(px, py, 10, 8, on ? 0xFFFFF2B0 : 0xFF6A5A40);
            }
            if (napeStrikes < napeNeeded) Ui.text(c, Text.literal((napeNeeded - napeStrikes) + " more to sever"), x, py + 11, 0.7f, 0xFFB0A890, false);
        }
        // "NAPE SEVERED" slam.
        long s = now - severedAt;
        if (s < 1200) {
            float t = s / 1200f;
            float sc = 2.2f + (t < 0.1f ? (0.1f - t) * 12 : 0);
            int a = Math.max(8, (int) (255 * (t < 0.7f ? 1 : (1 - t) / 0.3f))) << 24;
            var m = c.getMatrices();
            m.push();
            m.translate(w / 2f, h / 2f - 50, 0);
            m.scale(sc, sc, 1);
            Text txt = Ui.title("NAPE SEVERED");
            c.drawText(mc.textRenderer, txt, -mc.textRenderer.getWidth(txt) / 2, -4, a | 0xFFD76A, true);
            m.pop();
        }
    }
}
