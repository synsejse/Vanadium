package com.synsenetwork.vanadium.packaging

import com.google.gson.JsonParser
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DistributionTest {
    private fun release() = ZipFile(System.getProperty("vanadium.releaseJar"))

    @Test
    fun concurrentMapLibraryIsDeclaredAndBundled() = release().use { jar ->
        val metadata = jar.getInputStream(jar.getEntry("fabric.mod.json")).reader().use {
            JsonParser.parseReader(it).asJsonObject
        }
        val path = metadata.getAsJsonArray("jars").map { it.asJsonObject["file"].asString }
            .single { it.substringAfterLast('/').startsWith("jctools-core-") }
        val entry = jar.getEntry(path)
        assertTrue(entry != null, "Declared JCTools dependency missing from release")
        ZipInputStream(jar.getInputStream(entry)).use { dependency ->
            assertTrue(generateSequence { dependency.nextEntry }.any { it.name == "org/jctools/maps/NonBlockingHashMapLong.class" },
                "Bundled jar does not provide the primitive concurrent map")
        }
    }

    @Test
    fun releaseContainsOnlyProductionCodeWithMatchingMetadata() = release().use { jar ->
        val names = jar.entries().asSequence().map { it.name }.toList()
        assertFalse(names.any { name ->
            listOf("integration", "featurebench", "live", "bench", "packaging").any {
                name.startsWith("com/synsenetwork/vanadium/$it/")
            }
        }, "Test fixtures or their automatic server shutdown helper leaked into the release")
        assertFalse(names.any { it.endsWith("Test.class") }, "JUnit test classes leaked into the release")
        val metadata = jar.getInputStream(jar.getEntry("fabric.mod.json")).reader().use {
            JsonParser.parseReader(it).asJsonObject
        }
        assertEquals(System.getProperty("vanadium.expectedVersion"), metadata["version"].asString)
        assertEquals(System.getProperty("vanadium.expectedMinecraft"), metadata.getAsJsonObject("depends")["minecraft"].asString)
        assertTrue(metadata.getAsJsonObject("depends").has("c2me"))
        for (entry in names.filter { it.endsWith(".class") && it.startsWith("com/synsenetwork/vanadium/") }) {
            jar.getInputStream(jar.getEntry(entry)).use { input ->
                val header = input.readNBytes(8)
                assertEquals(69, (header[6].toInt() and 255) * 256 + (header[7].toInt() and 255), entry)
            }
        }
    }

    @Test
    fun everyRegisteredMixinIsPresentInTheRelease() = release().use { jar ->
        val config = jar.getInputStream(jar.getEntry("vanadium.mixins.json")).reader().use {
            JsonParser.parseReader(it).asJsonObject
        }
        val prefix = config["package"].asString.replace('.', '/')
        for (section in listOf("mixins", "client", "server")) {
            val entries = config.getAsJsonArray(section) ?: continue
            for (mixin in entries) {
                val path = "$prefix/${mixin.asString.replace('.', '/')}.class"
                assertTrue(jar.getEntry(path) != null, "Registered mixin missing from release: $path")
            }
        }
    }
}
