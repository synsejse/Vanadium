package com.synsenetwork.vanadium.coldexplosion.mixin;

import com.synsenetwork.vanadium.coldexplosion.ExplosionLog;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observation only: times every server explosion on its own thread. Targets ServerLevel (loaded at startup), never
 * ServerExplosion, so the first explosion's class loading and transform are those of an ordinary server.
 */
@Mixin(ServerLevel.class)
public abstract class ExplosionObserver {
    @Inject(method = "explode", at = @At("HEAD"))
    private void coldExplosion$begin(Entity source, DamageSource damageSource, ExplosionDamageCalculator calculator,
                                     double x, double y, double z, float radius, boolean fire, Level.ExplosionInteraction interaction,
                                     ParticleOptions small, ParticleOptions large, WeightedList<ExplosionParticleInfo> blockParticles,
                                     Holder<SoundEvent> sound, CallbackInfo ci) {
        ExplosionLog.begin();
    }

    @Inject(method = "explode", at = @At("RETURN"))
    private void coldExplosion$end(Entity source, DamageSource damageSource, ExplosionDamageCalculator calculator,
                                   double x, double y, double z, float radius, boolean fire, Level.ExplosionInteraction interaction,
                                   ParticleOptions small, ParticleOptions large, WeightedList<ExplosionParticleInfo> blockParticles,
                                   Holder<SoundEvent> sound, CallbackInfo ci) {
        ExplosionLog.end((ServerLevel) (Object) this, source);
    }
}
