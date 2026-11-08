package com.synsenetwork.vanadium.tick

import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet
import net.minecraft.world.ticks.ScheduledTick
import java.util.AbstractQueue

/** Due ticks are unique; retain drain order with constant-time removal by parallel callbacks. */
class ScheduledTickQueue<T : Any> : AbstractQueue<ScheduledTick<T>>() {
    private val ticks = ReferenceLinkedOpenHashSet<ScheduledTick<T>>()

    override fun offer(tick: ScheduledTick<T>): Boolean = ticks.add(tick)

    override fun peek(): ScheduledTick<T>? = if (ticks.isEmpty()) null else ticks.first()

    override fun poll(): ScheduledTick<T>? = if (ticks.isEmpty()) null else ticks.removeFirst()

    override fun remove(element: ScheduledTick<T>): Boolean = ticks.remove(element)

    override fun iterator(): MutableIterator<ScheduledTick<T>> = ticks.iterator()

    override val size: Int get() = ticks.size

    override fun clear() = ticks.clear()
}
