package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

/**
 * Sheathed ODM grips crossed on a player's back, handles up over the shoulders. Drawn straight into
 * the world after entities, so it works whatever renders the player model itself.
 */
public final class SheathRender {
    private SheathRender() {}

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || ClientState.sheaths.isEmpty()) return;
        MatrixStack ms = ctx.matrixStack();
        VertexConsumerProvider vc = ctx.consumers();
        if (ms == null || vc == null) return;
        Vec3d cam = ctx.camera().getPos();
        float td = ctx.tickCounter().getTickDelta(true);
        for (PlayerEntity pl : mc.world.getPlayers()) {
            Net.SheathState st = ClientState.sheaths.get(pl.getUuid());
            if (st == null || st.count() <= 0 || st.item().isEmpty() || pl.isInvisible() || pl.hasVehicle()) continue;
            if (pl == mc.player && mc.options.getPerspective().isFirstPerson()) continue;
            EntityPose pose = pl.getPose();
            if (pose != EntityPose.STANDING && pose != EntityPose.CROUCHING) continue;
            var item = Registries.ITEM.get(Identifier.of(st.item()));
            if (item == Items.AIR) continue;
            ItemStack stack = new ItemStack(item);

            Vec3d pos = pl.getLerpedPos(td);
            float bodyYaw = MathHelper.lerpAngleDegrees(td, pl.prevBodyYaw, pl.bodyYaw);
            boolean crouch = pose == EntityPose.CROUCHING;
            boolean armored = !pl.getEquippedStack(EquipmentSlot.CHEST).isEmpty();
            int light = WorldRenderer.getLightmapCoordinates(mc.world, pl.getBlockPos().up());

            ms.push();
            ms.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
            // Local frame: +z is the player's back, +x the viewer's right when seen from behind.
            ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180 - bodyYaw));
            ms.translate(0, crouch ? 1.02 : 1.2, 0);
            if (crouch) ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-28));
            ms.translate(0, 0, armored ? 0.24 : 0.19);
            for (int i = 0; i < st.count(); i++) {
                ms.push();
                // Handles up over each shoulder, blades crossing down the back.
                float angle = st.count() == 1 ? 225 : (i == 0 ? 180 : 270);
                ms.translate(0, 0, 0.015 * i);
                ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(angle));
                ms.scale(0.62f, 0.62f, 0.62f);
                mc.getItemRenderer().renderItem(stack, ModelTransformationMode.NONE, light, OverlayTexture.DEFAULT_UV, ms, vc, mc.world,
                    pl.getId() * 7 + i);
                ms.pop();
            }
            ms.pop();
        }
    }
}
