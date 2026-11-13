package com.synsenetwork.vanadium.integration

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer

/**
 * Checks that need real server ticks. Each step runs at the end of a tick until it returns true;
 * a step that throws, or a sequence that outlasts [MAX_TICKS], fails the live run.
 */
internal class TickedChecks(private val server: MinecraftServer, private val name: String) {
    private val steps = ArrayList<Pair<String, () -> Boolean>>()

    /** A step that is done after one tick. */
    fun then(label: String, action: () -> Unit) = until(label) { action(); true }

    fun until(label: String, step: () -> Boolean) {
        steps.add(label to step)
    }

    /** Waits up to [ticks] ticks for [condition]. */
    fun await(label: String, ticks: Int, condition: () -> Boolean) {
        var waited = 0
        until(label) {
            check(++waited <= ticks) { "$name: timed out waiting for $label" }
            condition()
        }
    }

    fun start(onDone: () -> Unit) {
        var index = 0
        var ticks = 0
        var done = false
        ServerTickEvents.END_SERVER_TICK.register { current ->
            if (current !== server || done) return@register
            check(++ticks <= MAX_TICKS) { "$name did not finish within $MAX_TICKS ticks (at ${steps.getOrNull(index)?.first})" }
            if (index < steps.size) {
                // One step per tick: each observes the tick that follows the previous one.
                val (label, step) = steps[index]
                val finished = try {
                    step()
                } catch (e: Throwable) {
                    throw IllegalStateException("$name failed at '$label' (server tick ${server.tickCount})", e)
                }
                if (finished) index++
                return@register
            }
            done = true
            println("${name.uppercase()}_PASSED")
            onDone()
        }
    }

    private companion object {
        const val MAX_TICKS = 500
    }
}
