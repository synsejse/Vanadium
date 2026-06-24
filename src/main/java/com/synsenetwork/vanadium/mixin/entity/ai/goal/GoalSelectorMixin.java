package com.synsenetwork.vanadium.mixin.entity.ai.goal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;

import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.ai.goal.PrioritizedGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;

@Mixin(GoalSelector.class)
public abstract class GoalSelectorMixin {
    @Shadow
    private final Set<PrioritizedGoal> goals = Collections.newSetFromMap(new ConcurrentHashMap<>());
}
