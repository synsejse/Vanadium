package com.synsenetwork.vanadium.tick

/** One stage's execution policy; the caller joins every participant before inspecting the result. */
internal sealed interface RegionWave {
    val busyNanos: Long
    fun participants(participants: Int): List<Runnable>
    val failure: Throwable?
}
