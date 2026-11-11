package com.synsenetwork.vanadium.mixin.entity.ai;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.concurrent.Interactions;
import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Flat entity wave: both parents run this goal and reach the birth tick together. Vanilla's first parent
 * resets both, which stops the second; here the birth owns both parents and the second sees that reset.
 */
@Mixin(BreedGoal.class)
public abstract class BreedGoalMixin {
    @Shadow
    @Final
    protected Animal animal;

    @Shadow
    protected Animal partner;

    @WrapMethod(method = "breed")
    private void vanadium$breedOnce(Operation<Void> original) {
        Animal other = partner;
        if (Interactions.active && other != null && Interactions.enter(other, Interactions.Kind.OTHER)) {
            try {
                if (animal.isInLove() && other.isInLove()) original.call();
            } finally {
                Interactions.exit();
            }
        } else {
            original.call();
        }
    }
}
