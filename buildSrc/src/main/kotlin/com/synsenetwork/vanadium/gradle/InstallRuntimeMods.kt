package com.synsenetwork.vanadium.gradle

import groovy.json.JsonSlurper
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/** Installs verified release jars without replacing the developer's mods or removing other versions. */
@UntrackedTask(because = "The interactive mods directory is user-owned; installation checks conflicts on every run")
abstract class InstallRuntimeMods : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val runtimeMods: ConfigurableFileCollection

    @get:Internal abstract val modsDirectory: DirectoryProperty

    @TaskAction
    fun install() {
        val sources = runtimeMods.files.associateBy { file ->
            requireNotNull(modId(file)) { "Runtime jar has no Fabric metadata: $file" }
        }
        require(sources.size == runtimeMods.files.size) { "Runtime jars must have distinct Fabric mod IDs" }
        val sourceNames = sources.values.associateBy { it.name }
        val directory = modsDirectory.get().asFile
        // Check the entire installation before writing either jar; filenames alone do not identify a mod.
        if (directory.exists()) {
            Files.newDirectoryStream(directory.toPath(), "*.jar").use { installed ->
                for (path in installed) {
                    val file = path.toFile()
                    if (!file.isFile) continue
                    val source = sources[modId(file)] ?: sourceNames[file.name] ?: continue
                    if (file.name != source.name || Files.mismatch(path, source.toPath()) != -1L) {
                        throw GradleException("Conflicting runtime mod: $file. Move it aside before installing ${source.name}.")
                    }
                }
            }
        }
        for (source in sources.values) {
            val target = directory.resolve(source.name).toPath()
            if (Files.exists(target)) continue
            Files.createDirectories(target.parent)
            val temporary = Files.createTempFile(target.parent, "runtime-mod-", ".install")
            try {
                Files.copy(source.toPath(), temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                Files.move(temporary, target)
            } finally {
                Files.deleteIfExists(temporary)
            }
            logger.lifecycle("Installed runtime mod: $target")
        }
    }

    private fun modId(file: File): String? = ZipFile(file).use { jar ->
        val metadata = jar.getEntry("fabric.mod.json") ?: return null
        val data = jar.getInputStream(metadata).reader().use { JsonSlurper().parse(it) } as Map<*, *>
        requireNotNull(data["id"] as? String) { "Fabric metadata has no mod ID: $file" }
    }
}
