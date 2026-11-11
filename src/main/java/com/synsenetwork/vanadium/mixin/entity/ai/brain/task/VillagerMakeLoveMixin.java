package com.synsenetwork.vanadium.mixin.entity.ai.brain.task;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.Interactions;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.ai.behavior.VillagerMakeLove;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Both villagers run this behaviour and reach the birth time together. Each step owns
 * both, and rechecks what vanilla checked just before it, so the second finds the first's cooldown.
 */
@Mixin(VillagerMakeLove.class)
public abstract class VillagerMakeLoveMixin {
    @WrapMethod(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/villager/Villager;J)V")
    private void vanadium$withPartner(ServerLevel level, Villager body, long time, Operation<Void> original) {
        Optional<AgeableMob> partner = Interactions.active ? body.getBrain().getMemory(MemoryModuleType.BREED_TARGET) : Optional.empty();
        if (partner.isPresent() && Interactions.enterPair(partner.get(), Interactions.Kind.PAIR)) {
            try {
                if (body.getBrain().hasMemoryValue(MemoryModuleType.BREED_TARGET) && body.canBreed() && partner.get().canBreed()) {
                    original.call(level, body, time);
                }
            } finally {
                Interactions.exitPair();
            }
        } else {
            original.call(level, body, time);
        }
    }
}
