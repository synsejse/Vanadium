package com.synsenetwork.vanadium.tick

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ParticipantCeilingTest {
    private fun parse(text: String?) = ParticipantCeiling.parse("vanadium.spawnParticipants", text)

    @Test
    fun absentEmptyAndAutoAreTheCurrentDefault() {
        for (text in listOf(null, "", "  ", "auto", "AUTO", " Auto ")) {
            val ceiling = parse(text)
            assertSame(ParticipantCeiling.AUTO, ceiling, "'$text'")
            assertEquals("auto", ceiling.label)
            assertEquals(37, ceiling.apply(37), "auto never lowers the estimate")
        }
    }

    @Test
    fun countsBoundTheEstimateWithoutRaisingIt() {
        assertEquals(ParticipantCeiling.of(4), parse(" 4 "))
        assertEquals("4", parse("4").label)
        assertEquals(4, parse("4").apply(25))
        assertEquals(3, parse("4").apply(3), "a wave estimated below the ceiling keeps its estimate")
        assertEquals(1, parse("1").apply(25))
        assertEquals(1, parse("8").apply(1))
    }

    /** 0 could mean "no limit" or "no threads"; neither is guessed, and typos do not silently measure `auto`. */
    @Test
    fun rejectsZeroNegativeAndMalformedValues() {
        for (text in listOf("0", "-1", "four", "1.5", "4x", "2147483648")) {
            val failure = assertFailsWith<IllegalArgumentException>("'$text'") { parse(text) }
            assertTrue("vanadium.spawnParticipants" in failure.message.orEmpty())
        }
        assertFailsWith<IllegalArgumentException> { ParticipantCeiling.of(0) }
    }
}
