package com.synsenetwork.vanadium.concurrent

import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap
import java.util.function.ToDoubleFunction
import net.minecraft.world.entity.Entity

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
        return Comparator { a, b -> distances.computeIfAbsent(a, measure).compareTo(distances.computeIfAbsent(b, measure)) }
    }
}
