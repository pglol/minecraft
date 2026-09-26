package com.pglol.aotrpg.mixin.phase;

import com.pglol.aotrpg.Story;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phasing: a story actor or scene titan is only sent to the players in its scene, so each player's
 * story NPCs stay theirs (and their party's) while the rest of the world is shared.
 */
@Mixin(targets = "net.minecraft.server.world.ServerChunkLoadingManager$EntityTracker")
public abstract class EntityTrackerMixin {
    @Shadow @Final Entity entity;

    @Shadow public abstract void stopTracking(ServerPlayerEntity player);

    @Inject(method = "updateTrackedStatus(Lnet/minecraft/server/network/ServerPlayerEntity;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void aotrpg$phase(ServerPlayerEntity player, CallbackInfo ci) {
        if (!Story.visibleTo(entity, player)) {
            stopTracking(player);
            ci.cancel();
        }
    }
}
