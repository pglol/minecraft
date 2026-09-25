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
            if (st == null || st.count() <= 0 || pl.isInvisible() || pl.hasVehicle()) continue;
            if (pl == mc.player && mc.options.getPerspective().isFirstPerson()) continue;
            EntityPose pose = pl.getPose();
            if (pose != EntityPose.STANDING && pose != EntityPose.CROUCHING) continue;
            ItemStack[] grips = st.count() == 2 ? new ItemStack[] {st.a(), st.b()} : new ItemStack[] {st.a().isEmpty() ? st.b() : st.a()};

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
            for (int i = 0; i < grips.length; i++) {
                ItemStack stack = grips[i];
                ms.push();
                // An even X: handles up over each shoulder, blades crossing down the back at the
                // same angle either side. Danny's grips are upright 3D models; flat item sprites
                // are drawn diagonally, so they need 45 degrees less.
                boolean upright = Registries.ITEM.getId(stack.getItem()).getNamespace().equals("dannys-aot");
                float tilt = grips.length == 1 ? 30 : (i == 0 ? 38 : -38);
                float angle = (upright ? 180 : 225) + tilt;
                ms.translate(0, -0.05, 0.02 * i);
                ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(angle));
                ms.scale(0.95f, 0.95f, 0.95f);
                // Turn about the model's middle, not its corner (an upright grip's origin is its
                // pommel, so it also slides back half its length to cross at the middle).
                ms.translate(-0.5, upright ? -1.1 : -0.5, -0.5);
                mc.getItemRenderer().renderItem(stack, ModelTransformationMode.NONE, light, OverlayTexture.DEFAULT_UV, ms, vc, mc.world,
                    pl.getId() * 7 + i);
                ms.pop();
            }
            ms.pop();
        }
    }
}
