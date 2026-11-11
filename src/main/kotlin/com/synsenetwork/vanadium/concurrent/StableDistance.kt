package com.synsenetwork.vanadium.concurrent

import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap
import net.minecraft.world.entity.Entity
import java.util.function.ToDoubleFunction

/**
 * Sorting entities by distance while they move on other threads: a comparator that reads live positions can
 * contradict itself mid-sort, and the sort then throws ("Comparison method violates its general contract").
 */
object StableDistance {
    /** Orders by squared distance to [body], reading each entity's position once per sort. */
    @JvmStatic
    fun <T : Entity> comparator(body: Entity): Comparator<T> {
        val distances = Reference2DoubleOpenHashMap<Entity>()
        val measure = ToDoubleFunction<Entity> { body.distanceToSqr(it.position()) }
        return Comparator { a, b -> java.lang.Double.compare(distances.computeIfAbsent(a, measure), distances.computeIfAbsent(b, measure)) }
    }
}
