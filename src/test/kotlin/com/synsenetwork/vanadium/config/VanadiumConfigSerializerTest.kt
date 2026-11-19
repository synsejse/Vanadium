package com.synsenetwork.vanadium.config

import me.shedaniel.cloth.clothconfig.shadowed.com.moandjiezana.toml.Toml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VanadiumConfigSerializerTest {
    private val oldFile = """
        enabled = true
        parallelChunkLoads = false
        isolation = 3
        consolidateFlushes = false
    """.trimIndent()

    @Test
    fun anOldFileWithARemovedOptionStillLoadsEverythingElse() {
        // What AutoConfig's TOML serializer does with the file: unknown keys are skipped.
        val config = Toml().read(oldFile).to(VanadiumConfig::class.java)
        assertEquals(3, config.isolation)
        assertFalse(config.consolidateFlushes)
        assertTrue(config.enabled)
        assertFalse(VanadiumConfig::class.java.declaredFields.any { it.name == "parallelChunkLoads" })
    }

    @Test
    fun aRemovedOptionWarnsOnceByName() {
        val warnings = ArrayList<String>()
        VanadiumConfigSerializer.warnRetired(Toml().read(oldFile), "vanadium.toml", warnings::add)
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue("'parallelChunkLoads'" in warnings[0] && "removed" in warnings[0] && "vanadium.toml" in warnings[0], warnings[0])

        warnings.clear()
        VanadiumConfigSerializer.warnRetired(Toml().read("isolation = 2"), "vanadium.toml", warnings::add)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun retiredOptionsAreNotConfigFields() {
        val fields = VanadiumConfig::class.java.declaredFields.map { it.name }.toSet()
        for (key in VanadiumConfigSerializer.RETIRED.keys) assertFalse(key in fields, key)
    }
}
