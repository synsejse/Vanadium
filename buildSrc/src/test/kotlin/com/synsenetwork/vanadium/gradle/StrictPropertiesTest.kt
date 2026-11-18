package com.synsenetwork.vanadium.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StrictPropertiesTest {
    private fun properties(vararg values: Pair<String, String>) = StrictProperties(mapOf(*values)::get)

    private fun message(block: () -> Unit) = assertFailsWith<IllegalArgumentException>(block = block).message

    @Test
    fun unsetPropertiesTakeTheirDefaults() {
        val properties = properties()
        assertEquals("sham", properties.choice("cold.arm", "sham", listOf("sham", "load")))
        assertEquals(8, properties.int("cold.blasts", 8, 2..11))
        assertNull(properties.intOrNull("mixed.c2me", 1..1_024))
        assertEquals(true, properties.bool("mixed.parallelSpawning", true))
        assertNull(properties["mixed.heap"])
    }

    @Test
    fun setValuesAreParsedStrictly() {
        val properties = properties("a" to "11", "b" to "false", "c" to "load", "d" to "1")
        assertEquals(11, properties.int("a", 8, 2..11))
        assertEquals(false, properties.bool("b", true))
        assertEquals("load", properties.choice("c", "sham", listOf("sham", "load")))
        assertEquals(1, properties.intOrNull("d", 1..1_024))
    }

    @Test
    fun invalidValuesKeepTheirMessages() {
        assertEquals("cold.blasts must be an integer in 2..11, not '12'", message { properties("cold.blasts" to "12").int("cold.blasts", 8, 2..11) })
        assertEquals("mixed.c2me must be an integer in 1..1024, not 'x'", message { properties("mixed.c2me" to "x").intOrNull("mixed.c2me", 1..1_024) })
        assertEquals("mixed.sprint must be true or false, not 'True'", message { properties("mixed.sprint" to "True").bool("mixed.sprint", false) })
        assertEquals("mixed.gc must be one of [g1, zgc], not 'shenandoah'",
            message { MixedBenchSettings(mapOf("mixed.gc" to "shenandoah")::get) })
        assertEquals("mixed.mode must be one of [enabled, serial], not 'off'", message { MixedBenchSettings(mapOf("mixed.mode" to "off")::get) })
        assertEquals("cold.arm must be one of [sham, warmup, load], not 'tnt'", message { ColdExplosionSettings(mapOf("cold.arm" to "tnt")::get) })
    }
}
