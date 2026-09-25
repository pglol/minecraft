package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Discipline;
import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityAttachmentType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/** AoT-styled name plates over players: dark plate, gold trim, name and level. */
public final class NamePlates {
    private NamePlates() {}

    private static final double NEAR = 18, FAR = 26;

    /** Returns true if a plate was handled (drawn or deliberately hidden). */
    public static boolean render(PlayerEntity player, EntityRenderDispatcher dispatcher, MatrixStack matrices,
                                 VertexConsumerProvider consumers, float tickDelta) {
        Net.RosterEntry r = ClientState.roster.get(player.getUuid());
        if (r == null) return false;
        double d = Math.sqrt(dispatcher.getSquaredDistanceToCamera(player));
        if (d > FAR) return true;
        float alpha = d < NEAR ? 1f : (float) (1 - (d - NEAR) / (FAR - NEAR));
        Vec3d at = player.getAttachments().getPointNullable(EntityAttachmentType.NAME_TAG, 0, player.getYaw(tickDelta));
        if (at == null) return true;

        Net.PartyMember party = ClientState.partyMember(player.getUuid());
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        Text name = Ui.heading(r.name());
        String disc = r.discipline() >= 0 && r.discipline() < Discipline.values().length ? Discipline.values()[r.discipline()].title : "";
        Text sub = Text.literal("Lv " + r.level() + "  ").withColor(Ui.GOLD).append(Text.literal(disc).withColor(Ui.disciplineColor(r.discipline())));
        int w = Math.max(tr.getWidth(name), tr.getWidth(sub)) + 12;
        int h = party != null ? 25 : 22;

        matrices.push();
        matrices.translate(at.x, at.y + 0.55, at.z);
        matrices.multiply(dispatcher.getRotation());
        matrices.scale(0.025f, -0.025f, 0.025f);
        Matrix4f m = matrices.peek().getPositionMatrix();
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        float x0 = -w / 2f, x1 = w / 2f, y0 = -h, y1 = 0;

        VertexConsumer bg = consumers.getBuffer(RenderLayer.getTextBackground());
        int trim = party == null ? Ui.TRIM : party.leader() ? 0xFFF2C14E : 0xFF5BD35B;
        quad(bg, m, x0, y0, x1, y1, -1.5f, fade(0xB00B0F0C, alpha), light);
        quad(bg, m, x0, y0, x1, y0 + 1, -1.0f, fade(trim, alpha), light);
        quad(bg, m, x0, y1 - 1, x1, y1, -1.0f, fade(trim, alpha), light);
        quad(bg, m, x0, y0, x0 + 1, y1, -1.0f, fade(trim & 0x80FFFFFF, alpha), light);
        quad(bg, m, x1 - 1, y0, x1, y1, -1.0f, fade(trim & 0x80FFFFFF, alpha), light);
        if (party != null) {
            float hp = party.maxHealth() > 0 ? Math.max(0, Math.min(1, party.health() / party.maxHealth())) : 0;
            quad(bg, m, x0 + 4, y1 - 4, x1 - 4, y1 - 2, -1.0f, fade(0xFF000000, alpha), light);
            quad(bg, m, x0 + 4, y1 - 4, x0 + 4 + (w - 8) * hp, y1 - 2, -0.5f, fade(Ui.HP, alpha), light);
        }

        int nameColor = party == null ? Ui.CREAM : party.leader() ? 0xFFF2C14E : 0xFF9BE89B;
        tr.draw(name, -tr.getWidth(name) / 2f, y0 + 3, fade(nameColor, alpha), false, m, consumers,
            TextRenderer.TextLayerType.POLYGON_OFFSET, 0, light);
        tr.draw(sub, -tr.getWidth(sub) / 2f, y0 + 12, fade(0xFFFFFFFF, alpha), false, m, consumers,
            TextRenderer.TextLayerType.POLYGON_OFFSET, 0, light);
        // Roleplay tag: a small badge above the plate ("RP · Sergeant" or an operator role).
        if (!r.tag().isEmpty()) {
            Text tag = r.rp() ? Text.literal("RP · ").withColor(0xFF8F8A7A).append(Ui.heading(r.tag()).withColor(0xFF000000 | r.tagColor()))
                : Ui.heading(r.tag()).withColor(0xFF000000 | r.tagColor());
            float tw = tr.getWidth(tag) * 0.8f;
            float bx0 = -tw / 2 - 5, bx1 = tw / 2 + 5, by1 = y0 - 1, by0 = by1 - 10;
            int tc = 0xFF000000 | r.tagColor();
            bg = consumers.getBuffer(RenderLayer.getTextBackground());
            quad(bg, m, bx0, by0, bx1, by1, -1.5f, fade(0xC00B0F0C, alpha), light);
            quad(bg, m, bx0, by1 - 1, bx1, by1, -1.0f, fade(tc, alpha), light);
            quad(bg, m, bx0, by0, bx0 + 2, by1, -1.0f, fade(tc, alpha), light);
            quad(bg, m, bx1 - 2, by0, bx1, by1, -1.0f, fade(tc, alpha), light);
            matrices.push();
            matrices.translate(0, by0 + 2, 0);
            matrices.scale(0.8f, 0.8f, 1);
            tr.draw(tag, -tr.getWidth(tag) / 2f, 0, fade(0xFFFFFFFF, alpha), false, matrices.peek().getPositionMatrix(), consumers,
                TextRenderer.TextLayerType.POLYGON_OFFSET, 0, light);
            matrices.pop();
        }
        matrices.pop();
        return true;
    }

    private static int fade(int argb, float alpha) {
        int a = Math.round(((argb >>> 24) & 0xFF) * alpha);
        return (Math.max(a, 5) << 24) | (argb & 0xFFFFFF);
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float x1, float y1, float x2, float y2, float z, int argb, int light) {
        // Both windings so the plate shows regardless of face culling.
        vc.vertex(m, x1, y1, z).color(argb).light(light);
        vc.vertex(m, x1, y2, z).color(argb).light(light);
        vc.vertex(m, x2, y2, z).color(argb).light(light);
        vc.vertex(m, x2, y1, z).color(argb).light(light);
        vc.vertex(m, x2, y1, z).color(argb).light(light);
        vc.vertex(m, x2, y2, z).color(argb).light(light);
        vc.vertex(m, x1, y2, z).color(argb).light(light);
        vc.vertex(m, x1, y1, z).color(argb).light(light);
    }
}
