package com.pglol.aotrpg.mixin;

import com.pglol.aotrpg.Combat;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Scales incoming damage (gear Power, combat abilities) in place, so the hit itself still lands. */
@Mixin(value = LivingEntity.class, priority = 500)
public abstract class LivingEntityDamageMixin {
    @ModifyVariable(method = "damage", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private float aotrpg$scale(float amount, DamageSource source, float original) {
        return Combat.scale((LivingEntity) (Object) this, source, amount);
    }
}
