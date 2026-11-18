package com.synsenetwork.vanadium.featurebench

import java.io.IOException
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class MeasurementsTest {
    @TempDir
    lateinit var directory: Path

    private fun measurements(filter: String, rounds: Int = 2) =
        Measurements(directory.resolve("feature-bench"), filter, rounds, scale = 0.0001, jfr = false)

    @Test
    fun selectedCaseWritesAlternatingPairs() {
        val arms = ArrayList<Boolean>()
        measurements("chosen", rounds = 4).use { bench ->
            bench.compare("skipped", { error("an unselected case ran") }) {}
            bench.compare("chosen", { arms.add(it) }) {}
            bench.requireSelection()
        }
        // Warmup off then on, AB/BA rounds, then the case leaves the feature on.
        assertEquals(listOf(false, true) + listOf(false, true, true, false, false, true, true, false) + true, arms)
        val lines = directory.resolve("feature-bench/samples.csv").readLines()
        assertEquals("case,enabled,round,operations,wall_ns,caller_cpu_ns,worker_cpu_ns,allocated_bytes", lines[0])
        assertEquals(1 + 4 * 2, lines.size)
        val rows = lines.drop(1).map { it.split(',') }
        assertTrue(rows.all { it.size == 8 && it[0] == "chosen" && it.drop(2).all { cell -> cell.toLongOrNull() != null } }, "$rows")
        assertEquals(listOf("false", "true", "true", "false", "false", "true", "true", "false"), rows.map { it[1] })
        assertEquals(listOf("0", "0", "1", "1", "2", "2", "3", "3"), rows.map { it[2] })
    }

    @Test
    fun emptyFilterSelectsEveryCase() {
        val ran = ArrayList<String>()
        measurements("").use { bench ->
            for (name in listOf("a", "b")) bench.compare(name, {}) { if (ran.lastOrNull() != name) ran.add(name) }
            bench.requireSelection()
        }
        assertEquals(listOf("a", "b"), ran)
    }

    @Test
    fun unmatchedFilterFailsNamingTheCases() {
        measurements("nav.*").use { bench ->
            bench.compare("tracking-8-players-256-mobs", { error("an unselected case ran") }) {}
            bench.compare("spawning", { error("an unselected case ran") }) {}
            val failure = assertFailsWith<IllegalStateException> { bench.requireSelection() }
            assertEquals("bench.filter 'nav.*' matched no feature benchmark case; cases: tracking-8-players-256-mobs, spawning", failure.message)
        }
    }

    @Test
    fun invalidFilterFailsAtStart() {
        val failure = assertFailsWith<IllegalArgumentException> { measurements("(") }
        assertEquals("bench.filter '(' is not a valid regular expression", failure.message)
        assertTrue(Files.notExists(directory.resolve("feature-bench")), "an invalid filter opened the samples file")
    }

    @Test
    fun writeFailuresPropagate() {
        val failing = object : StringWriter() {
            override fun flush() = throw IOException("disk full")
        }
        Measurements(directory, "", rounds = 1, scale = 0.0001, jfr = false, sampleWriter = failing).use { bench ->
            val failure = assertFailsWith<IOException> { bench.compare("case", {}) {} }
            assertEquals("disk full", failure.message)
        }
        assertTrue(Files.notExists(directory.resolve("samples.csv")))
    }
}
