package com.pglol.aotrpg.client.mixin;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Idle life on the Extraction balloon: seated squadmates lean forward with their forearms on their
 * knees, heads drifting as they look out over the clouds, each at their own rhythm; now and then
 * one stretches, or checks the blade grip at their side.
 */
@Mixin(BipedEntityModel.class)
public abstract class BalloonIdleMixin<T extends LivingEntity> {
    @Shadow @Final public ModelPart head;
    @Shadow @Final public ModelPart hat;
    @Shadow @Final public ModelPart body;
    @Shadow @Final public ModelPart rightArm;
    @Shadow @Final public ModelPart leftArm;

    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void aotrpg$balloonIdle(T e, float limbAngle, float limbDistance, float age, float headYaw, float headPitch, CallbackInfo ci) {
        if (!(e instanceof PlayerEntity) || !e.hasVehicle() || e.getX() > -399_744) return;
        float ph = (e.getId() * 37 % 100) / 100f * MathHelper.TAU;
        float t = age * 0.05f + ph;
        // Where they're gazing: a slow wander, and a lean.
        head.yaw += MathHelper.sin(t * 0.6f) * 0.35f;
        head.pitch += 0.12f + MathHelper.sin(t * 0.9f) * 0.08f;
        body.pitch = 0.18f;
        // Every so often (a few seconds in each half minute) a stretch; otherwise arms on the knees.
        float cycle = (age + e.getId() * 97) % 600;
        if (cycle < 50) {
            float k = MathHelper.sin(cycle / 50f * MathHelper.PI);
            rightArm.pitch = -0.9f - k * 2.1f;
            leftArm.pitch = -0.9f - k * 2.1f;
            rightArm.roll = 0.2f * k;
            leftArm.roll = -0.2f * k;
            head.pitch -= 0.4f * k;
        } else if (cycle > 300 && cycle < 360) {
            // Checking the grip at the hip.
            float k = MathHelper.sin((cycle - 300) / 60f * MathHelper.PI);
            rightArm.pitch = -0.9f + k * 0.7f;
            rightArm.yaw = -0.3f * k;
            leftArm.pitch = -0.95f + MathHelper.sin(t) * 0.04f;
            head.yaw -= 0.5f * k;
            head.pitch += 0.4f * k;
        } else {
            rightArm.pitch = -0.95f + MathHelper.sin(t * 1.3f) * 0.04f;
            leftArm.pitch = -0.95f + MathHelper.sin(t * 1.1f + 1) * 0.04f;
            rightArm.yaw = -0.25f;
            leftArm.yaw = 0.25f;
        }
        hat.copyTransform(head);
    }
}
