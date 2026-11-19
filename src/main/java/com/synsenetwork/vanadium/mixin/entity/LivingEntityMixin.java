package com.synsenetwork.vanadium.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.Interactions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity {

    public LivingEntityMixin(EntityType<?> type, Level world) {
        super(type, world);
    }

    /** Deaths are serialized per entity: two damage sources must not both run the death sequence. */
    @WrapMethod(method = "die")
    private synchronized void onDeath(DamageSource damageSource, Operation<Void> original) {
        original.call(damageSource);
    }

    /** Damage from another entity's tick waits for this entity's own tick, then runs whole. */
    @WrapMethod(method = "hurtServer")
    private boolean vanadium$hurtServer(ServerLevel level, DamageSource source, float damage, Operation<Boolean> original) {
        if (Interactions.active && Interactions.enter(this, Interactions.Kind.HURT)) {
            try {
                return original.call(level, source, damage);
            } finally {
                Interactions.exit();
            }
        }
        return original.call(level, source, damage);
    }

    /** Effects applied by another entity's tick (stings, auras) change this entity's effect map. */
    @WrapMethod(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z")
    private boolean vanadium$addEffect(MobEffectInstance effect, Entity source, Operation<Boolean> original) {
        if (Interactions.active && Interactions.enter(this, Interactions.Kind.OTHER)) {
            try {
                return original.call(effect, source);
            } finally {
                Interactions.exit();
            }
        }
        return original.call(effect, source);
    }

    @WrapMethod(method = "forceAddEffect")
    private void vanadium$forceAddEffect(MobEffectInstance effect, Entity source, Operation<Void> original) {
        if (Interactions.active && Interactions.enter(this, Interactions.Kind.OTHER)) {
            try {
                original.call(effect, source);
            } finally {
                Interactions.exit();
            }
        } else {
            original.call(effect, source);
        }
    }

    @WrapMethod(method = "removeEffect")
    private boolean vanadium$removeEffect(Holder<MobEffect> effect, Operation<Boolean> original) {
        if (Interactions.active && Interactions.enter(this, Interactions.Kind.OTHER)) {
            try {
                return original.call(effect);
            } finally {
                Interactions.exit();
            }
        }
        return original.call(effect);
    }
}
