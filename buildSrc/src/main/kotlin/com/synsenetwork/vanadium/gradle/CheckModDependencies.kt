package com.synsenetwork.vanadium.gradle

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a hand-written `depends` constraint in `fabric.mod.json` drifts from the version the build pins. The
 * minima are the pinned versions on purpose: only the pins are compiled against and run by the live tests, so a pin
 * change must update the manifest in the same change (and an older minimum needs evidence it still works).
 */
abstract class CheckModDependencies : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifest: RegularFileProperty

    /** Mod id to the exact constraint the manifest must declare. */
    @get:Input abstract val expected: MapProperty<String, String>

    /** Written on success so an unchanged manifest and pin set stays up to date. */
    @get:OutputFile abstract val marker: RegularFileProperty

    @TaskAction
    fun checkConstraints() {
        val file = manifest.get().asFile
        val problems = drift(depends(file.readText()), expected.get())
        if (problems.isNotEmpty()) {
            throw GradleException(problems.joinToString("\n", "$file disagrees with the pinned versions:\n", postfix = "\n" +
                "Update its depends block to the pins (gradle.properties, Java toolchain).") { "  $it" })
        }
        marker.get().asFile.writeText(expected.get().entries.joinToString("\n", postfix = "\n") { (id, constraint) -> "$id $constraint" })
    }

    companion object {
        fun depends(manifest: String): Map<String, Any?> {
            val root = JsonSlurper().parseText(manifest) as? Map<*, *> ?: return emptyMap()
            return (root["depends"] as? Map<*, *>).orEmpty().entries.associate { (id, constraint) -> "$id" to constraint }
        }

        fun drift(depends: Map<String, Any?>, expected: Map<String, String>): List<String> = expected.mapNotNull { (id, constraint) ->
            when (val actual = depends[id]) {
                null -> "depends.$id is missing, expected \"$constraint\""
                constraint -> null
                else -> "depends.$id is \"$actual\", expected \"$constraint\""
            }
        }
    }
}
