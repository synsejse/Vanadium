package com.synsenetwork.vanadium.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.Interactions;
import net.minecraft.server.level.ServerLevel;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity {

    @Shadow
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType") // Must match the vanilla field's type.
    private Optional<BlockPos> lastClimbablePos;

    @Shadow
    protected abstract boolean trapdoorUsableAsLadder(BlockPos pos, BlockState state);

    public LivingEntityMixin(EntityType<?> type, Level world) {
        super(type, world);
    }

    @Inject(method = "onClimbable", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/tags/TagKey;)Z"), cancellable = true)
    private void modifyIsInClimbable(CallbackInfoReturnable<Boolean> cir) {
        try {
            if (this.isSpectator()) {
                cir.setReturnValue(false);
            } else {
                BlockPos blockPos = this.blockPosition();
                BlockState blockState = this.getInBlockState();
                if (blockState.is(BlockTags.CLIMBABLE)) {
                    this.lastClimbablePos = Optional.of(blockPos);
                    cir.setReturnValue(true);
                } else if (blockState.getBlock() instanceof TrapDoorBlock && this.trapdoorUsableAsLadder(blockPos, blockState)) {
                    this.lastClimbablePos = Optional.of(blockPos);
                    cir.setReturnValue(true);
                } else {
                    cir.setReturnValue(false);
                }
            }
        } catch (Exception e) {
            cir.setReturnValue(false);
        }
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
