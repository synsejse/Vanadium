package com.synsenetwork.vanadium.integration

import com.synsenetwork.vanadium.Vanadium
import com.synsenetwork.vanadium.config.VanadiumConfig
import com.synsenetwork.vanadium.live.runCommand
import java.util.concurrent.atomic.AtomicBoolean
import me.shedaniel.autoconfig.AutoConfig
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
    }
}
