package com.pglol.aotrpg.mixin;

import com.pglol.aotrpg.AotRpg;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Titan napes that are not living entities (plain hitbox parts) still count nape strikes. */
@Mixin(Entity.class)
public abstract class EntityDamageMixin {
    @Inject(method = "damage", at = @At("HEAD"), cancellable = true, require = 0)
    private void aotrpg$nape(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof LivingEntity) && AotRpg.TITAN_LEVELS.strike(self, source, amount)) cir.setReturnValue(false);
    }
}
