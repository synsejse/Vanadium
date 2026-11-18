package com.synsenetwork.vanadium.live

import java.io.IOException
import java.io.StringWriter
import java.io.Writer
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FixtureOutputTest {
    @Test
    fun valuesKeepTheFixtureCsvRules() {
        val previous = Locale.getDefault()
        // A decimal comma locale must not leak into the CSV.
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals("", formatValue(null))
            assertEquals("1.500", formatValue(1.5))
            assertEquals("-2.250", formatValue(-2.25))
            assertEquals("12345678", formatValue(12_345_678L))
            assertEquals("7", formatValue(7))
            assertEquals("2.5", formatValue(2.5f))
            assertEquals("true", formatValue(true))
            assertEquals("entity_wave_worker", formatValue("entity_wave_worker"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun linesEndInNewlineAndWriteErrorsThrow() {
        val out = StringWriter()
        out.line("a,b").line("")
        assertEquals("a,b\n\n", out.toString())
        val failing = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, length: Int) = throw IOException("closed")
            override fun flush() = Unit
            override fun close() = Unit
        }
        assertEquals("closed", assertFailsWith<IOException> { failing.line("x") }.message)
    }
}
