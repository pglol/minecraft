package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.VillagerEntity;
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
        if (mc.world == null) return;
        MatrixStack ms = ctx.matrixStack();
        VertexConsumerProvider vc = ctx.consumers();
        if (ms == null || vc == null) return;
        Vec3d cam = ctx.camera().getPos();
        float td = ctx.tickCounter().getTickDelta(true);
        // (Players carry theirs as a layer of their own model: see Feature.)
        // Cadets and soldiers in the story: harness on and hands empty means blades sheathed on the back.
        ItemStack blade = grip();
        if (!blade.isEmpty()) {
            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof VillagerEntity v) || v.isInvisible() || v.hasVehicle() || !v.getMainHandStack().isEmpty()) continue;
                if (!Registries.ITEM.getId(v.getEquippedStack(EquipmentSlot.LEGS).getItem()).getPath().contains("odm")) continue;
                if (cam.squaredDistanceTo(v.getPos()) > 48 * 48) continue;
                draw(mc, ms, vc, cam, td, v, new ItemStack[] {blade, blade});
            }
        }
    }

    private static ItemStack gripCache;

    /** Danny's grip, to draw on actors' backs (empty without the mod). */
    private static ItemStack grip() {
        if (gripCache == null) {
            var item = Registries.ITEM.get(Identifier.of("dannys-aot", "blade"));
            gripCache = item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        }
        return gripCache;
    }

    private static void draw(MinecraftClient mc, MatrixStack ms, VertexConsumerProvider vc, Vec3d cam, float td, LivingEntity pl, ItemStack[] grips) {
            EntityPose pose = pl.getPose();
            Vec3d pos = pl.getLerpedPos(td);
            float bodyYaw = MathHelper.lerpAngleDegrees(td, pl.prevBodyYaw, pl.bodyYaw);
            boolean crouch = pose == EntityPose.CROUCHING;
            boolean armored = !pl.getEquippedStack(EquipmentSlot.CHEST).isEmpty();
            int light = WorldRenderer.getLightmapCoordinates(mc.world, pl.getBlockPos().up());

            ms.push();
            ms.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
            // Tumbling with the body through an ODM flip or dash (the same turn the model takes).
            OdmMoves.Anim move = OdmMoves.anim(pl.getId());
            if (move != null) {
                float angle = OdmMoves.bodyAngle(move);
                if (angle != 0) {
                    double my = Math.toRadians(move.yaw());
                    float mid = pl.getHeight() / 2f;
                    ms.translate(0, mid, 0);
                    ms.multiply(new org.joml.Quaternionf().rotationAxis((float) Math.toRadians(angle), (float) Math.cos(my), 0, (float) Math.sin(my)));
                    ms.translate(0, -mid, 0);
                }
            }
            // Local frame: +z is the player's back, +x the viewer's right when seen from behind.
            ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180 - bodyYaw));
            ms.translate(0, crouch ? 1.02 : 1.2, 0);
            if (crouch) ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-28));
            ms.translate(0, 0, armored ? 0.24 : 0.19);
            grips(mc, ms, vc, light, pl, grips);
            ms.pop();
    }

    /** The grips themselves, crossed, in a frame on the back (origin between the shoulder blades, +z out of the back). */
    private static void grips(MinecraftClient mc, MatrixStack ms, VertexConsumerProvider vc, int light, LivingEntity pl, ItemStack[] grips) {
            for (int i = 0; i < grips.length; i++) {
                ItemStack stack = grips[i];
                ms.push();
                ms.translate(0, -0.05, 0.012 * i);
                if (Registries.ITEM.getId(stack.getItem()).getNamespace().equals("dannys-aot")) {
                    // Danny's real 3D grip: upright along its own Y from the pommel. Tilted 45 degrees
                    // either side of hanging straight down, handles over the shoulders, and slid back
                    // half its length so the two cross at their middles, flat against the back.
                    float angle = grips.length == 1 ? 200 : (i == 0 ? 135 : 225);
                    ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(angle));
                    // Turn each blade a quarter about its own length (inward, mirrored) so the flat of
                    // the blade lies against the back instead of standing out edge-first.
                    ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(grips.length == 1 || i == 0 ? 90 : -90));
                    ms.scale(0.8f, 0.8f, 0.8f);
                    ms.translate(0, -0.55, 0);
                    mc.getItemRenderer().renderItem(stack, ModelTransformationMode.NONE, light, OverlayTexture.DEFAULT_UV, ms, vc, mc.world,
                        pl.getId() * 7 + i);
                } else {
                    // Flat item sprites lie on the diagonal: 180 and 270 degrees make the X.
                    float angle = grips.length == 1 ? 225 : (i == 0 ? 180 : 270);
                    ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(angle));
                    ms.scale(0.85f, 0.85f, 0.85f);
                    mc.getItemRenderer().renderItem(stack, ModelTransformationMode.GUI, light, OverlayTexture.DEFAULT_UV, ms, vc, mc.world,
                        pl.getId() * 7 + i);
                }
                ms.pop();
            }
    }

    /**
     * A player's sheathed grips as a layer of their model, fixed to the body: they move with
     * whatever animates it (crouching, an ODM flip, a movement pack such as Fresh Moves), and show
     * in the combat camera's over-the-shoulder view too.
     */
    public static final class Feature extends net.minecraft.client.render.entity.feature.FeatureRenderer<net.minecraft.client.network.AbstractClientPlayerEntity,
        net.minecraft.client.render.entity.model.PlayerEntityModel<net.minecraft.client.network.AbstractClientPlayerEntity>> {

        public Feature(net.minecraft.client.render.entity.feature.FeatureRendererContext<net.minecraft.client.network.AbstractClientPlayerEntity,
            net.minecraft.client.render.entity.model.PlayerEntityModel<net.minecraft.client.network.AbstractClientPlayerEntity>> ctx) {
            super(ctx);
        }

        @Override
        public void render(MatrixStack ms, VertexConsumerProvider vc, int light, net.minecraft.client.network.AbstractClientPlayerEntity pl,
                           float limbAngle, float limbDistance, float tickDelta, float animationProgress, float headYaw, float headPitch) {
            Net.SheathState st = ClientState.sheaths.get(pl.getUuid());
            if (st == null || st.count() <= 0 || pl.isInvisible() || pl.hasVehicle()) return;
            ItemStack[] grips = st.count() == 2 ? new ItemStack[] {st.a(), st.b()} : new ItemStack[] {st.a().isEmpty() ? st.b() : st.a()};
            boolean armored = !pl.getEquippedStack(EquipmentSlot.CHEST).isEmpty();
            ms.push();
            // Onto the body as it's posed right now, then back to an upright frame (the model's is flipped).
            getContextModel().body.rotate(ms);
            ms.scale(-1, -1, 1);
            ms.translate(0, -0.22, armored ? 0.24 : 0.19);
            grips(MinecraftClient.getInstance(), ms, vc, light, pl, grips);
            ms.pop();
        }
    }
}
