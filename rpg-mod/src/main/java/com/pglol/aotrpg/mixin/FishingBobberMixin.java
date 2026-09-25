package com.pglol.aotrpg.mixin;

import com.pglol.aotrpg.AotRpg;
import com.pglol.aotrpg.Fishing;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fishing minigame: holds the bite while the player reels, and shortens the wait for skilled anglers. */
@Mixin(FishingBobberEntity.class)
public abstract class FishingBobberMixin implements Fishing.Hook {
    @Shadow private int hookCountdown;
    @Shadow private int waitCountdown;

    @Override
    public int aotrpg$bite() {
        return hookCountdown;
    }

    @Override
    public void aotrpg$holdBite() {
        hookCountdown = Math.max(hookCountdown, 10);
    }

    @Override
    public int aotrpg$wait() {
        return waitCountdown;
    }

    @Override
    public void aotrpg$setWait(int ticks) {
        waitCountdown = ticks;
    }

    @Inject(method = "use(Lnet/minecraft/item/ItemStack;)I", at = @At("HEAD"), cancellable = true, require = 0)
    private void aotrpg$reel(ItemStack rod, CallbackInfoReturnable<Integer> cir) {
        FishingBobberEntity self = (FishingBobberEntity) (Object) this;
        if (!self.getWorld().isClient && AotRpg.FISHING.intercept(self)) cir.setReturnValue(0);
    }

    @Inject(method = "tick()V", at = @At("HEAD"), require = 0)
    private void aotrpg$tick(CallbackInfo ci) {
        FishingBobberEntity self = (FishingBobberEntity) (Object) this;
        if (!self.getWorld().isClient) AotRpg.FISHING.tick(self);
    }
}
