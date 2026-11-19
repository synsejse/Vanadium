package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.config.VanadiumConfig
import com.synsenetwork.vanadium.live.runCommand
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import me.shedaniel.autoconfig.AutoConfig
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.world.InteractionResult

internal object ConfigChecks {
    fun run(server: MinecraftServer) {
        val holder = AutoConfig.getConfigHolder(VanadiumConfig::class.java)
        val original = Vanadium.config
        val reject = AtomicBoolean(true)
        // A load listener exercises AutoConfig's real failure/reset path without producing an expected error log.
        holder.registerLoadListener { _, _ -> if (reject.get()) InteractionResult.FAIL else InteractionResult.PASS }
        try {
            check(server.runCommand("vanadium reload") == 0)
            check(Vanadium.config === original) { "failed reload replaced the running config" }
            check(holder.config === original) { "failed reload left AutoConfig and the running config out of sync" }
            reject.set(false)
            check(server.runCommand("vanadium reload") == 1)
            check(Vanadium.config === holder.config && Vanadium.config !== original)
        } finally {
            reject.set(false)
        }
        println("CONFIG_RELOAD_CHECKS_PASSED")
        checkRetiredOption(server)
    }

    /** A file that still sets a removed option reloads, ignores it and says so once. */
    private fun checkRetiredOption(server: MinecraftServer) {
        // AutoConfig wrote the file at startup; top-level keys come first in TOML, so prepend the retired one.
        val file = FabricLoader.getInstance().configDir.resolve("vanadium.toml")
        val saved = Files.readString(file)
        val retired = LogCapture { message -> if ("Config option 'parallelChunkLoads'" in message) "retired" else null }
        try {
            Files.writeString(file, "parallelChunkLoads = false\nslowWaveMillis = 7\n" + saved.replace(Regex("(?m)^slowWaveMillis\\s*=.*\n"), ""))
            check(server.runCommand("vanadium reload") == 1) { "a config with a removed option did not reload" }
            check(Vanadium.config.slowWaveMillis == 7) { "the rest of a config with a removed option was not applied" }
            retired.mark("reloaded")
            val seen = retired.until("reloaded")
            check(seen == listOf("retired")) { "expected one removal warning for parallelChunkLoads, got $seen" }
        } finally {
            retired.close()
            Files.writeString(file, saved)
        }
        check(server.runCommand("vanadium reload") == 1)
        println("RETIRED_CONFIG_OPTION_CHECKS_PASSED")
    }
}
