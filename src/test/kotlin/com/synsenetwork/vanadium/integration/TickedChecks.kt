package com.synsenetwork.vanadium.integration

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer

/**
 * Checks that need real server ticks. Each step runs at the end of a tick until it returns true;
 * a step that throws, or a sequence that outlasts [maxTicks], fails the live run. Sprinting removes
 * the normal 50 ms tick pacing, not the tick callbacks, chunk-system work or completion barriers.
 */
internal class TickedChecks(private val server: MinecraftServer, private val name: String) {
    private class Step(val label: String, val paced: Boolean, val run: () -> Boolean)
    private val steps = ArrayList<Step>()
    private val maxTicks = System.getProperty("vanadium.live.ticks", "500").toInt().also {
        require(it in 1..100_000) { "live.ticks must be between 1 and 100000" }
    }

    /** A step that is done after one tick. */
    fun then(label: String, action: () -> Unit) = until(label) { action(); true }

    fun until(label: String, step: () -> Boolean) {
        steps.add(Step(label, false, step))
    }

    /** Waits up to [ticks] ticks for [condition]. */
    fun await(label: String, ticks: Int, condition: () -> Boolean) {
        await(label, ticks, false, condition)
    }

    /** Async IO/ticket progress needs elapsed time as well as ticks. Pace only this wait at 20 TPS. */
    fun awaitAsync(label: String, ticks: Int, condition: () -> Boolean) {
        await(label, ticks, true, condition)
    }

    private fun await(label: String, ticks: Int, paced: Boolean, condition: () -> Boolean) {
        var waited = 0
        steps.add(Step(label, paced) {
            check(++waited <= ticks) { "$name: timed out waiting for $label" }
            condition()
        })
    }

    fun start(onDone: () -> Unit) {
        var index = 0
        var ticks = 0
        var done = false
        val start = System.nanoTime()
        val sprint = System.getProperty("vanadium.live.sprint", "true").toBooleanStrict()
        fun paceNextStep() {
            if (!sprint) return
            val manager = server.tickRateManager()
            if (steps.getOrNull(index)?.paced == true) {
                if (manager.isSprinting) manager.stopSprinting()
            } else if (!manager.isSprinting) {
                manager.requestGameToSprint(maxTicks - ticks + 2)
            }
        }
        paceNextStep()
        println("LIVE_TICKS_START budget=$maxTicks sprint=$sprint steps=${steps.size}")
        ServerTickEvents.END_SERVER_TICK.register { current ->
            if (current !== server || done) return@register
            check(++ticks <= maxTicks) { "$name did not finish within $maxTicks ticks (at ${steps.getOrNull(index)?.label})" }
            if (index < steps.size) {
                // One step per tick: each observes the tick that follows the previous one.
                val step = steps[index]
                val finished = try {
                    step.run()
                } catch (e: Throwable) {
                    throw IllegalStateException("$name failed at '${step.label}' (server tick ${server.tickCount})", e)
                }
                if (finished) {
                    index++
                    paceNextStep()
                }
                return@register
            }
            done = true
            println("${name.uppercase()}_PASSED")
            println("LIVE_TICKS_DONE ticks=$ticks budget=$maxTicks elapsedMs=${(System.nanoTime() - start) / 1_000_000}")
            onDone()
        }
    }
}
