package com.synsenetwork.vanadium.mixin.entity.ai.brain;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.Interactions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paired behaviours (socializing, breeding, trading) write the other mob's memories. A brain
 * learns its owner when it first ticks; after that, memory writes from another entity's tick wait for the owner's.
 */
@Mixin(Brain.class)
public abstract class BrainMixin<E extends LivingEntity> {
    @Unique
    private LivingEntity vanadium$owner;

    @Inject(method = "tick", at = @At("HEAD"))
    private void vanadium$rememberOwner(ServerLevel level, E body, CallbackInfo ci) {
        vanadium$owner = body;
    }

    @WrapMethod(method = "setMemoryInternal(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;Ljava/lang/Object;)V")
    private <U> void vanadium$setMemory(MemoryModuleType<U> type, U value, Operation<Void> original) {
        LivingEntity owner = vanadium$owner;
        if (Interactions.active && owner != null && Interactions.enter(owner, Interactions.Kind.MEMORY)) {
            try {
                original.call(type, value);
            } finally {
                Interactions.exit();
            }
        } else {
            original.call(type, value);
        }
    }

    @WrapMethod(method = "setMemoryInternal(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;Ljava/lang/Object;J)V")
    private <U> void vanadium$setMemory(MemoryModuleType<U> type, U value, long timeToLive, Operation<Void> original) {
        LivingEntity owner = vanadium$owner;
        if (Interactions.active && owner != null && Interactions.enter(owner, Interactions.Kind.MEMORY)) {
            try {
                original.call(type, value, timeToLive);
            } finally {
                Interactions.exit();
            }
        } else {
            original.call(type, value, timeToLive);
        }
    }

    @WrapMethod(method = "eraseMemory")
    private <U> void vanadium$eraseMemory(MemoryModuleType<U> type, Operation<Void> original) {
        LivingEntity owner = vanadium$owner;
        if (Interactions.active && owner != null && Interactions.enter(owner, Interactions.Kind.MEMORY)) {
            try {
                original.call(type);
            } finally {
                Interactions.exit();
            }
        } else {
            original.call(type);
        }
    }
}
