package com.synsenetwork.vanadium.config

import com.synsenetwork.vanadium.tick.IdleWorkers
import net.minecraft.resources.Identifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VanadiumConfigTest {
    @Test
    fun autoWorkersLeaveOnePhysicalCoreForTheServerThread() {
        assertEquals(35, VanadiumConfig.resolveWorkers(0, 72, 36), "two sockets x 18 cores x 2 threads")
        assertEquals(35, VanadiumConfig.resolveWorkers(-1, 36, 36), "no SMT: same cores, same count")
        assertEquals(15, VanadiumConfig.resolveWorkers(0, 32, 16), "16 cores with SMT")
        assertEquals(23, VanadiumConfig.resolveWorkers(0, 32, 24), "hybrid: 8 SMT cores + 16 single-thread cores")
    }

    @Test
    fun autoWorkersOnSmallMachines() {
        assertEquals(7, VanadiumConfig.resolveWorkers(0, 8, 8), "8 cores, no SMT")
        assertEquals(3, VanadiumConfig.resolveWorkers(0, 8, 4), "4 cores with SMT")
        assertEquals(3, VanadiumConfig.resolveWorkers(0, 4, 4), "4 cores, no SMT")
        assertEquals(1, VanadiumConfig.resolveWorkers(0, 4, 2), "2 cores with SMT")
        assertEquals(1, VanadiumConfig.resolveWorkers(0, 2, 2), "2 cores, no SMT")
        assertEquals(1, VanadiumConfig.resolveWorkers(0, 2, 1), "1 core with SMT: never below one")
        assertEquals(1, VanadiumConfig.resolveWorkers(0, 1, 1))
    }

    @Test
    fun autoWorkersRespectAProcessorQuotaBelowTheCoreCount() {
        // A cgroup quota (or -XX:ActiveProcessorCount) lowers availableProcessors but not the cores the process may use.
        assertEquals(3, VanadiumConfig.resolveWorkers(0, 4, 36), "quota of 4 CPUs on the 72-thread machine")
        assertEquals(35, VanadiumConfig.resolveWorkers(0, 48, 36), "a quota above the core count changes nothing")
    }

    @Test
    fun autoWorkersFallBackToTwoThirdsWithoutTopology() {
        assertEquals(48, VanadiumConfig.resolveWorkers(0, 72, null))
        assertEquals(5, VanadiumConfig.resolveWorkers(0, 8, null), "rounded down")
        assertEquals(2, VanadiumConfig.resolveWorkers(0, 4, null))
        assertEquals(1, VanadiumConfig.resolveWorkers(0, 2, null), "but never below one")
        assertEquals(1, VanadiumConfig.resolveWorkers(0, 1, null))
    }

    @Test
    fun explicitWorkerCountsIgnoreTopology() {
        for (cores in listOf(36, null)) {
            assertEquals(16, VanadiumConfig.resolveWorkers(16, 72, cores))
            assertEquals(48, VanadiumConfig.resolveWorkers(48, 72, cores), "explicit values may exceed the cores")
            assertEquals(2, VanadiumConfig.resolveWorkers(1, 72, cores), "explicit values are floored at two")
            assertEquals(72, VanadiumConfig.resolveWorkers(200, 72, cores), "and capped at available processors")
            assertEquals(2, VanadiumConfig.resolveWorkers(16, 1, cores), "the floor wins over a single processor")
        }
    }

    @Test
    fun isolationMustBePositive() {
        val config = VanadiumConfig()
        assertEquals(2, config.isolation)
        config.isolation = 0
        assertFailsWith<me.shedaniel.autoconfig.ConfigData.ValidationException> { config.validatePostLoad() }
    }

    @Test
    fun idleWorkersParsesKnownModesAndRejectsOthers() {
        val config = VanadiumConfig()
        assertEquals(IdleWorkers.LEAN, config.idleWorkersMode())
        config.idleWorkers = "HOT"
        config.validatePostLoad()
        assertEquals(IdleWorkers.HOT, config.idleWorkersMode())
        config.idleWorkers = "lean"
        assertEquals(IdleWorkers.LEAN, config.idleWorkersMode(), "a changed value is reparsed")
        config.idleWorkers = "warm"
        assertFailsWith<me.shedaniel.autoconfig.ConfigData.ValidationException> { config.validatePostLoad() }
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
