package com.synsenetwork.vanadium.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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

    private fun share(text: String?): Pair<Double, List<String>> {
        val warnings = ArrayList<String>()
        return DeveloperProperties.double("vanadium.share", 0.8, "a share", text, warnings::add) { it >= 0.0 && it <= 1.0 } to warnings
    }

    @Test
    fun doublesApplyOnlyWhenFiniteAndAccepted() {
        assertEquals(0.8 to emptyList(), share(null))
        assertEquals(0.5 to emptyList(), share(" 0.5 "))
        assertEquals(0.0 to emptyList(), share("0"))
        assertEquals(1.0 to emptyList(), share("1"))
        assertEquals(0.25 to emptyList(), share("2.5e-1"))
        for (text in listOf("", "fast", "NaN", "-NaN", "Infinity", "-Infinity", "-0.1", "1.01", "1e400")) {
            val (value, warnings) = share(text)
            assertEquals(0.8, value, "'$text'")
            assertEquals(1, warnings.size, "'$text'")
            assertTrue("vanadium.share" in warnings[0] && "0.8" in warnings[0], warnings[0])
        }
    }

    @Test
    fun booleansAcceptOnlyTrueOrFalse() {
        val warnings = ArrayList<String>()
        fun parse(text: String?) = DeveloperProperties.boolean("vanadium.flag", true, text, warnings::add)
        assertEquals(true, parse(null))
        assertEquals(false, parse("false"))
        assertEquals(false, parse(" FALSE "))
        assertEquals(true, parse("True"))
        assertEquals(emptyList(), warnings)
        for (text in listOf("", "no", "0", "off", "yes")) assertEquals(true, parse(text), "'$text'")
        assertEquals(5, warnings.size, warnings.toString())
        assertTrue(warnings.all { "vanadium.flag" in it && "true or false" in it }, warnings.toString())
    }

    @Test
    fun participantLimitIsAutoOrAPositiveCount() {
        for (text in listOf(null, "", "  ", "auto", "AUTO", " Auto ")) {
            assertNull(DeveloperProperties.participantLimit("vanadium.spawnParticipants", text), "'$text'")
        }
        assertEquals(4, DeveloperProperties.participantLimit("vanadium.spawnParticipants", " 4 "))
        assertEquals(1, DeveloperProperties.participantLimit("vanadium.spawnParticipants", "1"))
    }

    /** 0 could mean "no limit" or "no threads"; neither is guessed, and typos do not silently measure `auto`. */
    @Test
    fun participantLimitRejectsZeroNegativeAndMalformedValues() {
        for (text in listOf("0", "-1", "four", "1.5", "4x", "2147483648")) {
            val failure = assertFailsWith<IllegalArgumentException>("'$text'") {
                DeveloperProperties.participantLimit("vanadium.spawnParticipants", text)
            }
            assertTrue("vanadium.spawnParticipants" in failure.message.orEmpty())
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
