package com.synsenetwork.vanadium.mixin.world.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.Interactions;
import com.synsenetwork.vanadium.entity.LineOfSightFastPath;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
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
        boolean scoped = Interactions.active && Interactions.enter(this, Interactions.Write.HURT);
        try {
            return original.call(level, source, damage);
        } finally {
            if (scoped) Interactions.exit();
        }
    }

    /** Effects applied by another entity's tick (stings, auras) change this entity's effect map. */
    @WrapMethod(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z")
    private boolean vanadium$addEffect(MobEffectInstance effect, Entity source, Operation<Boolean> original) {
        boolean scoped = Interactions.active && Interactions.enter(this, Interactions.Write.OTHER);
        try {
            return original.call(effect, source);
        } finally {
            if (scoped) Interactions.exit();
        }
    }

    @WrapMethod(method = "forceAddEffect")
    private void vanadium$forceAddEffect(MobEffectInstance effect, Entity source, Operation<Void> original) {
        boolean scoped = Interactions.active && Interactions.enter(this, Interactions.Write.OTHER);
        try {
            original.call(effect, source);
        } finally {
            if (scoped) Interactions.exit();
        }
    }

    @WrapMethod(method = "removeEffect")
    private boolean vanadium$removeEffect(Holder<MobEffect> effect, Operation<Boolean> original) {
        boolean scoped = Interactions.active && Interactions.enter(this, Interactions.Write.OTHER);
        try {
            return original.call(effect);
        } finally {
            if (scoped) Interactions.exit();
        }
    }

    /** Allocation-free exact DDA raycast for line-of-sight checks; falls back if a custom Level overrides {@code clip}. */
    @WrapMethod(method = "hasLineOfSight(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/ClipContext$Block;Lnet/minecraft/world/level/ClipContext$Fluid;D)Z")
    private boolean vanadium$hasLineOfSight(
            Entity target,
            ClipContext.Block block,
            ClipContext.Fluid fluid,
            double targetY,
            Operation<Boolean> original) {
        Level level = this.level();
        if (target.level() != level) {
            return false;
        }
        if (!LineOfSightFastPath.usesDefaultClip(level)) {
            return original.call(target, block, fluid, targetY);
        }
        return LineOfSightFastPath.hasLineOfSight((LivingEntity) (Object) this, level, target, block, fluid, targetY);
    }
}
