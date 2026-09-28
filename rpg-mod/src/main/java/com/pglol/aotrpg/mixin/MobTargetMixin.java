package com.pglol.aotrpg.mixin;

import com.pglol.aotrpg.AotRpg;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Titans don't hunt townsfolk (they can't be hurt, so they were grabbed and left dangling), and
 * nothing hunts a downed player: they're left alone on the ground until revived or gone.
 */
@Mixin(MobEntity.class)
public abstract class MobTargetMixin {
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true, require = 0)
    private void aotrpg$target(LivingEntity target, CallbackInfo ci) {
        if (target == null) return;
        MobEntity self = (MobEntity) (Object) this;
        // (Marleyan troops are fair game: titans hunt them like anyone.)
        if (target instanceof MerchantEntity && AotRpg.isTitan(self) && !com.pglol.aotrpg.Troops.is(target)) ci.cancel();
        else if (target instanceof ServerPlayerEntity p && AotRpg.DOWNED != null && AotRpg.DOWNED.isDowned(p)) ci.cancel();
    }
}
