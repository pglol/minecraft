package com.pglol.aotrpg.mixin;

import com.pglol.aotrpg.WorldCare;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks when a non-player entity ticks, so blocks it places (debris) can be cleaned up later. */
@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {
    @Inject(method = "tickEntity(Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"), require = 0)
    private void aotrpg$mobStart(Entity e, CallbackInfo ci) {
        if (!(e instanceof PlayerEntity)) WorldCare.mobTicking++;
    }

    @Inject(method = "tickEntity(Lnet/minecraft/entity/Entity;)V", at = @At("RETURN"), require = 0)
    private void aotrpg$mobEnd(Entity e, CallbackInfo ci) {
        if (!(e instanceof PlayerEntity) && WorldCare.mobTicking > 0) WorldCare.mobTicking--;
    }
}
