package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

/** Sheathed ODM grips crossed diagonally on the player's back. */
public final class SheathFeature extends FeatureRenderer<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> {
    public SheathFeature(FeatureRendererContext<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> ctx) {
        super(ctx);
    }

    @Override
    public void render(MatrixStack m, VertexConsumerProvider vc, int light, AbstractClientPlayerEntity e, float limbAngle,
                       float limbDistance, float tickDelta, float animationProgress, float headYaw, float headPitch) {
        Net.SheathState st = ClientState.sheaths.get(e.getUuid());
        if (st == null || st.count() <= 0 || st.item().isEmpty() || e.isInvisible()) return;
        var item = Registries.ITEM.get(Identifier.of(st.item()));
        if (item == Items.AIR) return;
        ItemStack stack = new ItemStack(item);
        boolean armored = !e.getEquippedStack(EquipmentSlot.CHEST).isEmpty();
        m.push();
        getContextModel().body.rotate(m);
        // Model space: y points down, +z is the back.
        m.translate(0, 0.38, armored ? 0.21 : 0.16);
        for (int i = 0; i < st.count(); i++) {
            float side = st.count() == 1 ? 0 : (i == 0 ? -1 : 1);
            m.push();
            m.translate(side * 0.07, 0, 0.02 * i);
            m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180));
            m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180 + (side == 0 ? 35 : side * 40)));
            m.scale(0.6f, 0.6f, 0.6f);
            MinecraftClient.getInstance().getItemRenderer().renderItem(e, stack, ModelTransformationMode.FIXED, false, m, vc,
                e.getWorld(), light, OverlayTexture.DEFAULT_UV, e.getId() + i);
            m.pop();
        }
        m.pop();
    }
}
