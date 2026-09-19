package com.synsenetwork.vanadium.mixin.world;

import com.synsenetwork.vanadium.tick.ScheduledTickAccess;
import com.synsenetwork.vanadium.tick.ScheduledTickQueue;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin<T> implements ScheduledTickAccess<T> {
    @Shadow @Final @Mutable
    private Queue<ScheduledTick<T>> toRunThisTick = new ScheduledTickQueue<>();
    @Shadow @Final private List<ScheduledTick<T>> alreadyRunThisTick;
    @Shadow @Final private Set<ScheduledTick<?>> toRunThisTickSet;

    @Shadow protected abstract void collectTicks(long time, int limit, ProfilerFiller profiler);
    @Shadow protected abstract void cleanupAfterTick();

    @Override
    public synchronized List<ScheduledTick<T>> vanadium$collect(long time, int limit) {
        collectTicks(time, limit, Profiler.get());
        return List.copyOf(toRunThisTick);
    }

    @Override
    public synchronized boolean vanadium$claim(ScheduledTick<T> tick) {
        // clearArea may have cancelled this callback since collection.
        if (!toRunThisTick.remove(tick)) return false;
        toRunThisTickSet.remove(tick);
        alreadyRunThisTick.add(tick);
        return true;
    }

    @Override
    public synchronized void vanadium$finish() {
        cleanupAfterTick();
    }

    @Inject(method = "clearArea", at = @At("RETURN"))
    private void clearPendingLookup(BoundingBox area, CallbackInfo ci) {
        toRunThisTickSet.removeIf(tick -> area.isInside(tick.pos()));
    }
}
