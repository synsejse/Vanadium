package com.synsenetwork.vanadium.config

import org.apache.logging.log4j.LogManager

/**
 * `-Dvanadium.*` developer and measurement inputs, read once per JVM. A malformed or out-of-range value never stops
 * the server or silently changes behaviour: it logs a warning and the default applies.
 */
internal object DeveloperProperties {
    private val LOGGER = LogManager.getLogger("Vanadium")

    /** Removed JVM overrides, with what replaces them where something does; setting one only logs a warning. */
    val RETIRED: Map<String, String?> = linkedMapOf(
        "vanadium.idleWorkers" to "set idleWorkers in config/vanadium.toml or with /vanadium set idleWorkers",
        "vanadium.workerSpinMicros" to null,
        "vanadium.callerSpinMicros" to null,
        "vanadium.serverSpinMicros" to null,
        "vanadium.callerWakes" to null,
        "vanadium.prewake" to null,
        "vanadium.prewakeLeadMicros" to null,
        "vanadium.prewakeGraceMicros" to null,
    )

    /** [name] as an integer within [range]; absent is [default], and anything else warns and is [default] too. */
    fun long(
        name: String,
        default: Long,
        range: LongRange,
        text: String? = System.getProperty(name),
        warn: (String) -> Unit = LOGGER::warn,
    ): Long {
        if (text == null) return default
        val value = text.trim().toLongOrNull()
        if (value != null && value in range) return value
        warn("Ignoring -D$name=$text: expected an integer from ${range.first} to ${range.last}; using the default $default")
        return default
    }

    /**
     * [name] as a finite number that [valid] accepts, which [expected] describes for the warning; absent is [default],
     * and anything else (malformed, NaN, infinite or rejected) warns and is [default] too.
     */
    fun double(
        name: String,
        default: Double,
        expected: String,
        text: String? = System.getProperty(name),
        warn: (String) -> Unit = LOGGER::warn,
        valid: (Double) -> Boolean,
    ): Double {
        if (text == null) return default
        val value = text.trim().toDoubleOrNull()
        if (value != null && value.isFinite() && valid(value)) return value
        warn("Ignoring -D$name=$text: expected $expected; using the default $default")
        return default
    }

    /** [name] as `true` or `false` (any case); absent is [default], and anything else warns and is [default] too. */
    fun boolean(name: String, default: Boolean, text: String? = System.getProperty(name), warn: (String) -> Unit = LOGGER::warn): Boolean {
        if (text == null) return default
        return when (text.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> {
                warn("Ignoring -D$name=$text: expected true or false; using the default $default")
                default
            }
        }
    }

    /** Warns once for each [RETIRED] property that is set: it was removed and is ignored. */
    fun warnRetired(value: (String) -> String? = System::getProperty, warn: (String) -> Unit = LOGGER::warn) {
        for ((name, instead) in RETIRED) {
            if (value(name) == null) continue
            warn("-D$name was removed and is ignored" + if (instead != null) "; $instead" else "")
        }
    }
}
