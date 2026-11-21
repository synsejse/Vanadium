package com.synsenetwork.vanadium.mixin.world.ticks;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.synsenetwork.vanadium.collections.ConcurrentLongLongMap;
import com.synsenetwork.vanadium.collections.ConcurrentLongObjectMap;
import com.synsenetwork.vanadium.stage.ScheduledTickAccess;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenCustomHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces blanket {@code SyncAllMixin} synchronization on {@code LevelTicks} with fine-grained concurrency:
 * {@code allContainers} and {@code nextTickForContainer} are concurrent maps so {@code schedule} and
 * {@code hasScheduledTick} take only the target chunk's {@code LevelChunkTicks} monitor; due ticks in a
 * parallel wave are claimed lock-free through {@link #vanadium$waveStates}; and {@code willTickThisTick}
 * reads a lazily built immutable wave index without taking the {@code LevelTicks} monitor.
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin<T> implements ScheduledTickAccess<T> {
    @Unique private static final int STATE_PENDING = 0;
    @Unique private static final int STATE_CLAIMED = 1;
    @Unique private static final int STATE_CANCELLED = 2;
    @Unique private static final int INDEX_ABSENT = -1;
    @Unique private static final int INDEX_MULTIPLE = -2;

    @Shadow @Final @Mutable private Long2ObjectMap<LevelChunkTicks<T>> allContainers;
    @Shadow @Final @Mutable private Long2LongMap nextTickForContainer;
    @Shadow @Final private Queue<ScheduledTick<T>> toRunThisTick;
    @Shadow @Final private List<ScheduledTick<T>> alreadyRunThisTick;
    @Shadow @Final private Set<ScheduledTick<?>> toRunThisTickSet;

    @Shadow protected abstract void collectTicks(long time, int limit, ProfilerFiller profiler);
    @Shadow protected abstract void cleanupAfterTick();

    @Unique private final Object vanadium$waveIndexLock = new Object();
    @Unique private final ArrayList<ScheduledTick<T>> vanadium$waveTicks = new ArrayList<>();
    @Unique private volatile boolean vanadium$waveActive;
    @Unique private AtomicIntegerArray vanadium$waveStates = new AtomicIntegerArray(0);
    @Unique private volatile Object2IntOpenCustomHashMap<ScheduledTick<?>> vanadium$waveIndex;

    /** Installed explicitly: Mixin may drop shadow-field initializers once the class adds fields of its own. */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void vanadium$install(CallbackInfo ci) {
        allContainers = new ConcurrentLongObjectMap<>();
        ConcurrentLongLongMap next = new ConcurrentLongLongMap();
        next.defaultReturnValue(nextTickForContainer.defaultReturnValue());
        nextTickForContainer = next;
    }

    @WrapMethod(method = "addContainer")
    private synchronized void vanadium$addContainer(ChunkPos pos, LevelChunkTicks<T> container, Operation<Void> original) {
        original.call(pos, container);
    }

    @WrapMethod(method = "removeContainer")
    private synchronized void vanadium$removeContainer(ChunkPos pos, Operation<Void> original) {
        original.call(pos);
    }

    @WrapMethod(method = "tick")
    private synchronized void vanadium$serialTick(long time, int maxTicks, BiConsumer<BlockPos, T> ticker, Operation<Void> original) {
        original.call(time, maxTicks, ticker);
    }

    @Override
    public synchronized List<ScheduledTick<T>> vanadium$collect(long time, int limit) {
        collectTicks(time, limit, Profiler.get());
        vanadium$waveTicks.clear();
        vanadium$waveTicks.addAll(toRunThisTick);
        int count = vanadium$waveTicks.size();
        if (count > 0) {
            if (vanadium$waveStates.length() < count) {
                vanadium$waveStates = new AtomicIntegerArray(Math.max(count, vanadium$waveStates.length() * 2));
            }
            for (int i = 0; i < count; i++) {
                vanadium$waveStates.lazySet(i, STATE_PENDING);
            }
        }
        vanadium$waveIndex = null;
        vanadium$waveActive = count > 0;
        return vanadium$waveTicks;
    }

    @Override
    public boolean vanadium$claim(int index) {
        return vanadium$waveStates.compareAndSet(index, STATE_PENDING, STATE_CLAIMED);
    }

    @WrapMethod(method = "willTickThisTick")
    private boolean vanadium$willTickThisTick(BlockPos pos, T type, Operation<Boolean> original) {
        if (!vanadium$waveActive) {
            synchronized (this) {
                return original.call(pos, type);
            }
        }
        Object2IntOpenCustomHashMap<ScheduledTick<?>> index = vanadium$waveIndex;
        if (index == null) {
            index = vanadium$buildWaveIndex();
        }
        ScheduledTick<?> probe = ScheduledTick.probe(type, pos);
        int slot = index.getInt(probe);
        if (slot >= 0) {
            return vanadium$waveStates.get(slot) == STATE_PENDING;
        }
        if (slot == INDEX_ABSENT) {
            return false;
        }
        // Multiple due ticks shared (pos, type) in this wave: scan for any still pending.
        int count = vanadium$waveTicks.size();
        for (int i = 0; i < count; i++) {
            if (vanadium$waveStates.get(i) == STATE_PENDING
                    && ScheduledTick.UNIQUE_TICK_HASH.equals(vanadium$waveTicks.get(i), probe)) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private Object2IntOpenCustomHashMap<ScheduledTick<?>> vanadium$buildWaveIndex() {
        synchronized (vanadium$waveIndexLock) {
            Object2IntOpenCustomHashMap<ScheduledTick<?>> existing = vanadium$waveIndex;
            if (existing != null) return existing;
            int count = vanadium$waveTicks.size();
            Object2IntOpenCustomHashMap<ScheduledTick<?>> built =
                    new Object2IntOpenCustomHashMap<>(count, ScheduledTick.UNIQUE_TICK_HASH);
            built.defaultReturnValue(INDEX_ABSENT);
            for (int i = 0; i < count; i++) {
                ScheduledTick<T> tick = vanadium$waveTicks.get(i);
                int prev = built.put(tick, i);
                if (prev != INDEX_ABSENT) {
                    built.put(tick, INDEX_MULTIPLE);
                }
            }
            vanadium$waveIndex = built;
            return built;
        }
    }

    @WrapMethod(method = "clearArea")
    private synchronized void vanadium$clearArea(BoundingBox area, Operation<Void> original) {
        if (vanadium$waveActive) {
            int count = vanadium$waveTicks.size();
            for (int i = 0; i < count; i++) {
                if (area.isInside(vanadium$waveTicks.get(i).pos())) {
                    vanadium$waveStates.set(i, STATE_CANCELLED);
                }
            }
        }
        original.call(area);
        toRunThisTickSet.removeIf(tick -> area.isInside(tick.pos()));
    }

    @Override
    public synchronized void vanadium$syncWaveLists() {
        if (!vanadium$waveActive) return;
        toRunThisTick.clear();
        alreadyRunThisTick.clear();
        toRunThisTickSet.clear();
        int count = vanadium$waveTicks.size();
        for (int i = 0; i < count; i++) {
            int state = vanadium$waveStates.get(i);
            if (state == STATE_PENDING) {
                toRunThisTick.add(vanadium$waveTicks.get(i));
            } else if (state == STATE_CLAIMED) {
                alreadyRunThisTick.add(vanadium$waveTicks.get(i));
            }
        }
    }

    @WrapMethod(method = "copyAreaFrom")
    @SuppressWarnings("unchecked")
    private void vanadium$copyAreaFrom(LevelTicks<T> source, BoundingBox area, Vec3i offset, Operation<Void> original) {
        if (source == (Object) this) {
            synchronized (this) {
                vanadium$syncWaveLists();
                original.call(source, area, offset);
            }
            return;
        }
        ((ScheduledTickAccess<T>) source).vanadium$syncWaveLists();
        synchronized (this) {
            original.call(source, area, offset);
        }
    }

    @Override
    public synchronized void vanadium$finish() {
        vanadium$waveActive = false;
        vanadium$waveTicks.clear();
        vanadium$waveIndex = null;
        cleanupAfterTick();
    }
}
