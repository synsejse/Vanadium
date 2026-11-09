package com.synsenetwork.vanadium.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat

abstract class PrepareRuntimeMod : DefaultTask() {
    @get:Input abstract val downloadUrl: Property<String>
    @get:Input abstract val sha512: Property<String>
    @get:OutputFile abstract val destination: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    private fun hash(path: java.nio.file.Path): String {
        val digest = MessageDigest.getInstance("SHA-512")
        DigestInputStream(Files.newInputStream(path), digest).use { input ->
            input.transferTo(OutputStream.nullOutputStream())
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    @TaskAction
    fun prepare() {
        val target = destination.get().asFile.toPath()
        if (!Files.exists(target)) {
            Files.createDirectories(target.parent)
            val temporary = Files.createTempFile(target.parent, "runtime-mod-", ".download")
            try {
                val connection = URI(downloadUrl.get()).toURL().openConnection().apply {
                    connectTimeout = 60_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "Vanadium-development")
                }
                connection.getInputStream().use { input -> Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING) }
                check(hash(temporary) == sha512.get()) { "Runtime mod checksum mismatch: ${downloadUrl.get()}" }
                Files.move(temporary, target)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
        check(hash(target) == sha512.get()) { "Runtime mod checksum mismatch: $target" }
        logger.lifecycle("Verified runtime mod: $target")
    }
}
