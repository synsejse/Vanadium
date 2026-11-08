package com.synsenetwork.vanadium.config

import net.minecraft.resources.Identifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VanadiumConfigTest {
    @Test
    fun workerCountResolution() {
        assertEquals(72, VanadiumConfig.resolveWorkers(0, 72), "<= 0 means available processors")
        assertEquals(8, VanadiumConfig.resolveWorkers(-1, 8))
        assertEquals(16, VanadiumConfig.resolveWorkers(16, 72))
        assertEquals(2, VanadiumConfig.resolveWorkers(1, 72), "explicit values are floored at two")
        assertEquals(72, VanadiumConfig.resolveWorkers(200, 72), "and capped at available processors")
        assertEquals(2, VanadiumConfig.resolveWorkers(16, 1), "the floor wins over a single processor")
        assertEquals(1, VanadiumConfig.resolveWorkers(0, 1))
    }

    @Test
    fun autoCellSizeShrinksWithWorkersWithinRange() {
        assertTrue(VanadiumConfig.autoCellSize(16) <= VanadiumConfig.autoCellSize(4))
        for (parallelism in -1..256) assertTrue(VanadiumConfig.autoCellSize(parallelism) in 2..8, "at $parallelism")
    }

    @Test
    fun exactIdsAreDeduplicated() {
        val rules = TypeRules.parse(listOf("minecraft:pig", "example:machine", "minecraft:pig"))
        assertEquals(2, rules.size)
        assertTrue(Identifier.parse("example:machine") in rules)
        assertFalse(Identifier.parse("example:other") in rules)
    }

    @Test
    fun malformedRulesAreRejected() {
        for (invalid in listOf("pig", "minecraft:*", "Minecraft:pig", "minecraft:pig,", ":pig", "minecraft:")) {
            assertFailsWith<IllegalArgumentException>(invalid) { TypeRules.parse(listOf(invalid)) }
        }
    }

    @Test
    fun ruleListChangesRecompile() {
        val rules = TypeRules()
        val pig = Identifier.parse("minecraft:pig")
        repeat(2) {
            rules.update(listOf("minecraft:pig"))
            assertTrue(pig in rules)
            rules.update(emptyList())
            assertFalse(pig in rules)
        }
        val config = VanadiumConfig()
        config.serialEntityTypes.add("minecraft:pig")
        config.refreshSerialRules()
        assertTrue(config.isSerialEntity(pig))
        assertFalse(config.isSerialBlockEntity(pig))
        config.serialEntityTypes.clear()
        config.refreshSerialRules()
        assertFalse(config.isSerialEntity(pig))
        assertFalse(config.hasSerialEntityRules())
        config.serialBlockEntityTypes.add("minecraft:pig")
        config.refreshSerialRules()
        assertTrue(config.isSerialBlockEntity(pig))
    }
}
