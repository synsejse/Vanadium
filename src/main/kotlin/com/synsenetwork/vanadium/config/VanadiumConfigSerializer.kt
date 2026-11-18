package com.synsenetwork.vanadium.config

import java.nio.file.Files
import java.nio.file.Path
import me.shedaniel.autoconfig.annotation.Config
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer
import me.shedaniel.cloth.clothconfig.shadowed.com.moandjiezana.toml.Toml
import net.fabricmc.loader.api.FabricLoader
import org.apache.logging.log4j.LogManager

/**
 * AutoConfig's TOML serializer for `config/vanadium.toml`. Toml4j ignores keys the config class no longer has, so an
 * old file still loads; each load (startup, `/vanadium reload`) also warns once for every [RETIRED] key the file
 * still sets. AutoConfig rewrites the file after a successful startup load, which drops such keys.
 */
internal class VanadiumConfigSerializer(definition: Config, type: Class<VanadiumConfig>) :
    Toml4jConfigSerializer<VanadiumConfig>(definition, type) {
    private val path: Path = FabricLoader.getInstance().configDir.resolve("${definition.name}.toml")

    override fun deserialize(): VanadiumConfig {
        // Parse errors surface from the real load first, with AutoConfig's handling.
        val config = super.deserialize()
        if (Files.exists(path)) warnRetired(Toml().read(path.toFile()), path.fileName.toString())
        return config
    }

    companion object {
        private val LOGGER = LogManager.getLogger("Vanadium")

        /** Removed options, with why nothing replaces them. */
        val RETIRED: Map<String, String> = linkedMapOf(
            "parallelChunkLoads" to "chunk loads always run under per-chunk locks on the server thread",
        )

        /** Warns once for each [RETIRED] key that [toml], read from [file], sets. */
        fun warnRetired(toml: Toml, file: String, warn: (String) -> Unit = LOGGER::warn) {
            for ((key, reason) in RETIRED) {
                if (toml.contains(key)) warn("Config option '$key' in $file was removed and is ignored: $reason")
            }
        }
    }
}
