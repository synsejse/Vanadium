package com.synsenetwork.vanadium.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheckModDependenciesTest {
    private val manifest = """
        {
          "schemaVersion": 1,
          "version": "${'$'}{version}",
          "depends": { "fabricloader": ">=0.20.1", "minecraft": "26.3", "lithium": ">=0.26.2", "java": ">=25" }
        }
    """.trimIndent()

    @Test
    fun matchingConstraintsPass() {
        val depends = CheckModDependencies.depends(manifest)
        assertEquals(">=0.20.1", depends["fabricloader"])
        assertTrue(CheckModDependencies.drift(depends, mapOf("fabricloader" to ">=0.20.1", "minecraft" to "26.3", "java" to ">=25")).isEmpty())
    }

    @Test
    fun driftedAndMissingConstraintsAreReported() {
        val problems = CheckModDependencies.drift(CheckModDependencies.depends(manifest),
            mapOf("lithium" to ">=0.26.3", "c2me" to ">=0.4.2-alpha.0.116", "minecraft" to "26.3"))
        assertEquals(listOf("depends.lithium is \">=0.26.2\", expected \">=0.26.3\"",
            "depends.c2me is missing, expected \">=0.4.2-alpha.0.116\""), problems)
    }
}
