package com.pglol.aotrpg.client.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** No potion swirls around anyone: effects are felt (EffectFx), not a cloud of particles. */
@Mixin(LivingEntity.class)
public abstract class PotionSwirlMixin {
    @Redirect(method = "tickStatusEffects", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/World;addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)V"), require = 0)
    private void aotrpg$noSwirl(World world, ParticleEffect effect, double x, double y, double z, double vx, double vy, double vz) {
        // Nothing: the swirl is dropped.
    }
}
