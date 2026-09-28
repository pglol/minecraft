package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.story.CutscenePlayer;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** During a cutscene the camera follows the shot, not the player; in the balloon lobby it frames you on your seat. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private boolean thirdPerson;

    @Shadow protected abstract void setPos(double x, double y, double z);

    @Shadow protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update", at = @At("TAIL"))
    private void aotrpg$cutscene(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
        CutscenePlayer.Cam cam = CutscenePlayer.camera(tickDelta);
        if (cam == null) cam = com.pglol.aotrpg.client.LobbyScreen.camera(tickDelta);
        if (cam == null) cam = com.pglol.aotrpg.client.ShoulderCam.camera(tickDelta);
        if (cam == null) return;
        this.thirdPerson = true;
        setRotation(cam.yaw(), cam.pitch());
        setPos(cam.x(), cam.y(), cam.z());
    }
}
