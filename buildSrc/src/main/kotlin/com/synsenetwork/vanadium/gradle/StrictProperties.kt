package com.synsenetwork.vanadium.gradle

/**
 * Optional `-P` properties of a manual fixture: an absent property takes its default, and any value that is set must
 * be valid, so a typo fails before launch instead of silently measuring the default.
 */
class StrictProperties(private val property: (String) -> String?) {
    operator fun get(name: String): String? = property(name)

    fun choice(name: String, default: String, choices: Collection<String>): String = (property(name) ?: default).also {
        require(it in choices) { "$name must be one of $choices, not '$it'" }
    }

    fun int(name: String, default: Int, range: IntRange): Int = intOrNull(name, range) ?: default

    /** Null when unset; a set value must still be an integer in [range]. */
    fun intOrNull(name: String, range: IntRange): Int? = property(name)?.let { value ->
        value.toIntOrNull().also { require(it != null && it in range) { "$name must be an integer in $range, not '$value'" } }
    }

    fun bool(name: String, default: Boolean): Boolean = property(name)?.let {
        requireNotNull(it.toBooleanStrictOrNull()) { "$name must be true or false, not '$it'" }
    } ?: default
}
