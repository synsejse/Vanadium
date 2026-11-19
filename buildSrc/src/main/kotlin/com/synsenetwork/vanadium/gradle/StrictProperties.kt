package com.synsenetwork.vanadium.gradle

/**
 * Optional `-P` properties of a manual fixture: an absent property takes its default, and any value that is set must
 * be valid, so a typo fails before launch instead of silently measuring the default.
 */
class StrictProperties(private val property: (String) -> String?) {
    operator fun get(name: String): String? = property(name)

    fun choice(name: String, default: String, choices: Collection<String>): String {
        val value = property(name) ?: default
        require(value in choices) { "$name must be one of $choices, not '$value'" }
        return value
    }

    fun int(name: String, default: Int, range: IntRange): Int = intOrNull(name, range) ?: default

    /** Null when unset; a set value must still be an integer in [range]. */
    fun intOrNull(name: String, range: IntRange): Int? {
        val text = property(name) ?: return null
        val value = text.toIntOrNull()
        require(value != null && value in range) { "$name must be an integer in $range, not '$text'" }
        return value
    }

    fun bool(name: String, default: Boolean): Boolean {
        val text = property(name) ?: return default
        return requireNotNull(text.toBooleanStrictOrNull()) { "$name must be true or false, not '$text'" }
    }
}
