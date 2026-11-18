package com.synsenetwork.vanadium.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeveloperPropertiesTest {
    private val range = 0L..1_000_000L

    private fun parse(text: String?): Pair<Long, List<String>> {
        val warnings = ArrayList<String>()
        return DeveloperProperties.long("vanadium.test", 500L, range, text, warnings::add) to warnings
    }

    @Test
    fun validValuesApplyWithoutWarning() {
        assertEquals(500L to emptyList(), parse(null))
        assertEquals(250L to emptyList(), parse("250"))
        assertEquals(0L to emptyList(), parse(" 0 "))
        assertEquals(1_000_000L to emptyList(), parse("1000000"))
    }

    @Test
    fun invalidValuesWarnAndKeepTheDefault() {
        for (text in listOf("", "fast", "2.5", "NaN", "Infinity", "-1", "1000001", "99999999999999999999")) {
            val (value, warnings) = parse(text)
            assertEquals(500L, value, "'$text'")
            assertEquals(1, warnings.size, "'$text'")
            assertTrue("vanadium.test" in warnings[0] && "500" in warnings[0], warnings[0])
        }
    }

    @Test
    fun eachRetiredPropertySetWarnsOnce() {
        val set = mapOf("vanadium.prewakeLeadMicros" to "2000", "vanadium.idleWorkers" to "hot", "vanadium.workerSlackSpinMicros" to "250")
        val warnings = ArrayList<String>()
        DeveloperProperties.warnRetired(set::get, warnings::add)
        assertEquals(2, warnings.size, warnings.toString())
        assertTrue(warnings[0].startsWith("-Dvanadium.idleWorkers was removed and is ignored; set idleWorkers"), warnings[0])
        assertEquals("-Dvanadium.prewakeLeadMicros was removed and is ignored", warnings[1])

        val none = ArrayList<String>()
        DeveloperProperties.warnRetired({ null }, none::add)
        assertEquals(emptyList(), none)
    }

    @Test
    fun retiredPropertiesAreTheRemovedOverrides() {
        assertEquals(
            setOf(
                "vanadium.idleWorkers", "vanadium.workerSpinMicros", "vanadium.callerSpinMicros", "vanadium.serverSpinMicros",
                "vanadium.callerWakes", "vanadium.prewake", "vanadium.prewakeLeadMicros", "vanadium.prewakeGraceMicros",
            ),
            DeveloperProperties.RETIRED.keys,
        )
    }
}
