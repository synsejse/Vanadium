package com.synsenetwork.vanadium.mixin.entity.ai.brain.task;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.Interactions;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.ai.behavior.AnimalMakeLove;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Flat entity wave: both parents run this behaviour, and whichever gives birth clears the other's breed
 * target. Each step owns both; the second parent finds its target cleared and stops instead of giving
 * birth again (or reading a memory that is gone).
 */
@Mixin(AnimalMakeLove.class)
public abstract class AnimalMakeLoveMixin {
    @WrapMethod(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/animal/Animal;J)V")
    private void vanadium$withPartner(ServerLevel level, Animal body, long time, Operation<Void> original) {
        Optional<AgeableMob> partner = Interactions.active ? body.getBrain().getMemory(MemoryModuleType.BREED_TARGET) : Optional.empty();
        if (partner.isPresent() && Interactions.enter(partner.get(), Interactions.Kind.OTHER)) {
            try {
                if (body.getBrain().hasMemoryValue(MemoryModuleType.BREED_TARGET)) original.call(level, body, time);
            } finally {
                Interactions.exit();
            }
        } else {
            original.call(level, body, time);
        }
    }
}
