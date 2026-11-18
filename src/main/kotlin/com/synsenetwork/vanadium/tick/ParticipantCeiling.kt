package com.synsenetwork.vanadium.tick

/**
 * Experimental upper bound on the threads, the caller included, that one stage's waves may use, on top of the
 * scheduler's own estimate ([TickScheduler.participants]). [AUTO] adds no bound: the estimate alone decides, as
 * without the ceiling. A bound of 1 runs the stage's regions inline on the caller, in plan order.
 *
 * Only natural spawn attempts take one, from `-Dvanadium.spawnParticipants` (see [TickScheduler]), so that
 * contention on vanilla's shared spawn state can be measured against fewer threads. It is not a tuning option.
 */
class ParticipantCeiling private constructor(
    /** Most participants, the caller included; null for [AUTO]. */
    val limit: Int?,
) {
    /** `auto`, or the limit: what results and logs record. */
    val label: String get() = limit?.toString() ?: AUTO_LABEL

    val isAuto: Boolean get() = limit == null

    /** [estimated] participants, at most [limit]. */
    fun apply(estimated: Int): Int = if (limit == null) estimated else minOf(estimated, limit)

    override fun equals(other: Any?): Boolean = other is ParticipantCeiling && other.limit == limit

    override fun hashCode(): Int = limit ?: 0

    override fun toString(): String = label

    companion object {
        private const val AUTO_LABEL = "auto"

        @JvmField val AUTO = ParticipantCeiling(null)

        /** A bound of [limit] participants, the caller included; at least 1. */
        @JvmStatic
        fun of(limit: Int): ParticipantCeiling {
            require(limit >= 1) { "a participant ceiling is at least 1 (the caller), not $limit" }
            return ParticipantCeiling(limit)
        }

        /**
         * Parses [property]'s value [text]: absent, empty or `auto` is [AUTO], a positive integer is a bound.
         * Anything else fails, `0` included: it would read as both "no limit" and "no threads", so neither is guessed.
         */
        @JvmStatic
        fun parse(property: String, text: String?): ParticipantCeiling {
            val value = text?.trim().orEmpty()
            if (value.isEmpty() || value.equals(AUTO_LABEL, ignoreCase = true)) return AUTO
            val limit = value.toIntOrNull()
            require(limit != null && limit >= 1) {
                "$property must be 'auto' or a participant count of at least 1, the server thread included " +
                    "(1 = inline on the server thread), not '$text'"
            }
            return ParticipantCeiling(limit)
        }
    }
}
