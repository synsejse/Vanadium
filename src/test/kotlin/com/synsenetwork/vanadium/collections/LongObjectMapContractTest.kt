package com.synsenetwork.vanadium.collections

import it.unimi.dsi.fastutil.longs.Long2ObjectFunction
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import java.util.function.LongFunction
import java.util.function.LongUnaryOperator
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The single-key and view contract shared by every [Long2ObjectMap] adapter, checked against fastutil's own
 * [Long2ObjectOpenHashMap]: one script of operations runs on each map and must give the same result at every step.
 * Behaviour particular to one adapter (atomicity, listeners, the concrete subtype's extra methods) stays in
 * [ConcurrentMapsTest] and [CompatMapsTest].
 */
class LongObjectMapContractTest {
    /** [defaults]: honours `defaultReturnValue`; [writableViews]: entry `setValue` and iterator `remove` write through. */
    private class Subject(val name: String, val defaults: Boolean, val writableViews: Boolean, val create: () -> Long2ObjectMap<String>)

    private val subjects = listOf(
        Subject("ConcurrentLongObjectMap", defaults = true, writableViews = true) { ConcurrentLongObjectMap() },
        Subject("ConcurrentLongObjectOpenHashMap", defaults = true, writableViews = true) { ConcurrentLongObjectOpenHashMap() },
        // Mutated only through put and remove, which report to the listener; its views are read-only and it keeps no default.
        Subject("ListeningConcurrentLongObjectMap", defaults = false, writableViews = false) {
            ListeningConcurrentLongObjectMap({ _, _, _ -> }, LongUnaryOperator { it shr 4 })
        },
    )

    @Test
    fun adaptersAnswerLikeFastutil() {
        for (subject in subjects) {
            val expected = script(Long2ObjectOpenHashMap(), subject)
            val actual = script(subject.create(), subject)
            for (i in expected.indices) assertEquals(expected[i], actual.getOrNull(i), "${subject.name}, step ${i + 1}")
            assertEquals(expected.size, actual.size, "${subject.name}: steps")
        }
    }

    /** Each step's description and result; views are recorded sorted, since iteration order is not part of the contract. */
    private fun script(map: Long2ObjectMap<String>, subject: Subject): List<Pair<String, Any?>> {
        val log = ArrayList<Pair<String, Any?>>()
        fun step(what: String, result: Any?) = log.add(what to result)
        fun views() {
            step("size", map.size)
            step("isEmpty", map.isEmpty())
            step("keys", map.keys.toLongArray().sorted())
            step("keys contain", KEYS.map { map.keys.contains(it) })
            step("values", map.values.sorted())
            step("entries", map.long2ObjectEntrySet().map { "${it.longKey}=${it.value}" }.sorted())
        }
        for (key in KEYS) {
            step("put($key) absent", map.put(key, "a$key"))
            step("put($key) present", map.put(key, "b$key"))
            step("get($key)", map.get(key))
            step("containsKey($key)", map.containsKey(key))
        }
        step("containsValue present", map.containsValue("b0"))
        step("containsValue absent", map.containsValue("a0"))
        views()
        step("get absent", map.get(ABSENT))
        step("containsKey absent", map.containsKey(ABSENT))
        step("getOrDefault present", map.getOrDefault(0L, "x"))
        step("getOrDefault absent", map.getOrDefault(ABSENT, "x"))
        step("remove(key, wrong value)", map.remove(-5L, "a-5" as Any?))
        step("remove(key, value)", map.remove(-5L, "b-5" as Any?))
        step("remove present", map.remove(0L))
        step("remove absent", map.remove(0L))
        step("putIfAbsent absent", map.putIfAbsent(0L, "p0"))
        step("putIfAbsent present", map.putIfAbsent(0L, "q0"))
        step("computeIfAbsent absent", map.computeIfAbsent(-5L, LongFunction { "c$it" }))
        step("computeIfAbsent present", map.computeIfAbsent(-5L, LongFunction { error("ran for a present key") }))
        step("computeIfAbsent partial, undefined", map.computeIfAbsent(ABSENT, UNDEFINED))
        step("computeIfPresent present", map.computeIfPresent(-5L) { k, v -> "$v/$k" })
        step("computeIfPresent absent", map.computeIfPresent(ABSENT) { _, _ -> error("ran for an absent key") })
        step("computeIfPresent to null", map.computeIfPresent(-5L) { _, _ -> null })
        step("compute absent to value", map.compute(7L) { k, v -> "n$k$v" })
        step("compute present to value", map.compute(7L) { k, v -> "$v/$k" })
        step("compute present to null", map.compute(7L) { _, _ -> null })
        step("merge absent", map.merge(8L, "m") { _, _ -> error("merged with an absent key") })
        step("merge present", map.merge(8L, "n") { old, new -> old + new })
        step("merge to null", map.merge(8L, "o") { _, _ -> null })
        step("replace present", map.replace(0L, "r0"))
        step("replace absent", map.replace(ABSENT, "r"))
        views()
        if (subject.writableViews) {
            for (entry in map.long2ObjectEntrySet()) entry.setValue("w${entry.longKey}")
            step("entry setValue writes through", KEYS.map { map.get(it) })
            val iterator = map.long2ObjectEntrySet().iterator()
            val removed = iterator.next().longKey
            iterator.remove()
            step("iterator remove writes through", map.containsKey(removed) to map.size)
        }
        map.clear()
        views()
        if (subject.defaults) {
            map.defaultReturnValue("missing")
            step("default get", map.get(ABSENT))
            step("default put", map.put(1L, "v"))
            step("default remove", map.remove(ABSENT))
            step("default putIfAbsent", map.putIfAbsent(2L, "v"))
            step("default computeIfPresent absent", map.computeIfPresent(ABSENT) { _, _ -> error("ran for an absent key") })
            step("default compute to null", map.compute(ABSENT) { _, _ -> null })
            step("default computeIfPresent to null", map.computeIfPresent(1L) { _, _ -> null })
            step("default merge to null", map.merge(2L, "x") { _, _ -> null })
            step("default replace absent", map.replace(ABSENT, "r"))
            views()
        }
        return log
    }

    private companion object {
        /** Negative, zero, packed-position-looking and extreme keys. */
        val KEYS = listOf(-5L, 0L, 1L shl 40, 0x1_0000_0001L * -2048, Long.MIN_VALUE, Long.MAX_VALUE)
        const val ABSENT = 123_456_789L

        /** A partial function defined nowhere: computeIfAbsent leaves the map unchanged. */
        val UNDEFINED = object : Long2ObjectFunction<String> {
            override fun get(key: Long): String = error("undefined key mapped")
            override fun containsKey(key: Long) = false
        }
    }
}
