package com.synsenetwork.vanadium.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrepareRuntimeModTest {
    @TempDir lateinit var directory: Path

    private fun task(expected: ByteArray): PrepareRuntimeMod {
        val source = directory.resolve("source.jar").toFile().apply { writeBytes(expected) }
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        return project.tasks.register("prepareRuntimeMod", PrepareRuntimeMod::class.java).get().apply {
            downloadUrl.set(source.toURI().toString())
            sha512.set(MessageDigest.getInstance("SHA-512").digest(expected).joinToString("") { "%02x".format(it) })
            destination.set(directory.resolve("mods/runtime.jar").toFile())
        }
    }

    @Test
    fun onlyPublishesAVerifiedDownload() {
        val bytes = byteArrayOf(1, 2, 3)
        val task = task(bytes)
        task.prepare()
        assertContentEquals(bytes, task.destination.get().asFile.readBytes())
        directory.resolve("source.jar").toFile().delete()
        task.prepare() // A verified cached artifact does not need the download source.
    }

    @Test
    fun failedDownloadLeavesNoArtifactOrTemporaryFile() {
        val task = task(byteArrayOf(1, 2, 3))
        task.sha512.set("wrong")
        assertFailsWith<IllegalStateException> { task.prepare() }
        assertFalse(task.destination.get().asFile.exists())
        Files.list(directory.resolve("mods")).use { assertTrue(it.findAny().isEmpty) }
    }

    @Test
    fun tamperedCachedJarIsRejectedWithoutOverwritingIt() {
        val task = task(byteArrayOf(1, 2, 3))
        val target = task.destination.get().asFile
        target.parentFile.mkdirs()
        target.writeBytes(byteArrayOf(9))
        assertFailsWith<IllegalStateException> { task.prepare() }
        assertContentEquals(byteArrayOf(9), target.readBytes())
    }
}
