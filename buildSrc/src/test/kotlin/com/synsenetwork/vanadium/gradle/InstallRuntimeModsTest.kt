package com.synsenetwork.vanadium.gradle

import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir

class InstallRuntimeModsTest {
    @TempDir lateinit var directory: Path

    private fun jar(name: String, id: String, version: String = "1") = directory.resolve(name).toFile().apply {
        parentFile.mkdirs()
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            zip.write("""{"id":"$id","version":"$version"}""".toByteArray())
            zip.closeEntry()
        }
    }

    private fun task(): InstallRuntimeMods {
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        return project.tasks.register("setupRuntimeMods", InstallRuntimeMods::class.java).get().apply {
            runtimeMods.from(jar("cache/c2me-pinned.jar", "c2me"), jar("cache/lithium-pinned.jar", "lithium"))
            modsDirectory.set(directory.resolve("run/mods").toFile())
        }
    }

    @Test
    fun installsVerifiedInputsAndKeepsUnrelatedModsOnRepeatedRuns() {
        val task = task()
        val other = jar("run/mods/unrelated.jar", "other")
        val original = other.readBytes()
        task.install()
        task.install()
        for (source in task.runtimeMods.files) {
            assertContentEquals(source.readBytes(), directory.resolve("run/mods/${source.name}").toFile().readBytes())
        }
        assertContentEquals(original, other.readBytes())
        assertTrue(task.outputs.files.isEmpty, "Gradle cleanup must not own files in the user's mods directory")
    }

    @Test
    fun detectsOtherVersionsByModIdBeforeWritingAnyFiles() {
        val task = task()
        val conflict = jar("run/mods/renamed.jar", "c2me", "old")
        val original = conflict.readBytes()
        val error = assertFailsWith<GradleException> { task.install() }
        assertTrue("renamed.jar" in error.message.orEmpty())
        assertContentEquals(original, conflict.readBytes())
        assertFalse(directory.resolve("run/mods/lithium-pinned.jar").toFile().exists())
        assertFalse(directory.resolve("run/mods/c2me-pinned.jar").toFile().exists())
    }

    @Test
    fun refusesToOverwriteAnAlteredJarWithTheExpectedName() {
        val task = task()
        val conflict = jar("run/mods/c2me-pinned.jar", "c2me", "modified")
        val original = conflict.readBytes()
        assertFailsWith<GradleException> { task.install() }
        assertContentEquals(original, conflict.readBytes())
    }

    @Test
    fun refusesAConflictingFilenameEvenWhenItContainsAnotherMod() {
        val task = task()
        val conflict = jar("run/mods/c2me-pinned.jar", "other")
        val original = conflict.readBytes()
        assertFailsWith<GradleException> { task.install() }
        assertContentEquals(original, conflict.readBytes())
    }
}
