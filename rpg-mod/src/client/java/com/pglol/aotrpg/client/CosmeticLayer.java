package com.pglol.aotrpg.client;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Worn cosmetics on the player model itself: wings, cloaks and banners hang from the body, halos
 * and crowns sit on the head. They follow every move (turning, looking up, crouching, leaning into
 * a swing) instead of floating where the body was, so the body never passes through them.
 */
public final class CosmeticLayer extends FeatureRenderer<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> {
    public CosmeticLayer(FeatureRendererContext<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> context) {
        super(context);
    }

    @Override
    public void render(MatrixStack ms, VertexConsumerProvider vc, int light, AbstractClientPlayerEntity p, float limbAngle, float limbDistance,
                       float tickDelta, float animationProgress, float headYaw, float headPitch) {
        if (p.isInvisible()) return;
        float time = (p.age + tickDelta) / 20f;
        String back = CosmeticFx.worn(p.getUuid(), "back");
        if (back.startsWith("back_wings") || back.startsWith("back_cloak") || back.equals("back_banner")) {
            ms.push();
            getContextModel().body.rotate(ms);
            // Model space: +Y is down and the back faces +Z. Flip both so the shapes read Y up, Z forward.
            ms.translate(0, 0.1, 0);
            ms.scale(1, -1, -1);
            CosmeticFx.backShape(ms, vc, time, back, p.getVelocity().horizontalLength(), limbDistance);
            ms.pop();
        }
        String head = CosmeticFx.worn(p.getUuid(), "head");
        if (head.equals("head_halo") || head.equals("head_crown") || head.equals("head_laurel") || head.equals("head_sun") || head.equals("head_founder")) {
            ms.push();
            getContextModel().head.rotate(ms);
            ms.translate(0, -0.5, 0); // the top of the head
            ms.scale(1, -1, -1);
            CosmeticFx.headShape(ms, vc, time, head);
            ms.pop();
        }
    }
}
