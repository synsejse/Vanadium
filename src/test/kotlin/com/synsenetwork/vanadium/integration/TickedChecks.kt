package com.synsenetwork.vanadium.integration

import java.util.Locale
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer

/**
 * Checks that need real server ticks. Each step runs at the end of a tick until it returns true;
 * a step that throws, or a sequence that outlasts [maxTicks], fails the live run. Sprinting removes
 * the normal 50 ms tick pacing, not the tick callbacks, chunk-system work or completion barriers.
 *
 * Each step's ticks and wall time are logged when it finishes (`LIVE_STEP_DONE`), and each [phase]'s totals when its
 * last step does (`LIVE_PHASE_DONE`): a step's time runs from the end of the tick that finished the previous step.
 */
internal class TickedChecks(private val server: MinecraftServer, private val name: String) {
    private class Step(val phase: String, val label: String, val paced: Boolean, val run: () -> Boolean)
    private val steps = ArrayList<Step>()
    private var phase = name
    private val maxTicks = System.getProperty("vanadium.live.ticks", "500").toInt().also {
        require(it in 1..100_000) { "live.ticks must be between 1 and 100000" }
    }

    /** Steps added from now on belong to [name] in the timing log (a fixture's schedule, typically). */
    fun phase(name: String) {
        phase = name
    }

    /** A step that is done after one tick. */
    fun then(label: String, action: () -> Unit) = until(label) { action(); true }

    fun until(label: String, step: () -> Boolean) {
        steps.add(Step(phase, label, false, step))
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
        steps.add(Step(phase, label, paced) {
            check(++waited <= ticks) { "$name: timed out waiting for $label" }
            condition()
        })
    }

    fun start(onDone: () -> Unit) {
        var index = 0
        var ticks = 0
        var done = false
        val start = System.nanoTime()
        var stepStart = start
        var stepTicks = 0
        var phaseStart = start
        var phaseTicks = 0
        var pacedTicks = 0
        var phaseSteps = 0
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
                stepTicks++
                val finished = try {
                    step.run()
                } catch (e: Throwable) {
                    throw IllegalStateException("$name failed at '${step.label}' (server tick ${server.tickCount})", e)
                }
                if (finished) {
                    val now = System.nanoTime()
                    println("LIVE_STEP_DONE phase=${step.phase} ticks=$stepTicks paced=${step.paced} elapsedMs=${millis(now - stepStart)} step='${step.label}'")
                    phaseTicks += stepTicks
                    if (step.paced) pacedTicks += stepTicks
                    phaseSteps++
                    if (steps.getOrNull(index + 1)?.phase != step.phase) {
                        println("LIVE_PHASE_DONE ${step.phase} steps=$phaseSteps ticks=$phaseTicks pacedTicks=$pacedTicks " +
                            "elapsedMs=${millis(now - phaseStart)}")
                        phaseStart = now
                        phaseTicks = 0
                        pacedTicks = 0
                        phaseSteps = 0
                    }
                    stepStart = now
                    stepTicks = 0
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

    private fun millis(nanos: Long) = "%.1f".format(Locale.ROOT, nanos / 1e6)
}
