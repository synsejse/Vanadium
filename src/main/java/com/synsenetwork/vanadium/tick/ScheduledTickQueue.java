package com.synsenetwork.vanadium.tick;

import java.util.AbstractQueue;
import java.util.Iterator;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import net.minecraft.world.ticks.ScheduledTick;

/** Due ticks are unique; retain drain order with constant-time removal by parallel callbacks. */
public final class ScheduledTickQueue<T> extends AbstractQueue<ScheduledTick<T>> {
    private final ReferenceLinkedOpenHashSet<ScheduledTick<T>> ticks = new ReferenceLinkedOpenHashSet<>();

    @Override public boolean offer(ScheduledTick<T> tick) { return ticks.add(tick); }
    @Override public ScheduledTick<T> peek() { return ticks.isEmpty() ? null : ticks.first(); }
    @Override public ScheduledTick<T> poll() { return ticks.isEmpty() ? null : ticks.removeFirst(); }
    @Override public boolean remove(Object tick) { return ticks.remove(tick); }
    @Override public Iterator<ScheduledTick<T>> iterator() { return ticks.iterator(); }
    @Override public int size() { return ticks.size(); }
    @Override public void clear() { ticks.clear(); }
}
