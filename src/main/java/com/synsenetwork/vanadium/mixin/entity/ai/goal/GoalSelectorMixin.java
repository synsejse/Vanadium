package com.synsenetwork.vanadium.mixin.entity.ai.goal;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(GoalSelector.class)
public abstract class GoalSelectorMixin {
    @Shadow
    private final Set<WrappedGoal> availableGoals = Collections.newSetFromMap(new ConcurrentHashMap<>());
}
